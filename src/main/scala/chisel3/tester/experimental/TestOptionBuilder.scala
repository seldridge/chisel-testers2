// See LICENSE for license details.

package chisel3.tester.experimental

import chisel3._
import chisel3.tester._
import chisel3.tester.internal.{TreadleBackendAnnotation, VerilatorBackendAnnotation}
import treadle.HasTreadleSuite
import firrtl.stage.CompilerAnnotation
import firrtl.{
  AnnotationSeq, ExecutionOptionsManager, LowFirrtlCompiler, MinimumVerilogCompiler,
  NoneCompiler, SystemVerilogCompiler, VerilogCompiler}

package object TestOptionBuilder {
  implicit class ChiselScalatestOptionBuilder[T <: MultiIOModule](x: ChiselScalatestTester#TestBuilder[T]) {
    def withAnnotations(annotationSeq: AnnotationSeq): ChiselScalatestTester#TestBuilder[T] = {
      new x.outer.TestBuilder[T](x.dutGen, annotationSeq)
    }

    @deprecated("Use withAnnotations instead")
    def withExecOptions(manager: ExecutionOptionsManager with HasTreadleSuite): ChiselScalatestTester#TestBuilder[T] = {
      val annos = manager.toAnnotationSeq.map {
        case CompilerAnnotation(compiler) if compiler.isInstanceOf[LowFirrtlCompiler]      => TreadleBackendAnnotation
        case CompilerAnnotation(compiler) if compiler.isInstanceOf[NoneCompiler]           => TreadleBackendAnnotation
        case CompilerAnnotation(compiler) if compiler.isInstanceOf[VerilogCompiler]        => VerilatorBackendAnnotation
        case CompilerAnnotation(compiler) if compiler.isInstanceOf[MinimumVerilogCompiler] => VerilatorBackendAnnotation
        case CompilerAnnotation(compiler) if compiler.isInstanceOf[SystemVerilogCompiler]  => VerilatorBackendAnnotation
        case anno => anno
      }

      new x.outer.TestBuilder[T](x.dutGen, annos)
    }

    @deprecated("Use withAnnotations instead")
    def withTesterOptions(opt: TesterOptions): ChiselScalatestTester#TestBuilder[T] = {
      new x.outer.TestBuilder[T](x.dutGen, opt.toAnnotations)
    }
  }
}
