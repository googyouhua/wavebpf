package wavebpf

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

case class Controller(
    insnBufferConfig: InsnBufferConfig,
    context: PeContextData
) extends Component {
  val io = new Bundle {
    val mmio = slave(AxiLite4(MMIOBusConfigV2()))
    val refill = master Flow (InsnBufferRefillReq(insnBufferConfig))
    val pcUpdater = master Flow (PcUpdateReq())
    val excReport = in(new CpuException())
    val excAck = out(Bool())
  }

  val excAckReg = Reg(Bool()) init (False)
  io.excAck := excAckReg

  val refillCounter = Reg(UInt(insnBufferConfig.addrWidth bits)) init (0)
  val refillBuffer = Reg(Bits(32 bits))
  io.pcUpdater.payload.assignDontCare()
  io.pcUpdater.valid := False

  io.refill.setIdle()

  val mmio = WbpfUtil.axilite4Pipe(io.mmio)
  mmio.ar.setBlocked()
  mmio.aw.setBlocked()
  mmio.w.setBlocked()
  mmio.r.setIdle()
  mmio.b.setIdle()

  val awSnapshot = Reg(AxiLite4Ax(MMIOBusConfigV2()))
  val writeAddr = awSnapshot.addr(11 downto 2)

  val writeFsm = new StateMachine {
    val waitForAw: State = new State with EntryPoint {
      whenIsActive {
        when(mmio.aw.valid) {
          mmio.aw.ready := True
          awSnapshot := mmio.aw.payload
          goto(waitForW)
        }
      }
    }
    val waitForW: State = new State {
      whenIsActive {
        when(mmio.w.valid) {
          mmio.w.ready := True
          switch(writeAddr) {
            is(0x00) {
              val value = mmio.w.payload
                .data(insnBufferConfig.addrWidth - 1 downto 0)
                .asUInt
              refillCounter := value
            }
            is(0x01) {
              io.pcUpdater.valid := True
              io.pcUpdater.payload.pc := 0
              io.pcUpdater.payload.flush := True
              io.pcUpdater.payload.flushReason := PcFlushReasonCode.STOP
            }
            is(0x02) {
              refillBuffer := mmio.w.payload.data
            }
            is(0x04) {
              val data = mmio.w.payload.data ## refillBuffer
              io.refill.valid := True
              io.refill.payload.addr := refillCounter
              io.refill.payload.insn := data
              refillCounter := refillCounter + 1
            }
            is(0x06) {
              io.pcUpdater.valid := True
              io.pcUpdater.payload.pc := mmio.w.payload.data.asUInt.resized
              io.pcUpdater.payload.flush := True
              io.pcUpdater.payload.flushReason := PcFlushReasonCode.EXTERNAL
              // report(Seq("Update PC: ", mmio.w.payload.data.asUInt))
            }
            is(0x08) {
              // Exception ACK
              when(io.excReport.valid) {
                excAckReg := io.excReport.generation
              }
            }
          }
          goto(sendWriteRsp)
        }
      }
    }
    val sendWriteRsp: State = new State {
      whenIsActive {
        mmio.b.valid := True
        mmio.b.payload.resp := 0 // OKAY
        when(mmio.b.ready) {
          goto(waitForAw)
        }
      }
    }
  }

  val arSnapshot = Reg(AxiLite4Ax(MMIOBusConfigV2()))
  val readAddr = arSnapshot.addr(11 downto 2)

  val readFsm = new StateMachine {
    val waitForAr: State = new State with EntryPoint {
      whenIsActive {
        when(mmio.ar.valid) {
          mmio.ar.ready := True
          arSnapshot := mmio.ar.payload
          goto(sendReadRsp)
        }
      }
    }
    val sendReadRsp: State = new State {
      whenIsActive {
        mmio.r.valid := True
        mmio.r.payload.resp := 0 // OKAY

        switch(readAddr) {
          is(0x00) {
            mmio.r.payload.data := refillCounter.asBits.resized
          }
          is(0x06) {
            val pc = io.excReport.pc.asBits << 3
            val data = pc(31 downto 0)
            mmio.r.payload.data := io.excReport.valid.mux(
              (False, U(0, 32 bits).asBits),
              (True, data)
            )
          }
          is(0x08) {
            mmio.r.payload.data := io.excReport.valid.mux(
              (False, U(0, 32 bits).asBits),
              (True, io.excReport.code.asBits.resize(32 bits))
            )
          }
          is(0x0a) {
            val data = io.excReport.data(31 downto 0)
            mmio.r.payload.data := io.excReport.valid.mux(
              (False, U(0, 32 bits).asBits),
              (True, data.asBits)
            )
          }
          is(0x0b) {
            val data = io.excReport.data(63 downto 32)
            mmio.r.payload.data := io.excReport.valid.mux(
              (False, U(0, 32 bits).asBits),
              (True, data.asBits)
            )
          }
          is(0x0c) {
            val data =
              U(context.numPe, 16 bits) ## U(context.coreIndex, 16 bits)
            mmio.r.payload.data := data.asBits
          }
          default {
            mmio.r.payload.data := 0
          }
        }
        when(mmio.r.ready) {
          goto(waitForAr)
        }
      }
    }
  }
}

object MMIOBusConfigV2 {
  def apply() = AxiLite4Config(
    addressWidth = 32,
    dataWidth = 32
  )
}
