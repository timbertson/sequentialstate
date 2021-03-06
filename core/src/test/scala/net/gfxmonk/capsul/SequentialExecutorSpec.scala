package net.gfxmonk.capsul

import java.util.concurrent.{ExecutorService, Executors}

import org.scalatest._
import org.scalatest.concurrent._

import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.immutable.Queue
import scala.collection.mutable
import net.gfxmonk.capsul.internal.Log
import net.gfxmonk.capsul.testsupport._

object SequentialExecutorSpec {
	def group[A](items: List[A]): List[(A, Int)] = {
		def fold(items: List[A]): List[(A, Int)] = {
			items match {
				case Nil => Nil
				case head::tail => {
					val (additional, rest) = tail.span(_ == head)
					(head -> (1 + additional.length)) :: fold(rest)
				}
			}
		}
		fold(items.reverse).reverse
	}

	class Ctx(bufLen: Int, val ec: InspectableExecutionContext) {
		implicit val executionContext: ExecutionContext = ec
		var count = new Ctx.Count
		val ex = new SequentialExecutor(bufLen)(ec)
		var promises = new mutable.Queue[Promise[Unit]]()

		def awaitAll[A](futures: List[Future[A]], seconds:Int = 10) =
			Await.result(Future.sequence(futures), seconds.seconds)

		def queuedRunLoops = {
			ec.queue.filter(_ == ex.workLoop)
		}

		private def doInc(sleep: Int) = {
			assert(count.busy == false)
			val initial = count.current
			count.busy = true
			Thread.sleep(sleep)
			assert(count.busy == true)
			count.current = initial + 1
			// println(s"incremented count from $initial to ${count.current}")
			count.busy = false
			count.current
		}

		def inc(sleep: Int = 10): UnitOfWork.Full[Int] = {
			UnitOfWork.Full(() => doInc(sleep))
		}

		def incAsync(sleep: Int = 10): UnitOfWork.FullAsync[Int] = {
			val promise = Promise[Unit]()
			promises.enqueue(promise)
			UnitOfWork.FullAsync(() => {
				val current = doInc(sleep)
				promise.future.map(_ => current)
			})
		}

		def noop(): UnitOfWork.Full[Unit] = {
			UnitOfWork.Full(() => ())
		}

		def noopAsync(sleep: Int = 10): UnitOfWork.FullAsync[Unit] = {
			val promise = Promise[Unit]()
			promises.enqueue(promise)
			UnitOfWork.FullAsync(() => {
				promise.future
			})
		}
	}

	object Ctx {
		private var threadPool:Option[ExecutorService] = None

		def withManualExecution(bufLen: Int) = new Ctx(bufLen, manualExecutionContext)
		def withThreadPool(bufLen: Int) = new Ctx(bufLen, threadpoolExecutionContext)

		def manualExecutionContext = new ManualExecutionContext
		def threadpoolExecutionContext = {
			new CountingExecutionContext(ExecutionContext.fromExecutor(threadPool.get))
		}

		def getThreadPool = threadPool.getOrElse(
			throw new RuntimeException("Ctx.init() not called")
		)

		def init() = {
			if (threadPool.isDefined) {
				throw new RuntimeException("Ctx.init() called twice")
			}
			threadPool = Some(Executors.newFixedThreadPool(3))
		}

		def shutdown() = {
			threadPool.foreach(_.shutdown())
		}

		def killAndReset() = {
			threadPool.foreach(_.shutdownNow())
			threadPool = None
			init()
		}

		class Count {
			var current = 0
			var busy = false
			override def toString() = "Count("+current+","+busy+")"
		}
	}
}

class SequentialExecutorSpec extends FunSpec with BeforeAndAfterAll with TimeLimitedTests
{
	import SequentialExecutorSpec._
	override def beforeAll = Ctx.init()
	override def afterAll = Ctx.shutdown()

	val timeLimit = 10.seconds
	var logsDumped = false

	private def dumpLogs(dumper: Function2[String,Option[Any],Unit]) {
		if(!logsDumped) {
			logsDumped = true
			Log.dumpTo(100, lines => lines.foreach(dumper(_, None)))
		}
	}

	override val defaultTestSignaler = new Signaler {
		def apply(testThread: Thread) {
			alert("--- Interrupted ---")
			dumpLogs(alert.apply)
			Ctx.killAndReset()
			ThreadSignaler(testThread)
		}
	}

	override def withFixture(test: NoArgTest): Outcome = {
		logsDumped = false
		Log.clear()
		val result = super.withFixture(test)
		result match {
			case Failed(_) | Canceled(_) => {
				dumpLogs(info.apply)
			}
			case other => ()
		}
		result
	}

	describe("synchronous tasks") {

		it("delays job enqueue once capacity is reached") {
			val ctx = Ctx.withManualExecution(3); import ctx._
			val futures = List.fill(4)(ex.enqueue(inc()))
			assert(futures.map(_.isAccepted) == List(true, true, true, false))
		}

		it("enqueues waiting jobs upon task completion") {
			val ctx = Ctx.withManualExecution(3); import ctx._
			val futures = List.fill(4)(ex.enqueue(inc()))

			assert(futures.map(_.isAccepted) == List(true, true, true, false))

			assert(ec.queue.length == 1)
			ec.queue(0).run()

			assert(futures.map(_.isAccepted) == List(true, true, true, true))
		}

		it("runs queued jobs in a single execution") {
			val ctx = Ctx.withManualExecution(3); import ctx._
			val futures = List.fill(3)(ex.enqueue(inc()))
			assert(futures.map(_.isAccepted) == List(true, true, true))
			assert(futures.map(_.isCompleted) == List(false, false, false))

			ec.queue.head.run()

			assert(futures.map(_.isCompleted) == List(true, true, true))
			assert(futures.map(_.value.get.get) == List(1, 2, 3))
			assert(ec.queue.length == 1)
		}

		it("executes all jobs in sequence") {
			val ctx = Ctx.withThreadPool(3); import ctx._

			// big sleep ensures that if we're not running in sequence,
			// we'll encounter race conditions due to `inc()` not being thread-safe
			awaitAll(List.fill(4)(ex.enqueue(inc(sleep=50))))

			assert(queuedRunLoops.length == 1)
			assert(count.current == 4)
		}

		it("executes up to 1000 jobs in a single loop") {
			val ctx = Ctx.withThreadPool(bufLen = 50); import ctx._

			awaitAll(List.fill(1000)(ex.enqueue(inc(sleep=1))))
			assert(queuedRunLoops.length == 1)
			assert(count.current == 1000)
		}

		it("defers jobs into a new loop after 1000 (rounded to batch size) to prevent starvation") {
			val ctx = Ctx.withThreadPool(bufLen = 50); import ctx._

			awaitAll(List.fill(1050)(ex.enqueue(inc(sleep=1))))
			assert(queuedRunLoops.length == 2)
			assert(count.current == 1050)
		}
	}

	describe("async tasks") {
		it("counts incomplete async tasks as taking a queue slot") {
			val ctx = Ctx.withManualExecution(2); import ctx._
			var futures = List.fill(2)(ex.enqueue(incAsync()))
			assert(futures.map(_.isAccepted) == List(true, true))
			ec.runOne()
			futures = futures ++ List.fill(2)(ex.enqueue(incAsync()))
			assert(futures.map(_.isAccepted) == List(true, true, false, false))
		}

		it("does not wait for an async task's completion before executing the next task") {
			val ctx = Ctx.withManualExecution(2); import ctx._
			val futures = List.fill(3)(ex.enqueue(incAsync()))
			assert(futures.map(_.isAccepted) == List(true, true, false))
			ec.runOne()
			assert(count.current == 2)
		}

		it("maintains unfinished tasks after workloop ends on an async task") {
			val ctx = Ctx.withManualExecution(2); import ctx._
			var futures = List.fill(1)(ex.enqueue(incAsync()))
			ec.runOne()
			futures ++= List.fill(2)(ex.enqueue(incAsync()))
			ec.runOne()
			assert(futures.map(_.isAccepted) == List(true, true, false))
		}

		it("maintains unfinished tasks after workloop ends on a sync task") {
			val ctx = Ctx.withManualExecution(2); import ctx._
			var futures = List(
				ex.enqueue(incAsync()),
				ex.enqueue(inc())
			)
			ec.runOne()
			futures ++= List.fill(2)(ex.enqueue(incAsync()))
			ec.runUntilEmpty()
			assert(group(futures.map(_.isAccepted)) == List(true -> 3, false -> 1))
		}

		it("resumes execution after being blocked on async tasks") {
			val ctx = Ctx.withManualExecution(3); import ctx._
			val futures = List.fill(6)(ex.enqueue(incAsync()))
			assert(group(futures.take(4).map(_.isAccepted)) == List(true -> 3, false -> 1))
			ec.runOne()
			// ex still blocked because it has three outstanding futures
			assert(group(futures.take(4).map(_.isAccepted)) == List(true -> 3, false -> 1))

			promises.take(2).foreach(_.success(()))
			ec.runUntilEmpty()

			// on completion of (n) items, it should accept (n) new items
			assert(group(futures.map(_.isAccepted)) == List(true -> 5, false -> 1))
		}

		it("prunes complete async tasks after a sync task") {
			val ctx = Ctx.withManualExecution(2); import ctx._
			val futures = List(
				ex.enqueue(noopAsync()),
				ex.enqueue(UnitOfWork.Full(() => promises.head.success(())))
			) ++ List.fill(3)(ex.enqueue(incAsync()))
			assert(group(futures.map(_.isAccepted)) == List(true -> 2, false -> 3))
			ec.runUntilEmpty()
			assert(group(futures.map(_.isAccepted)) == List(true -> 4, false -> 1))
		}
	}
}
