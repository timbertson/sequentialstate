package net.gfxmonk.capsul.internal

import java.util.concurrent.ConcurrentHashMap
import scala.util.Sorting
import scala.collection._
import scala.collection.immutable.Queue
import scala.collection.JavaConverters._
import scala.language.experimental.macros
import scala.annotation.tailrec

// efficient in-memory threadsafe log collection, used
// for debugging issues (SequentialExecutor is augmented
// with log calls which have zero cost when disabled)

private [capsul] class LogCtx(id: String, val buf: Log.LogBuffer) {
	val prefix = s"[$id]: "
}

object Log {
	// val ENABLE = true; type Ctx = LogCtx
	val ENABLE = false; type Ctx = Unit

	val MAX_LOG_LINES = 1000
	type LogEntry = (Long,String)
	type ThreadLogEntry = (Long,LogEntry)
	class MutableRef[T](val generation: Int, initialValue: T) {
		private var item: T = initialValue
		def set(t: T) { item = t }
		def get: T = { item }
	}
	type LogBuffer = MutableRef[Queue[LogEntry]]

	@volatile private var generation: Int = 0
	private lazy val threads = {
		if (!ENABLE) {
			throw new RuntimeException(
				"Log module has not been enabled")
		}
		println("\n\n ** WARNING ** Log is enabled; this should only be used for debugging\n\n")
		Thread.sleep(500)
		new ConcurrentHashMap[Long, LogBuffer]()
	}

	def clear() {
		ifEnabled {
			threads.synchronized {
				generation += 1
				threads.clear()
			}
		}
	}

	private val threadLocal = ThreadLocal.withInitial[Option[MutableRef[Queue[LogEntry]]]](() => None)

	def threadBuffer = {
		val currentGeneration = generation
		threadLocal.get.filter(_.generation == currentGeneration).getOrElse {
			val id = Thread.currentThread().getId()
			val buf = Option(threads.get(id)) match {
				case None => {
					val buf: LogBuffer = new MutableRef(currentGeneration, Queue[LogEntry]())
					threads.putIfAbsent(id, buf)
					threads.get(id)
				}
				case Some(buf) => buf
			}
			threadLocal.set(Some(buf))
			buf
		}
	}

	var nextId: Int = 0

	def scope(desc: String): Ctx = macro Macros.scopeImpl1
	def scope(obj: Any, desc: String): Ctx = macro Macros.scopeImpl2
	def log(msg: String) = macro Macros.logImpl

	private def ifEnabled(x: => Unit) {
		if (ENABLE) x
	}

	def apply(msg: String): Unit = macro Macros.applyImpl


	def dump() {
		ifEnabled {
			_dumpTo(None, None)
		}
	}

	def dump(n: Int) {
		ifEnabled {
			_dumpTo(Some(n), None)
		}
	}

	def dumpTo(n: Int, printer: Function[Seq[String],Unit]) {
		ifEnabled {
			_dumpTo(Some(n), Some(printer))
		}
	}

	def dumpTo(printer: Function[Seq[String],Unit]) {
		ifEnabled {
			_dumpTo(None, Some(printer))
		}
	}

	def _dumpTo(n: Option[Int], printerOpt: Option[Function[Seq[String],Unit]]) {
		val printer: Function[Seq[String],Unit] = printerOpt.getOrElse(lines => println(lines.mkString("\n")))

		// XXX this is racey, it will fail sometimes due to concurrently accessing mutable structures :(
		def extractLogs(): List[String] = {
			try {
				val buffers = threads.entrySet().asScala.map { case entry =>
					val tid = entry.getKey
					var logs = entry.getValue.get
					if (logs.size == MAX_LOG_LINES) {
						// insert a marker so we can tell that there may have been clipping
						val ts = logs.head._1
						logs = (ts, s"** Thread $tid logs begin **") +: logs
					}
					logs.map(log => (tid,log))
				}
				val merged = buffers.foldLeft(Array[ThreadLogEntry]()) { (acc, lst) =>
					// sort by timestamp
					Sorting.stableSort(acc ++ lst, { (a:ThreadLogEntry, b: ThreadLogEntry) =>
						(a,b) match {
							case ((_tida, (tsa, _msga)), (_tidb, (tsb, _msgb))) => {
								tsa < tsb
							}
						}
					})
				}
				var lines = n match {
					case Some(n) => merged.reverse.take(n).reverse
					case None => merged
				}
				if (lines.isEmpty) {
					Nil
				} else {
					val minTimestamp = lines.head._2._1
					lines.toList.map { case (tid,(time, msg)) =>
						val timeSecs = ((time - minTimestamp).toFloat / 1000000000) // nanos -> relative seconds
						f"-- ${timeSecs.formatted("%-2.9f")}|$tid: $msg"
					}
				}
			} catch {
				case e:Exception => {
					return List(s"*** Error extracting logs: $e")
				}
			}
		}

		val logs = extractLogs()
		val header = s"== Printing ${n match { case None => "all available"; case Some(n) => s"up to $n" }} log lines =="
		printer(header :: logs)
	}


	object Macros {
		import scala.reflect.macros.blackbox

		def doIfEnabled(c:blackbox.Context)(expr: c.Expr[Unit]):c.Expr[Unit] = {
			import c.universe._
			if (Log.ENABLE) expr else reify { }
		}

		def returnIfEnabled(c:blackbox.Context)(expr: c.Expr[Log.Ctx]):c.Expr[Log.Ctx] = {
			import c.universe._
			if (Log.ENABLE) expr else reify { ().asInstanceOf[Log.Ctx] }
		}

		def applyImpl(c:blackbox.Context)(msg:c.Expr[String]):c.Expr[Unit] = {
			import c.universe._
			doIfEnabled(c) { c.Expr(q"Log.Macros.logMsg($msg)") }
		}

		def scopeImpl1(c:blackbox.Context)(desc:c.Expr[String]):c.Expr[Log.Ctx] = {
			import c.universe._
			returnIfEnabled(c) { c.Expr(q"Log.Macros.makeId($desc)") }
		}

		def scopeImpl2(c:blackbox.Context)(obj:c.Expr[Any], desc:c.Expr[String]):c.Expr[Log.Ctx] = {
			import c.universe._
			returnIfEnabled(c) { c.Expr(q"Log.Macros.makeId($obj, $desc)") }
		}

		def logImpl(c:blackbox.Context)(msg:c.Expr[String]):c.Expr[Unit] = {
			import c.universe._
			// Can only be called with a `__logCtx` in scope
			doIfEnabled(c) { c.Expr(q"Log.Macros.logWithCtx(logId, $msg)") }
		}

		def logMsg(s: String) {
			// anonymous, global log
			doLog(threadBuffer, s)
		}

		def logWithCtx(ctx: LogCtx, s: String) {
			// thread-local tagged log
			doLog(ctx.buf, ctx.prefix+s)
		}

		def doLog(buf: LogBuffer, s: String) {
			val time = System.nanoTime()
			var queue = buf.get.enqueue(time -> s)
			if (queue.length > MAX_LOG_LINES) {
				queue = queue.dequeue._2
			}
			buf.set(queue)
		}

		def makeId(obj: Any, desc: String) = {
			nextId += 1
			val id = if (obj == null) nextId else s"${System.identityHashCode(obj).toHexString}.$nextId"
			new LogCtx(s"$id/$desc", Log.threadBuffer)
		}

		def makeId(desc: String) = {
			nextId += 1
			new LogCtx(s"$desc.$nextId", Log.threadBuffer)
		}

	}
}
