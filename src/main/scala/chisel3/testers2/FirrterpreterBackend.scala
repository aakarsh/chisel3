// See LICENSE for license details.

package chisel3.testers2

import chisel3._

import firrtl_interpreter._

class FirrterpreterBackend[T <: Module](dut: T, tester: InterpretiveTester) extends BackendInstance[T] {
  def getModule() = dut

  /** Returns a Seq of (data reference, fully qualified element names) for the input.
    * name is the name of data
    */
  protected def getDataNames(name: String, data: Data): Seq[(Data, String)] = Seq(data -> name) ++ (data match {
    case e: Element => Seq()
    case b: Record => b.elements.toSeq flatMap {case (n, e) => getDataNames(s"${name}_$n", e)}
    case v: Vec[_] => v.zipWithIndex flatMap {case (e, i) => getDataNames(s"${name}_$i", e)}
  })

  // TODO: the naming facility should be part of infrastructure not backend
  protected val portNames = getDataNames("io", dut.io).toMap

  def resolveName(signal: Data) =
    portNames.getOrElse(signal, signal.toString())

  def poke(signal: Data, value: BigInt): Unit = {
    signal match {
      case signal: Bits =>
        tester.poke(portNames(signal), value)
    }
  }

  def peek(signal: Data): BigInt = {
    signal match {
      case signal: Bits =>
        tester.peek(portNames(signal))
    }
  }

  def stalePeek(signal: Data): BigInt = {
    throw new Exception("Stale peek not implemented yet")
  }

  def check(signal: Data, value: BigInt): Unit = {
    Context().env.testerExpect(peek(signal), value, resolveName(signal), None)
  }

  def staleCheck(signal: Data, value: BigInt): Unit = {
    throw new Exception("Stale check not implemented yet")
  }

  def step(signal: Clock, cycles: Int): Unit = {
    // TODO: clock-dependence
    tester.step(cycles)
  }

  def run(testFn: T => Unit): Unit = {
    testFn(dut)
  }
}

object Firrterpreter {
  import chisel3.internal.firrtl.Circuit
  import chisel3.experimental.BaseModule

  import firrtl._

  def getTopModule(circuit: Circuit): BaseModule = {
    (circuit.components find (_.name == circuit.name)).get.id
  }

  def start[T <: Module](dutGen: => T): BackendInstance[T] = {
    val optionsManager = new ExecutionOptionsManager("chisel3")
        with HasChiselExecutionOptions with HasFirrtlOptions with HasInterpreterSuite {
      firrtlOptions = FirrtlExecutionOptions(
        compilerName = "low"
      )
    }

    chisel3.Driver.execute(optionsManager, () => dutGen) match {
      case ChiselExecutionSuccess(Some(circuit), _, Some(firrtlExecutionResult)) =>
        firrtlExecutionResult match {
          case FirrtlExecutionSuccess(_, compiledFirrtl) =>
            val dut = getTopModule(circuit).asInstanceOf[T]
            val interpretiveTester = new InterpretiveTester(compiledFirrtl, optionsManager)
            new FirrterpreterBackend(dut, interpretiveTester)
          case FirrtlExecutionFailure(message) =>
            throw new Exception(s"FirrtlBackend: failed firrtl compile message: $message")
        }
      case _ =>
        throw new Exception("Problem with compilation")
    }
  }
}