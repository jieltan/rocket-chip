package ReferenceChip

import Chisel._
import Node._
import uncore._
import rocket._
import ReferenceChipBackend._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap

object ReferenceChipBackend {
  val initMap = new HashMap[Component, Bool]()
}

class ReferenceChipBackend extends VerilogBackend
{
  override def emitPortDef(m: MemAccess, idx: Int) = {
    val res = new StringBuilder()
    for (node <- m.mem.inputs) {
      if(node.name.contains("init"))
         res.append("    .init(" + node.name + "),\n")
    }
    (if (idx == 0) res.toString else "") + super.emitPortDef(m, idx)
  }

  def addMemPin(c: Component) = {
    for (node <- Component.nodes) {
      if (node.isInstanceOf[Mem[ _ ]] && node.component != null && node.asInstanceOf[Mem[_]].inferSeqRead) {
        val init = Bool(INPUT)
        init.setName("init")
        node.inputs += init
        connectMemPin(c, node.component, init)
      }
    }
  }

  def connectMemPin(topC: Component, c: Component, p: Bool): Unit = {
    p.component = c
    var isNewPin = false
    val compInitPin = 
      if (initMap.contains(c)) {
        initMap(c)
      } else {
        isNewPin = true
        Bool(INPUT)
      }

    p.inputs += compInitPin

    if (isNewPin) {
      compInitPin.setName("init")
      c.io.asInstanceOf[Bundle] += compInitPin
      initMap += (c -> compInitPin)
      connectMemPin(topC, c.parent, compInitPin)
    }
  }

  def addTopLevelPin(c: Component) = {
    val init = Bool(INPUT)
    init.setName("init")
    init.component = c
    c.io.asInstanceOf[Bundle] += init
    initMap += (c -> init)
  }

  transforms += ((c: Component) => addTopLevelPin(c))
  transforms += ((c: Component) => addMemPin(c))
}

class OuterMemorySystem(ntiles: Int, co: CoherencePolicyWithUncached, resetSignal: Bool = null) extends Component(resetSignal)
{
  val io = new Bundle {
    val tiles = Vec(ntiles) { new ioTileLink() }.flip
    val htif = new ioTileLink().flip
    val mem_backup = new ioMemSerialized
    val mem_backup_en = Bool(INPUT)
    val mem = new ioMemPipe
  }

  import rocket.Constants._
  val hub = new CoherenceHubBroadcast(NTILES+1, co)
  val llc_tag_leaf = Mem(1024, seqRead = true) { Bits(width = 72) }
  val llc_data_leaf = Mem(4096, seqRead = true) { Bits(width = 64) }
  val llc = new DRAMSideLLC(512, 8, 4, llc_tag_leaf, llc_data_leaf)
  val mem_serdes = new MemSerdes

  for (i <- 0 until NTILES) {
    hub.io.tiles(i) <> io.tiles(i)
  }
  hub.io.tiles(NTILES) <> io.htif

  llc.io.cpu.req_cmd <> Queue(hub.io.mem.req_cmd)
  llc.io.cpu.req_data <> Queue(hub.io.mem.req_data, REFILL_CYCLES)
  hub.io.mem.resp <> llc.io.cpu.resp

  // mux between main and backup memory ports
  val mem_cmdq = (new Queue(2)) { new MemReqCmd }
  mem_cmdq.io.enq <> llc.io.mem.req_cmd
  mem_cmdq.io.deq.ready := Mux(io.mem_backup_en, mem_serdes.io.wide.req_cmd.ready, io.mem.req_cmd.ready)
  io.mem.req_cmd.valid := mem_cmdq.io.deq.valid && !io.mem_backup_en
  io.mem.req_cmd.bits := mem_cmdq.io.deq.bits
  mem_serdes.io.wide.req_cmd.valid := mem_cmdq.io.deq.valid && io.mem_backup_en
  mem_serdes.io.wide.req_cmd.bits := mem_cmdq.io.deq.bits

  val mem_dataq = (new Queue(REFILL_CYCLES)) { new MemData }
  mem_dataq.io.enq <> llc.io.mem.req_data
  mem_dataq.io.deq.ready := Mux(io.mem_backup_en, mem_serdes.io.wide.req_data.ready, io.mem.req_data.ready)
  io.mem.req_data.valid := mem_dataq.io.deq.valid && !io.mem_backup_en
  io.mem.req_data.bits := mem_dataq.io.deq.bits
  mem_serdes.io.wide.req_data.valid := mem_dataq.io.deq.valid && io.mem_backup_en
  mem_serdes.io.wide.req_data.bits := mem_dataq.io.deq.bits

  llc.io.mem.resp.valid := Mux(io.mem_backup_en, mem_serdes.io.wide.resp.valid, io.mem.resp.valid)
  llc.io.mem.resp.bits := Mux(io.mem_backup_en, mem_serdes.io.wide.resp.bits, io.mem.resp.bits)

  io.mem_backup <> mem_serdes.io.narrow
}

class Uncore(htif_width: Int, ntiles: Int, co: CoherencePolicyWithUncached) extends Component
{
  val io = new Bundle {
    val debug = new ioDebug()
    val host = new ioHost(htif_width)
    val host_clk = Bool(OUTPUT)
    val mem_backup = new ioMemSerialized
    val mem_backup_en = Bool(INPUT)
    val mem_backup_clk = Bool(OUTPUT)
    val mem = new ioMemPipe
    val tiles = Vec(ntiles) { new ioTileLink() }.flip
    val htif = Vec(ntiles) { new ioHTIF() }.flip
    val uncore_reset = Bool(OUTPUT)
  }

  import rocket.Constants._
  val htif = new rocketHTIF(htif_width, NTILES, co)

  for (i <- 0 until NTILES) {
    htif.io.cpu(i) <> io.htif(i)
  }

  val outmemsys = new OuterMemorySystem(ntiles, co)
  outmemsys.io.tiles <> io.tiles
  outmemsys.io.htif <> htif.io.mem
  io.mem <> outmemsys.io.mem
  outmemsys.io.mem_backup_en <> io.mem_backup_en
  io.uncore_reset := htif.io.cpu(NTILES-1).reset

  // pad out the HTIF using a divided clock
  val hio = (new slowIO(8)) { Bits(width = htif_width+1) }
  hio.io.out_fast.valid := htif.io.host.out.valid || outmemsys.io.mem_backup.req.valid
  hio.io.out_fast.bits := Cat(htif.io.host.out.valid, Mux(htif.io.host.out.valid, htif.io.host.out.bits, outmemsys.io.mem_backup.req.bits))
  htif.io.host.out.ready := hio.io.out_fast.ready
  outmemsys.io.mem_backup.req.ready := hio.io.out_fast.ready && !htif.io.host.out.valid
  io.host.out.valid := hio.io.out_slow.valid && hio.io.out_slow.bits(htif_width)
  io.host.out.bits := hio.io.out_slow.bits
  io.mem_backup.req.valid := hio.io.out_slow.valid && !hio.io.out_slow.bits(htif_width)
  hio.io.out_slow.ready := Mux(hio.io.out_slow.bits(htif_width), io.host.out.ready, io.mem_backup.req.ready)

  val mem_backup_resp_valid = io.mem_backup_en && io.mem_backup.resp.valid
  hio.io.in_slow.valid := mem_backup_resp_valid || io.host.in.valid
  hio.io.in_slow.bits := Cat(mem_backup_resp_valid, io.host.in.bits)
  io.host.in.ready := hio.io.in_slow.ready
  outmemsys.io.mem_backup.resp.valid := hio.io.in_fast.valid && hio.io.in_fast.bits(htif_width)
  outmemsys.io.mem_backup.resp.bits := hio.io.in_fast.bits
  htif.io.host.in.valid := hio.io.in_fast.valid && !hio.io.in_fast.bits(htif_width)
  htif.io.host.in.bits := hio.io.in_fast.bits
  hio.io.in_fast.ready := Mux(hio.io.in_fast.bits(htif_width), Bool(true), htif.io.host.in.ready)
  io.host_clk := hio.io.clk_slow
}

class ioTop(htif_width: Int) extends Bundle  {
  val debug   = new rocket.ioDebug();
  val host    = new rocket.ioHost(htif_width);
  val host_clk = Bool(OUTPUT)
  val mem_backup_en = Bool(INPUT)
  val in_mem_rdy = Bool(OUTPUT)
  val in_mem_val = Bool(INPUT)
  val out_mem_rdy = Bool(INPUT)
  val out_mem_val = Bool(OUTPUT)
  val mem     = new uncore.ioMem
}

class ReferenceChipTop extends Component {
  val clkdiv = 8
  import rocket.Constants._

  val co =  if(ENABLE_SHARING) {
              if(ENABLE_CLEAN_EXCLUSIVE) new MESICoherence
              else new MSICoherence
            } else {
              if(ENABLE_CLEAN_EXCLUSIVE) new MEICoherence
              else new MICoherence
            }

  val io = new ioTop(HTIF_WIDTH)

  val uncore = new Uncore(HTIF_WIDTH, NTILES, co)

  var error_mode = Bool(false)
  for (i <-0 until NTILES) {
    val hl = uncore.io.htif(i)
    val tl = uncore.io.tiles(i)
    val tile = new Tile(co, resetSignal = hl.reset)

    tile.io.host.reset := Reg(Reg(hl.reset))
    tile.io.host.pcr_req <> Queue(hl.pcr_req)
    hl.pcr_rep <> Queue(tile.io.host.pcr_rep)
    hl.ipi_req <> Queue(tile.io.host.ipi_req)
    tile.io.host.ipi_rep <> Queue(hl.ipi_rep)
    error_mode = error_mode || Reg(tile.io.host.debug.error_mode)

    tl.xact_init <> Queue(tile.io.tilelink.xact_init)
    tl.xact_init_data <> Queue(tile.io.tilelink.xact_init_data)
    tile.io.tilelink.xact_abort <> Queue(tl.xact_abort)
    tile.io.tilelink.xact_rep <> Queue(tl.xact_rep, 1, pipe = true)
    tl.xact_finish <> Queue(tile.io.tilelink.xact_finish)
    tile.io.tilelink.probe_req <> Queue(tl.probe_req)
    tl.probe_rep <> Queue(tile.io.tilelink.probe_rep, 1)
    tl.probe_rep_data <> Queue(tile.io.tilelink.probe_rep_data)
    tl.incoherent := hl.reset
  }

  io.host <> uncore.io.host
  io.host_clk := uncore.io.host_clk

  uncore.io.mem_backup.resp.valid := io.in_mem_val

  io.out_mem_val := uncore.io.mem_backup.req.valid
  uncore.io.mem_backup.req.ready := io.out_mem_rdy

  io.mem_backup_en <> uncore.io.mem_backup_en
  io.mem <> uncore.io.mem
  io.debug.error_mode := error_mode
}

object top_main {
  def main(args: Array[String]): Unit = { 
    val top = args(0)
    val chiselArgs = ArrayBuffer[String]()

    var i = 1
    while (i < args.length) {
      val arg = args(i)
      chiselArgs += arg
      i += 1
    }

    chiselMain(chiselArgs.toArray, () => Class.forName(top).newInstance.asInstanceOf[Component])
  }
}

