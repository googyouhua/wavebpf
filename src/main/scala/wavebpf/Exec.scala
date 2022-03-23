package wavebpf

import spinal.core._
import spinal.lib._
import spinal.lib.bus.wishbone._

object MemoryAccessWidth extends SpinalEnum(binarySequential) {
  val W1, W2, W4, W8 = newElement()

  def alignedMask(self: SpinalEnumCraft[this.type]): Bits = {
    val x = Bits(8 bits)
    switch(self) {
      is(W1) {
        x := 0x1
      }
      is(W2) {
        x := 0x3
      }
      is(W4) {
        x := 0xf
      }
      is(W8) {
        x := 0xff
      }
    }
    x
  }
}

case class ExecConfig(
    insnFetch: InsnBufferConfig,
    regFetch: RegfetchConfig,
    splitAluMem: Boolean
)

case class AluStageInsnContext(
    c: ExecConfig
) extends Bundle {
  val regFetch = RegGroupContext(c.regFetch, Bits(64 bits))
  val insnFetch = InsnBufferReadRsp(c.insnFetch)
  val regWritebackValid = Bool()
  val regWriteback = RegContext(c.regFetch, Bits(64 bits))
  val exc = CpuException()
  val memory = new MemoryAccessReq(c)
  val br = BranchReq()
}

case class MemoryAccessReq(
    c: ExecConfig
) extends Bundle {
  val valid = Bool()
  val store = Bool()
  val addr = UInt(64 bits)
  val rd = UInt(log2Up(c.regFetch.numRegs) bits)
  val width = MemoryAccessWidth()
}

case class MemoryStageInsnContext(
    c: ExecConfig
) extends Bundle {
  val regFetch = RegGroupContext(c.regFetch, Bits(64 bits))
  val insnFetch = InsnBufferReadRsp(c.insnFetch)
  val regWritebackValid = Bool()
  val regWriteback = RegContext(c.regFetch, Bits(64 bits))
  val exc = CpuException()
  val br = BranchReq()
}

case class BranchReq() extends Bundle {
  val valid = Bool()
  val addr = UInt(61 bits)
}

case class Exec(c: ExecConfig) extends Component {
  val io = new Bundle {
    val regFetch = slave Stream (RegGroupContext(c.regFetch, Bits(64 bits)))
    val regWriteback = master Flow (RegContext(c.regFetch, Bits(64 bits)))
    val dataMem = master(DataMemV2Port())
    val insnFetch = slave Stream (InsnBufferReadRsp(c.insnFetch))
    val excOutput = out(CpuException())
    val branchPcUpdater = master Flow (PcUpdateReq())
  }

  val rs1Bypass =
    new BypassNetwork(UInt(log2Up(c.regFetch.numRegs) bits), Bits(64 bits))
  val rs2Bypass =
    new BypassNetwork(UInt(log2Up(c.regFetch.numRegs) bits), Bits(64 bits))
  val rs1MemStallBypass =
    new BypassNetwork(UInt(log2Up(c.regFetch.numRegs) bits), Bool)
  val rs2MemStallBypass =
    new BypassNetwork(UInt(log2Up(c.regFetch.numRegs) bits), Bool)

  rs1Bypass.srcKey := io.regFetch.rs1.index
  rs1Bypass.srcValue := io.regFetch.rs1.data
  rs2Bypass.srcKey := io.regFetch.rs2.index
  rs2Bypass.srcValue := io.regFetch.rs2.data
  rs1MemStallBypass.srcKey := io.regFetch.rs1.index
  rs1MemStallBypass.srcValue := False
  rs2MemStallBypass.srcKey := io.regFetch.rs2.index
  rs2MemStallBypass.srcValue := False

  val rfOverride = RegGroupContext(c.regFetch, Bits(64 bits))
  rfOverride.rs1.index := io.regFetch.rs1.index
  rfOverride.rs1.data := rs1Bypass.bypassed
  rfOverride.rs2.index := io.regFetch.rs2.index
  rfOverride.rs2.data := rs2Bypass.bypassed

  val aluStage = new ExecAluStage(c)
  io.regFetch
    .translateWith(rfOverride)
    .continueWhen(
      !rs1MemStallBypass.bypassed && !rs2MemStallBypass.bypassed
    ) >> aluStage.io.regFetch
  io.insnFetch >> aluStage.io.insnFetch
  val memStage =
    new ExecMemoryStage(
      c,
      Seq(rs1Bypass, rs2Bypass),
      Seq(rs1MemStallBypass, rs2MemStallBypass)
    )

  val memOutputFlow = memStage.io.output.toFlow

  memStage.io.aluStage << aluStage.io.output
  memStage.io.dataMem.request >> io.dataMem.request
  memStage.io.dataMem.response << io.dataMem.response
  memOutputFlow
    .throwWhen(!memStage.io.output.payload.regWritebackValid)
    .translateWith(memStage.io.output.payload.regWriteback) >> io.regWriteback
  io.excOutput := memStage.io.excOutput

  val pcUpdateReq = PcUpdateReq()
  pcUpdateReq.pc := memStage.io.output.payload.br.addr
  pcUpdateReq.flush := True
  pcUpdateReq.flushReason := PcFlushReasonCode.BRANCH_RESOLVE
  memOutputFlow
    .throwWhen(!memStage.io.output.payload.br.valid)
    .translateWith(pcUpdateReq) >> io.branchPcUpdater

  when(memOutputFlow.fire) {
    report(
      Seq(
        "COMMIT",
        " pc=",
        memStage.io.output.payload.insnFetch.addr,
        " regwb=",
        memStage.io.output.payload.regWritebackValid,
        " regwb.index=",
        memStage.io.output.payload.regWriteback.index,
        " regwb.data=",
        memStage.io.output.payload.regWriteback.data
      )
    )
  }
}

case class ExecMemoryStage(
    c: ExecConfig,
    bypass: Seq[BypassNetwork[UInt, Bits]],
    stallBypass: Seq[BypassNetwork[UInt, Bool]]
) extends Area {
  private def provideBypassResource(stage: Stream[AluStageInsnContext]) {
    for (net <- bypass) {
      net.provide(
        2,
        0,
        stage.valid && stage.regWritebackValid && stage.regWriteback.index === net.srcKey,
        stage.regWriteback.data
      )
    }
    for (net <- stallBypass) {
      net.provide(
        2,
        0,
        stage.valid && stage.memory.rd === net.srcKey && stage.memory.valid && !stage.memory.store,
        True
      )
    }
  }
  val io = new Bundle {
    val aluStage = Stream(AluStageInsnContext(c))
    val dataMem = DataMemV2Port()
    val output = Stream(MemoryStageInsnContext(c))
    val excOutput = CpuException()
  }

  val outData = MemoryStageInsnContext(c)
  val memRead = Bits(64 bits)

  val excRegInit = CpuException()
  excRegInit.valid := True
  excRegInit.code := CpuExceptionCode.NOT_INIT
  excRegInit.data := 0

  val excReg = Reg(CpuException()) init (excRegInit)
  io.excOutput := excReg

  val nextExc = CpuException()
  val wasBranch = Bool(false)

  when(
    io.aluStage.valid && (
      (!excReg.valid && io.aluStage.payload.exc.valid) || (excReg.valid && (
        io.aluStage.payload.insnFetch.ctx.flush &&
          ControlFlowPriority.forFlushReason(
            io.aluStage.payload.insnFetch.ctx.flushReason
          ) <=
          ControlFlowPriority.forException(excReg.code)
      ))
    )
  ) {
    nextExc := io.aluStage.payload.exc
  } elsewhen (io.aluStage.valid && !excReg.valid && io.aluStage.payload.br.valid) {
    nextExc.valid := True
    nextExc.code := CpuExceptionCode.PENDING_BRANCH
    nextExc.data := 0
    wasBranch.set()
  } otherwise {
    nextExc := excReg
  }

  excReg := nextExc

  var maskedAluOutput = io.aluStage.throwWhen(nextExc.valid && !wasBranch)
  if (c.splitAluMem) {
    maskedAluOutput = maskedAluOutput.stage()
    provideBypassResource(maskedAluOutput)
  }

  val (maskedAluOutputToMem, maskedAluOutputToStage) = StreamFork2(
    maskedAluOutput
  )

  val memReq = DataMemRequest()
  memReq.addr := maskedAluOutput.memory.addr
  memReq.write := maskedAluOutput.memory.store
  memReq.ctx.assignDontCare()
  memReq.data := maskedAluOutput.regFetch.rs2.data // TODO: imm
  memReq.width := maskedAluOutput.memory.width
  maskedAluOutputToMem.translateWith(memReq) >> io.dataMem.request

  val maskedAluOutputStaged = maskedAluOutputToStage.stage()

  val writebackOverride = RegContext(c.regFetch, Bits(64 bits))
  writebackOverride.index := maskedAluOutputStaged.memory.rd
  writebackOverride.data := io.dataMem.response.payload.data

  outData.insnFetch := maskedAluOutputStaged.insnFetch
  outData.regFetch := maskedAluOutputStaged.regFetch

  val shouldWriteback =
    maskedAluOutputStaged.memory.valid && !maskedAluOutputStaged.memory.store
  outData.regWritebackValid := shouldWriteback
    .mux(
      (False, maskedAluOutputStaged.regWritebackValid),
      (True, True)
    )
  outData.regWriteback := shouldWriteback
    .mux(
      (False, maskedAluOutputStaged.regWriteback),
      (True, writebackOverride)
    )
  outData.exc := maskedAluOutputStaged.exc
  outData.br := maskedAluOutputStaged.br

  io.output << StreamJoin(maskedAluOutputStaged, io.dataMem.response)
    .translateWith(outData)

  provideBypassResource(maskedAluOutputStaged)
}

case class ExecAluStage(c: ExecConfig) extends Component {
  val io = new Bundle {
    val regFetch = slave Stream (RegGroupContext(c.regFetch, Bits(64 bits)))
    val insnFetch = slave Stream (InsnBufferReadRsp(c.insnFetch))
    val output = master Stream (AluStageInsnContext(c))
  }

  val opcode = io.insnFetch.payload.insn(7 downto 0)
  val rdIndex = io.insnFetch.payload.insn(11 downto 8).asUInt
  val imm = io.insnFetch.payload.insn(63 downto 32).asSInt.resize(64).asUInt
  val rs1 = io.regFetch.payload.rs1.data.asUInt
  val rs2 = io.regFetch.payload.rs2.data.asUInt
  val offset = io.insnFetch.payload.insn(31 downto 16).asSInt.resize(64).asUInt

  val operand2 = UInt(64 bits)
  when(opcode(3)) {
    operand2 := rs2
  } otherwise {
    operand2 := imm
  }

  val exc = CpuException()
  exc.code.assignDontCare()
  exc.data.assignDontCare()
  exc.valid := False

  val memory = new MemoryAccessReq(c)
  memory.assignDontCare()
  memory.valid := False
  memory.rd := rdIndex

  val regWritebackData = RegContext(c.regFetch, Bits(64 bits))
  regWritebackData.assignDontCare()
  regWritebackData.index := 0
  val regWritebackValid = Bool()
  regWritebackValid := False

  val isStore = opcode(1)
  memory.store := isStore
  memory.addr := isStore.mux(
    (False, rs2),
    (True, rs1)
  ) + offset

  val br = BranchReq()
  br.valid := False
  br.addr := io.insnFetch.payload.addr.resize(61 bits) + 1 + offset.resize(
    61 bits
  )

  val condEq = rs1 === operand2
  val condNe = rs1 =/= operand2
  val condLt = rs1 < operand2
  val condLe = condLt || condEq
  val condGt = !condLe
  val condGe = !condLt
  val condSlt = rs1.asSInt < operand2.asSInt
  val condSle = condSlt || condEq
  val condSgt = !condSle
  val condSge = !condSlt

  switch(opcode) {
    is(M"0000-111") { // 0x07/0x0f, dst += imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1 + operand2).asBits
    }
    is(M"0001-111") { // 0x17/0x1f, dst -= imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1 - operand2).asBits
    }
    is(M"0010-111") { // 0x27/0x2f, dst *= imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1 * operand2)(63 downto 0).asBits
    }
    is(M"0011-111") { // 0x37/0x3f, dst /= imm
      // division not implemented
      exc.valid := True
      exc.code := CpuExceptionCode.BAD_INSTRUCTION
      exc.data := io.insnFetch.payload.insn.asUInt
    }
    is(M"0100-111") { // 0x47/0x4f, dst |= imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1 | operand2).asBits
    }
    is(M"0101-111") { // 0x57/0x5f, dst &= imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1 & operand2).asBits
    }
    is(M"0110-111") { // 0x67/0x6f, dst <<= imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1 << operand2(5 downto 0))(63 downto 0).asBits
    }
    is(M"0111-111") { // 0x77/0x7f, dst >>= imm (logical)
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1 >> operand2(5 downto 0))(63 downto 0).asBits
    }
    is(M"10000111") { // 0x87, dst = -dst
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (-rs1.asSInt).asBits
    }
    is(M"1001-111") { // 0x97/0x9f, dst %= imm
      // division not implemented
      exc.valid := True
      exc.code := CpuExceptionCode.BAD_INSTRUCTION
      exc.data := io.insnFetch.payload.insn.asUInt
    }
    is(M"1010-111") { // 0xa7/0xaf, dst ^= imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1 ^ operand2).asBits
    }
    is(M"1011-111") { // 0xb7/0xbf, dst = imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := operand2.asBits
    }
    is(M"1100-111") { // 0xc7/0xcf, dst >>= imm (arithmetic)
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := (rs1.asSInt >> operand2(5 downto 0))(
        63 downto 0
      ).asBits
    }
    is(0x18) { // dst = imm
      regWritebackValid := True
      regWritebackData.index := rdIndex
      regWritebackData.data := imm.asBits
    }
    is(0x61, 0x62, 0x63) {
      memory.valid := True
      memory.width := MemoryAccessWidth.W4
    }
    is(0x69, 0x6a, 0x6b) {
      memory.valid := True
      memory.width := MemoryAccessWidth.W2
    }
    is(0x71, 0x72, 0x73) {
      memory.valid := True
      memory.width := MemoryAccessWidth.W1
    }
    is(0x79, 0x7a, 0x7b) {
      memory.valid := True
      memory.width := MemoryAccessWidth.W8
    }

    // Branch ops
    is(0x05) {
      // PC += off
      br.valid := True
    }
    is(0x15, 0x1d) {
      // jeq
      br.valid := condEq
    }
    is(0x25, 0x2d) {
      // jgt
      br.valid := condGt
    }
    is(0x35, 0x3d) {
      // jge
      br.valid := condGe
    }
    is(0xa5, 0xad) {
      // jlt
      br.valid := condLt
    }
    is(0xb5, 0xbd) {
      // jle
      br.valid := condLe
    }
    is(0x55, 0x5d) {
      // jne
      br.valid := condNe
    }
    is(0x65, 0x6d) {
      // jsgt
      br.valid := condSgt
    }
    is(0x75, 0x7d) {
      // jsge
      br.valid := condSge
    }
    is(0xc5, 0xcd) {
      // jslt
      br.valid := condSlt
    }
    is(0xd5, 0xdd) {
      // jsle
      br.valid := condSle
    }
    is(0x95) {
      // exit
      exc.valid := True
      exc.code := CpuExceptionCode.EXIT
      exc.data := 0
    }
    default {
      exc.valid := True
      exc.code := CpuExceptionCode.BAD_INSTRUCTION
      exc.data := io.insnFetch.payload.insn.asUInt
    }
  }

  val ctxOut = AluStageInsnContext(c)
  ctxOut.regFetch := io.regFetch
  ctxOut.insnFetch := io.insnFetch
  ctxOut.regWritebackValid := regWritebackValid
  ctxOut.regWriteback := regWritebackData
  ctxOut.exc := exc
  ctxOut.memory := memory
  ctxOut.br := br

  val outStream = StreamJoin
    .arg(io.regFetch, io.insnFetch)
    .translateWith(ctxOut)

  io.output << outStream

  /*when(!outStream.valid) {
    report(L"exec alu stall")
  }*/

  /*when(outStream.valid && memory.valid) {
    report(
      Seq(
        "Memory access at ",
        memory.addr,
        " with width ",
        memory.width,
        " and store ",
        memory.store
      )
    )
  }*/
}
