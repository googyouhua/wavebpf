package wavebpf

import spinal.core._
import spinal.sim._
import spinal.core.sim._

import org.scalatest.funsuite.AnyFunSuite

class SimCompiledAddSpec extends AnyFunSuite {
  // tests go here...
  test("SimCompiledAdd") {
    import SimUtil._
    val config = DefaultWbpfConfig()
    runWithAllBackends(
      new CustomWbpf(config.copy(pe = config.pe.copy(reportCommit = true)))
    ) { dut =>
      initDutForTesting(dut)

      val firstExc = dut.io.excOutput.head

      // Precompiled by wbpf-userspace
      /*
      extern void __attribute__((noreturn)) wbpf_host_complete();

      void __attribute__((noreturn)) add(int a, int b, int *out) {
       *out = a + b;
        wbpf_host_complete();
      }
       */
      /*
        mov32 r10, 0x0
        ldxdw r0, [r10+0x0]
        ldxdw r1, [r10+0x8]
        ldxdw r2, [r10+0x10]
        ldxdw r3, [r10+0x18]
        ldxdw r4, [r10+0x20]
        ldxdw r5, [r10+0x28]
        ldxdw r6, [r10+0x30]
        ldxdw r7, [r10+0x38]
        ldxdw r8, [r10+0x40]
        ldxdw r9, [r10+0x48]
        add64 r10, 0x50
        w_ret +0

      add:
        add32 r2, r1
        stxw [r3+0x0], r2
        call 0x400
       */
      loadCode(
        dut,
        0,
        0x0,
        Array[Short](
          0xb4, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x79, 0xa0, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x79, 0xa1, 0x08, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x79, 0xa2, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x79,
          0xa3, 0x18, 0x00, 0x00, 0x00, 0x00, 0x00, 0x79, 0xa4, 0x20, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x79, 0xa5, 0x28, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x79, 0xa6, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x79, 0xa7,
          0x38, 0x00, 0x00, 0x00, 0x00, 0x00, 0x79, 0xa8, 0x40, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x79, 0xa9, 0x48, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x07, 0x0a, 0x00, 0x00, 0x50, 0x00, 0x00, 0x00, 0x05, 0x10, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x0c, 0x12, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x63, 0x23, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x85,
          0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00
        ).map(_.toByte)
      )
      println("Code loaded.")

      assert(firstExc.valid.toBoolean)
      assert(firstExc.code.toEnum == CpuExceptionCode.NOT_INIT)

      dmWriteOnce(dut, 0x00, 0)
      dmWriteOnce(dut, 0x08, 1)
      dmWriteOnce(dut, 0x10, 2)
      dmWriteOnce(dut, 0x18, 0x100)
      dmWriteOnce(dut, 0x20, 0)
      dmWriteOnce(dut, 0x28, 0)
      dmWriteOnce(dut, 0x30, 0)
      dmWriteOnce(dut, 0x38, 0)
      dmWriteOnce(dut, 0x40, 0)
      dmWriteOnce(dut, 0x48, 0)
      dmWriteOnce(dut, 0x50, (BigInt("20000", 16) << 32) | BigInt("68", 16))

      mmioWrite(dut, 0x1018, 0x00)
      dut.clockDomain.waitSamplingWhere(!firstExc.valid.toBoolean)
      dut.clockDomain.waitSamplingWhere(firstExc.valid.toBoolean)
      assert(firstExc.code.toEnum == CpuExceptionCode.CALL)
      assert(firstExc.data.toBigInt == 1024)
      println("Check passed.")
    }
  }
}
