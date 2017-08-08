package net.gfxmonk.sequentialstate

import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec

import monix.execution.atomic.Atomic

import scala.concurrent.{ExecutionContext, Future}

object SequentialExecutor {
	val defaultBufferSize = 10
	def apply(bufLen: Int = defaultBufferSize)(implicit ec: ExecutionContext) = new SequentialExecutor(bufLen)
	private val successfulUnit = Future.successful(())
}

private final class Node[A](val item: A) {
	@volatile var next: Node[A] = null
	final def appearsAfter[A](parent: Node[A]): Boolean = {
		var node = parent.next
		while(node != null) {
			if (node == this) {
				return true
			}
			node = node.next
		}
		false
	}
}

private final class Queued[A](val len: Int, val node: Node[A]) {
	def add(newNode: Node[A]) = new Queued(len+1, newNode)
}
private object Queued {
	def single[A](node: Node[A]) = new Queued(1, node)
	def empty[A](node: Node[A]) = new Queued(0, node)
}

class SequentialExecutor(bufLen: Int)(implicit ec: ExecutionContext) {
	private val nullNode: Node[EnqueueableTask] = null
	private val head = Atomic(nullNode)
	private val queued = Atomic(Queued.empty(nullNode))
	private val tail = Atomic(nullNode)

	private def enqueue(work: EnqueueableTask):Boolean = {
		var currentTail = tail.get
		val newTail = new Node(work)

		// may race with other enqueuer threads
		var printlnTailCount = 1
		while(!tail.compareAndSet(currentTail, newTail)) {
			printlnTailCount += 1
			if(printlnTailCount > 5) println("printlnTailCount: " + printlnTailCount)
			currentTail = tail.get
		}

		if (currentTail == null) {
			// we're the first item! runloop is definitely not running
			queued.set(new Queued(workLoop.numInProgress + 1, newTail))
			head.set(newTail)
			ec.execute(workLoop)
			true
		} else {
			// nodes must be present in the linked list before adding them to `queued`
			// (also we want the consumer to see new tasks ASAP)
			currentTail.next = newTail

			// now update `queued`
			var printlnQueueCount = 0
			while(true) {
				printlnQueueCount += 1
				if(printlnQueueCount > 5) println("..ongoing queue attempts: " + printlnQueueCount)
				val currentQueued = queued.get
				if (currentQueued.len == bufLen) {
					// we're at capacity - only the consumer can advance queued
					// (and it already knows about our new node because we've put it in tail)
					return false
				} else {
					if (currentQueued.node.eq(currentTail)) {
						// common case: we added a node, and now we can (try to) enqueue it
						if (queued.compareAndSet(currentQueued, currentQueued.add(newTail))) {
							// println("queued is now " + (currentQueued.len+1))
							// if(printlnTailCount > 1 || printlnQueueCount > 1) println("tail attempts: " + printlnTailCount + ", queued attempts = " + printlnQueueCount)
							return true
						} // else try again
					} else if (currentQueued.node.eq(newTail)) {
						// someone has already enqueued us
						return true
					} else {
						if (newTail.appearsAfter(currentQueued.node)) {
							// We need `queued` to advance until either we reach buflen or the
							// task we added, and any thread knows enough to do that. Give it a go,
							// but don't retry if someone beats us to it.
							val _:Boolean = queued.compareAndSet(currentQueued, currentQueued.add(currentQueued.node.next))
						} else {
							// OR, there are two possibilities:
							//  - queued has advanced past newTail
							//  - the entire stack that we were added to has been completed,
							//    and queued is now a new stack
							// Either way, we were certainly enqueued.
							// println("enqueue(): queued advanced past us; skipping")
							return true
						}
					}
				}
			}

			assert(false); false // unreachable
		}
	}

	trait WorkLoop extends Runnable {
		def numInProgress: Int
	}

	val workLoop: WorkLoop = new WorkLoop() {
		@volatile private var storedInProgress: List[Future[_]] = Nil

		final def numInProgress = storedInProgress.length

		def run(): Unit = {
			val headNode = head.get
			val inProgress = storedInProgress
			storedInProgress = Nil
			runNode(headNode, 200, headNode, inProgress)
		}

		private final def runNode(
			headNode: Node[EnqueueableTask],
			numIterations: Int,
			node: Node[EnqueueableTask],
			inProgress: List[Future[_]]) =
		{
			runNodeRec(headNode, numIterations, node, inProgress)
		}

		private def supendWithInProgress(
			headNode: Node[EnqueueableTask],
			completedNode: Node[EnqueueableTask],
			inProgress: List[Future[_]]
		): Unit = {
			Future.firstCompletedOf[Any](inProgress).onComplete { _ =>
				inProgress.partition(_.isCompleted) match {
					case (Nil, inProgress) => assert(false)
					case (completed, inProgress) => {
						advanceQueued(completed.length)
						val headNode = head.get
						val nextNode = advanceNode(headNode, completedNode, inProgress)
						if (nextNode != null) {
							runNodeRec(headNode, 200, nextNode, inProgress)
						}
					}
				}
			}
		}

		@tailrec private final def runNodeRec(
			headNode: Node[EnqueueableTask],
			numIterations: Int,
			node: Node[EnqueueableTask],
			inProgress: List[Future[_]]
		) {
			if (numIterations > 0) {
				val asyncCompletion = node.item.run()
				asyncCompletion match {
					case None => {
						val next = advanceNode(headNode, node, inProgress)
						if (next != null) {
							advanceQueued()
							runNodeRec(headNode, numIterations - 1, next, inProgress)
						}
					}
					case Some(f) => {
						// try completing some async inProgress items:
						tryCompleteNodesAsync(f :: inProgress) match {
							case None => {
								// nothing ready
								supendWithInProgress(headNode, node, inProgress)
							}
							case Some(inProgress) => {
								// we can continue immediately with these remaining inProgress items
								// (some were already done, or we're not at capacity)
								val next = advanceNode(headNode, node, inProgress)
								if (next != null) {
									runNodeRec(headNode, numIterations - 1, next, inProgress)
								}
							}
						}
					}
				}
			} else {
				// we ran 200 iterations. Make sure we don't hog the thread
				storedInProgress = inProgress
				if (!head.compareAndSet(headNode, node)) {
					throw new IllegalStateException("head modified by external thread")
				}
				ec.execute(workLoop)
			}
		}

		// returns
		// None => cannot continue; at capacity
		// Some(list) => new inProgress list (with complete items removed)
		private final def tryCompleteNodesAsync(
			inProgress: List[Future[_]]): Option[List[Future[_]]] =
		{
			while (true) {
				val currentQueued = queued.get
				if (currentQueued.len == bufLen) {
					// we're at capacity, only this thread can advance it
					return inProgress.partition(_.isCompleted) match {
						// Are some of these items already done?
						case (Nil, inProgress) => {
							// nope
							None
						}
						case (completed, inProgress) => {
							advanceQueued(completed.length)
							Some(inProgress)
						}
					}
				} else {
					// We're not at capacity. We can just leave inProgress items and
					// accumulate some stuff.
					if (queued.compareAndSet(currentQueued, currentQueued)) {
						return Some(inProgress)
					}
				}
			}
			assert(false); None
		}

		private final def advanceNode(
			headNode: Node[EnqueueableTask],
			node: Node[EnqueueableTask],
			inProgress: List[Future[_]]): Node[EnqueueableTask] =
		{
			var printlnNullifyTailAttempts = 0
			while (node.next == null) {
				printlnNullifyTailAttempts += 1
				if(printlnNullifyTailAttempts>5) println("tail nullify attempts: " + printlnNullifyTailAttempts)
				// looks like we've hit the tail.
				storedInProgress = inProgress
				if (tail.compareAndSet(node, null)) {
					// yeah, that was the last item.
					head.compareAndSet(headNode, null) // best effort; helps with GC
					return null
				} else {
					// println("spin() node.next")
					// `node` isn't really the tail, so we just need to spin, waiting for
					// its `.next` property to be set
					// Thread.`yield`()
					LockSupport.parkNanos(100)
				}
			}
			node.next
		}

		private final def advanceQueued(n: Int): Unit =
		{
			// n is always >0
			var printlnUpdatedCount = 0
			@tailrec def tryUpdate(numCompleted: Int): Unit = {
				printlnUpdatedCount += 1
				if (printlnUpdatedCount > 5) println("runner queued update count: " + printlnUpdatedCount)
				val currentQueued = queued.get
				if (currentQueued.len == bufLen) {
					// we're at capacity, only this thread can advance it (just use `set`)
					val queuedNode = currentQueued.node
					val nextQueued = queuedNode.next
					if (nextQueued == null) {
						queued.set(new Queued(bufLen - numCompleted, queuedNode))
					} else {
						queued.set(new Queued(bufLen, nextQueued))
						// notify the lucky winner
						nextQueued.item.enqueuedAsync()
						val completedRemaining = numCompleted - 1
						if (completedRemaining > 0) {
							tryUpdate(completedRemaining)
						}
					}
				} else {
					// We're not at capacity. If threads enqueue more tasks in the meantime
					// this CAS will fail and we'll loop again
					// println("queued = " + currentQueued)
					if (!queued.compareAndSet(currentQueued, currentQueued)) {
						tryUpdate(numCompleted)
					}
				}
			}
			tryUpdate(n)
		}

		private def advanceQueued(): Unit = advanceQueued(1)
	}

	def enqueueOnly[R](task: EnqueueableTask with UnitOfWork.HasEnqueuePromise[Unit]): Future[Unit] = {
		if (enqueue(task)) {
			SequentialExecutor.successfulUnit
		} else {
			task.enqueuedPromise.future
		}
	}

	def enqueueReturn[R](task: EnqueueableTask with UnitOfWork.HasResultPromise[R]): Future[R] = {
		enqueue(task)
		task.resultPromise.future
	}

	def enqueueRaw[R](
		task: EnqueueableTask
			with UnitOfWork.HasEnqueuePromise[Future[R]]
			with UnitOfWork.HasResultPromise[R]
	): StagedFuture[R] = {
		if (enqueue(task)) {
			StagedFuture.accepted(task.resultPromise.future)
		} else {
			StagedFuture(task.enqueuedPromise.future)
		}
	}
}