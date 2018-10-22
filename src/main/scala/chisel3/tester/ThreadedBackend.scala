// See LICENSE for license details.

package chisel3.tester

import java.util.concurrent.{ConcurrentLinkedQueue, Semaphore, SynchronousQueue, TimeUnit}
import scala.collection.mutable

import chisel3._

/** Base trait for backends implementing concurrency by threading. Also implements timescopes.
  */
trait ThreadedBackend {
  //
  // Variable references
  //
  val combinationalPaths: Map[Data, Set[Data]]  // set of combinational Data inputs for each output
  val dataNames: Map[Data, String]  // FIRRTL string name for each Data

  //
  // Common variables
  //

  var currentTimestep: Int = 0  // current simulator timestep, in number of base clock cycles

  //
  // Timescope data structures and utilities
  //

  sealed abstract class BaseTimescope {
    def threadOption: Option[TesterThread]
  }

  // Root timescope, cannot be modified, with no signals poked.
  class RootTimescope extends BaseTimescope {
    override def threadOption = None
  }

  // Timescope that is the root of a thread, contains no signals but can have a parent
  case class ThreadRootTimescope(parentTimescope: BaseTimescope,
      openedTimestep: Int,
      parentActionId: Int,
      thread: TesterThread) extends BaseTimescope {
    override def threadOption = Some(thread)
  }

  case class PokeRecord(timestep: Int, actionId: Int, value: BigInt, trace: Throwable)

  class Timescope(val parentTimescope: BaseTimescope,
      val openedTimestep: Int,  // timestep this timescope was spawned on
      val parentActionId: Int)  // spawn time in actionId of parent timescope)
      extends BaseTimescope {
    var nextActionId: Int = 0  // actionId to be assigned to the next action
    var closedTimestep: Option[Int] = None  // timestep this timescope was closed on, if it is closed

    override def threadOption = parentTimescope.threadOption

    // Latest poke on a signal in this timescope
    val pokes = mutable.HashMap[Data, PokeRecord]()  // Latest poke on each data
    // TODO: do we need to record earlier pokes, to differentiate new pokes?
  }

  //
  // Threading checking data structures and utilities
  //

  // List of the top Timescope for every Data object.
  // During the testdriver phase, new timescopes may be added that supersede previous ones, but
  // none are to be removed until during the checking phase.
  val activePokes = mutable.HashMap[Data, mutable.ListBuffer[Timescope]]()

  case class PeekRecord(timescope: Timescope, timestep: Int, actionId: Int, trace: Throwable)
  // Active peeks on a signal, instantaneous on the current timestep
  // TODO: should this last until the next associated clock edge?
  protected val signalPeeks = mutable.HashMap[Data, mutable.ListBuffer[PeekRecord]]()

  /**
   * Logs a poke operation for later checking.
   * Returns whether to execute it, based on priorities compared to other active pokes.
   */
  def doPoke(signal: Data, value: BigInt, trace: Throwable): Unit = {
    val timescope = currentThread.get.getTimescope
    // Update the timescope
    timescope.pokes.put(signal,
        PokeRecord(currentTimestep, timescope.nextActionId, value, trace))
    timescope.nextActionId += 1

    // Update the global active pokes list
    val signalActivePokes = activePokes.getOrElseUpdate(signal, mutable.ListBuffer())
    if (!signalActivePokes.contains(timescope)) {
      signalActivePokes += timescope
    }
  }

  /**
   * Logs a peek operation for later checking.
   */
  def doPeek(signal: Data, trace: Throwable): Unit = {
    val timescope = currentThread.get.getTimescope
    signalPeeks.getOrElseUpdate(signal, mutable.ListBuffer()) +=
        PeekRecord(timescope, currentTimestep, timescope.nextActionId, trace)
    timescope.nextActionId += 1
  }

  /**
   * Creates a new timescope in the current thread.
   */
  def newTimescope(): Timescope = {
    val newTimescope = currentThread.get.topTimescope match {
      case timescope: Timescope =>
        val newTimescope = new Timescope(timescope, currentTimestep, timescope.nextActionId)
        timescope.nextActionId += 1
        newTimescope
      case timescope => new Timescope(timescope, currentTimestep, 0)
    }
    currentThread.get.topTimescope = newTimescope
    newTimescope
  }

  /**
   * Closes the specified timescope, returns a map of wires to values of any signals that need to be updated.
   */
  def closeTimescope(timescope: Timescope): Map[Data, Option[BigInt]] = {
    require(timescope eq currentThread.get.getTimescope)

    // Mark timescope as closed
    timescope.closedTimestep = Some(currentTimestep)
    currentThread.get.topTimescope = timescope.parentTimescope

    // Build a revert map of poked signals
    timescope.pokes.map { case (data, pokeRecord) =>
      def getPreviousPoke(startingTimescope: BaseTimescope, signal: Data): Option[PokeRecord] = {
        startingTimescope match {
          case _: RootTimescope => None
          case startingTimescope: ThreadRootTimescope => getPreviousPoke(startingTimescope.parentTimescope, signal)
          case startingTimescope: Timescope => startingTimescope.pokes.get(signal) match {
            case Some(pokeRecord) => Some(pokeRecord)
            case None => getPreviousPoke(startingTimescope.parentTimescope, signal)
          }
        }
      }
      val previousPoke = getPreviousPoke(timescope.parentTimescope, data)
      (data, previousPoke.map(_.value))
    }.toMap
  }

  /**
   * Starts a new timestep, checking if there were any conflicts on the previous timestep (and
   * throwing exceptions if there were).
   */
  def timestep(): Unit = {
    // TODO ALL THE CHECKING CODE
  }

  protected val interruptedException = new ConcurrentLinkedQueue[Throwable]()
  /**
   * Called when an exception happens inside a thread.
   * Can be used to propagate the exception back up to the main thread.
   * No guarantees are made about the state of the system on an exception.
   *
   * The thread then terminates, and the thread scheduler is invoked to unblock the next thread.
   * The implementation should only record the exception, which is properly handled later.
   */
  protected def onException(e: Throwable) {
    interruptedException.offer(e)
  }

  protected class TesterThread(runnable: () => Unit,
      openedTimestep: Int, parentTimescope: BaseTimescope, parentActionId: Int)
      extends AbstractTesterThread {
    val level: Int = parentTimescope.threadOption match {
      case Some(parentThread) => parentThread.level + 1
      case None => 0
    }
    val waiting = new Semaphore(0)
    var done: Boolean = false

    // TODO: perhaps accessors eg pushTimescope, popTimescope?
    protected val bottomTimescope = new ThreadRootTimescope(parentTimescope, openedTimestep, parentActionId, this)
    var topTimescope: BaseTimescope = bottomTimescope  // Currently open timescope in this thread
    def getTimescope = {
      require(topTimescope.asInstanceOf[Timescope].closedTimestep.isEmpty)
      topTimescope.asInstanceOf[Timescope]
    }

    val thread = new Thread(new Runnable {
      def run() {
        try {
          waiting.acquire()

          timescope {  // TODO breaks consistent level of abstraction
            runnable()
          }

          require(bottomTimescope == topTimescope)  // ensure timescopes unrolled properly
          done = true
          threadFinished(TesterThread.this)
        } catch {
          case e: InterruptedException =>
            // currently used as a signal to kill the thread without doing cleanup
            // (testdriver may be left in an inconsistent state, and the test should not continue)
            // TODO: allow other uses for InterruptedException?
          case e @ (_: Exception | _: Error) => onException(e)
        } finally {
          // TODO should there be something similar to done if thread was terminated by an exception
          scheduler()
        }
      }
    })
  }

  protected var currentThread: Option[TesterThread] = None
  protected val driverSemaphore = new Semaphore(0)  // blocks the driver thread while tests are running

  // TODO: does this need to be replaced with concurrent data structures?
  val allThreads = mutable.ArrayBuffer[TesterThread]()  // list of all threads, only used for sanity checking
  val joinedThreads = mutable.HashMap[TesterThread, Seq[TesterThread]]()  // threads blocking on another thread

  // TODO make this a class and Option[SchedulerState] var that contains currentThread and driverSemaphore?
  object schedulerState {  // temporary scheduler information not persisting between runThreads invocations
    var currentLevel: Int = -1  // -1 indicates nothing is running

    // map of levels to threads, initialized during runThreads entry and as lower level threads are unblocked, or new threads spawn
    // note: spawned threads are added to their parents level
    val activeThreads = mutable.HashMap[Int, mutable.ListBuffer[TesterThread]]()

    // list of threads blocked on a clock edge, added to as threads finish and step
    val blockedThreads = mutable.HashMap[Clock, mutable.ListBuffer[TesterThread]]()
  }

  /**
   * Runs the specified threads, blocking this thread while those are running.
   * Newly formed threads or unblocked join threads will also run.
   * Returns a list of threads run (either passed in, newly forked, or joined) that are waiting
   * on a clock edge.
   *
   * TODO: uses shared schedule state, as an optimization (so that control doesn't need to return
   * to the driver thread between runs. But control still needs to return to the calling thread
   * between timesteps.
   * TODO: this provides a separation which isolates the threading infrastructure from clock
   * control, but there are other structures which accomplish the same thing.
   */
  protected def runThreads(threads: Seq[TesterThread]): Map[Clock, Seq[TesterThread]] = {
    // Populate scheduler date, sort threads by level
    require(schedulerState.currentLevel == -1)
    require(schedulerState.activeThreads.isEmpty)
    require(schedulerState.blockedThreads.isEmpty)

    val threadsByLevel = threads.groupBy(_.level)
    schedulerState.activeThreads ++= threadsByLevel.mapValues(mutable.ListBuffer(_: _*)).toSeq
    schedulerState.currentLevel = threadsByLevel.keySet.max

    scheduler()
    driverSemaphore.acquire()

    if (!interruptedException.isEmpty()) {
      throw interruptedException.poll()
    }

    require(schedulerState.activeThreads.isEmpty)
    val rtn = schedulerState.blockedThreads.mapValues(_.toSeq).toMap
    schedulerState.blockedThreads.clear()
    schedulerState.currentLevel = -1
    rtn
  }

  /**
   * Invokes the thread scheduler, which should be done anytime a thread needs to pass time.
   * Prior to this call: caller should add itself to the blocked / joined threads list
   * (unless terminating).
   * After this call: caller should block on its semaphore (unless terminating). currentThread
   * will no longer be valid.
   *
   * Unblocks the next thread to be run, possibly also also stepping time via advanceTime().
   * When there are no more active threads, unblocks the driver thread via driverSemaphore.
   */
  protected def scheduler() {
    // TODO cleanup checks logic
    if (schedulerState.activeThreads(schedulerState.currentLevel).isEmpty) {
      schedulerState.activeThreads.remove(schedulerState.currentLevel)
    }

    if (!interruptedException.isEmpty() || schedulerState.activeThreads.isEmpty) {
      currentThread = None
      driverSemaphore.release()
    } else {
      if (!schedulerState.activeThreads.contains(schedulerState.currentLevel)) {
        require(schedulerState.currentLevel > schedulerState.activeThreads.keySet.max)
        schedulerState.currentLevel = schedulerState.activeThreads.keySet.max
      }
      val threadList = schedulerState.activeThreads(schedulerState.currentLevel)
      val nextThread = threadList.head
      currentThread = Some(nextThread)
      threadList.trimStart(1)
      nextThread.waiting.release()
    }
  }

  /**
   * Called on thread completion to remove this thread from the running list.
   * Does not terminate the thread, does not schedule the next thread.
   */
  protected def threadFinished(thread: TesterThread) {
    allThreads -= thread
    joinedThreads.remove(thread) match {
      case Some(testerThreads) => testerThreads.foreach(testerThread => {
        val threadLevel = testerThread.level
        require(schedulerState.currentLevel > threadLevel)
        schedulerState.activeThreads.getOrElseUpdate(threadLevel, mutable.ListBuffer[TesterThread]()) += testerThread
      })
      case None =>
    }
  }

  def doFork(runnable: () => Unit): TesterThread = {
    val timescope = currentThread.get.getTimescope
    val newThread = new TesterThread(runnable, currentTimestep, timescope, timescope.nextActionId)
    timescope.nextActionId += 1

    allThreads += newThread
    schedulerState.activeThreads(schedulerState.currentLevel) += newThread
    newThread.thread.start()
    newThread
  }

  def doJoin(thread: AbstractTesterThread) = {
    val thisThread = currentThread.get
    val threadTyped = thread.asInstanceOf[TesterThread]  // TODO get rid of this, perhaps by making it typesafe
    require(thisThread.level < threadTyped.level)
    if (!threadTyped.done) {
      joinedThreads.put(threadTyped, joinedThreads.getOrElseUpdate(threadTyped, Seq()) :+ thisThread)
      scheduler()
      thisThread.waiting.acquire()
    }  // otherwise do nothing if target thread is already finished
  }
}
