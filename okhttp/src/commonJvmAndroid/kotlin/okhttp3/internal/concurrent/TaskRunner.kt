/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.concurrent

import java.util.concurrent.BlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import okhttp3.internal.addIfAbsent
import okhttp3.internal.concurrent.TaskRunner.Companion.INSTANCE
import okhttp3.internal.okHttpName
import okhttp3.internal.threadFactory

/**
 * A set of worker threads that are shared among a set of task queues.
 *
 * Use [INSTANCE] for a task runner that uses daemon threads. There is not currently a shared
 * instance for non-daemon threads.
 *
 * The task runner is also responsible for releasing held threads when the library is unloaded.
 * This is for the benefit of container environments that implement code unloading.
 *
 * Most applications should share a process-wide [TaskRunner] and use queues for per-client work.
 */
class TaskRunner(
  val backend: Backend,
  internal val logger: Logger = TaskRunner.logger,
) : Lockable {
  private var nextQueueName = 10000
  private var coordinatorWaiting = false
  private var coordinatorWakeUpAt = 0L

  /**
   * When we need a new thread to run tasks, we call [Backend.execute]. A few microseconds later we
   * expect a newly-started thread to call [Runnable.run]. We shouldn't request new threads until
   * the already-requested ones are in service, otherwise we might create more threads than we need.
   *
   * We use [executeCallCount] and [runCallCount] to defend against starting more threads than we
   * need. Both fields are guarded by `this`.
   */
  private var executeCallCount = 0
  private var runCallCount = 0

  /** Queues with tasks that are currently executing their [TaskQueue.activeTask]. */
  private val busyQueues = mutableListOf<TaskQueue>()

  /** Queues not in [busyQueues] that have non-empty [TaskQueue.futureTasks]. */
  private val readyQueues = mutableListOf<TaskQueue>()

  private val runnable: Runnable =
    object : Runnable {
      override fun run() {
        var task: Task =
          withLock {
            runCallCount++
            awaitTaskToRun()
          } ?: return

        val currentThread = Thread.currentThread()
        val oldName = currentThread.name
        try {
          while (true) {
            currentThread.name = task.name
            val delayNanos =
              logger.logElapsed(task, task.queue!!) {
                task.runOnce()
              }

            // A task ran successfully. Update the execution state and take the next task.
            task = withLock {
              afterRun(task, delayNanos, true)
              awaitTaskToRun()
            } ?: return
          }
        } catch (thrown: Throwable) {
          // A task failed. Update execution state and re-throw the exception.
          withLock {
            afterRun(task, -1L, false)
          }
          if (thrown is InterruptedException) {
            Thread.currentThread().interrupt()
          } else {
            throw thrown
          }
        } finally {
          currentThread.name = oldName
        }
      }
    }

  internal fun kickCoordinator(taskQueue: TaskQueue) {
    assertLockHeld()

    if (taskQueue.activeTask == null) {
      if (taskQueue.futureTasks.isNotEmpty()) {
        readyQueues.addIfAbsent(taskQueue)
      } else {
        readyQueues.remove(taskQueue)
      }
    }

    if (coordinatorWaiting) {
      backend.coordinatorNotify(this@TaskRunner)
    } else {
      startAnotherThread()
    }
  }

  private fun beforeRun(task: Task) {
    assertLockHeld()

    task.nextExecuteNanoTime = -1L
    val queue = task.queue!!
    queue.futureTasks.remove(task)
    readyQueues.remove(queue)
    queue.activeTask = task
    busyQueues.add(queue)
  }

  private fun afterRun(
    task: Task,
    delayNanos: Long,
    completedNormally: Boolean,
  ) {
    assertLockHeld()

    val queue = task.queue!!
    check(queue.activeTask === task)

    val cancelActiveTask = queue.cancelActiveTask
    queue.cancelActiveTask = false
    queue.activeTask = null
    busyQueues.remove(queue)

    if (delayNanos != -1L && !cancelActiveTask && !queue.shutdown) {
      queue.scheduleAndDecide(task, delayNanos, recurrence = true)
    }

    if (queue.futureTasks.isNotEmpty()) {
      readyQueues.add(queue)

      // If the task crashed, start another thread to run the next task.
      if (!completedNormally) {
        startAnotherThread()
      }
    }
  }

  /**
   * Returns an immediately-executable task for the calling thread to execute, sleeping as necessary
   * until one is ready. If there are no ready queues, or if other threads have everything under
   * control this will return null. If there is more than a single task ready to execute immediately
   * this will start another thread to handle that work.
   */
  fun awaitTaskToRun(): Task? {
    assertLockHeld()

    while (true) {
      if (readyQueues.isEmpty()) {
        return null // Nothing to do.
      }

      val now = backend.nanoTime()
      var minDelayNanos = Long.MAX_VALUE
      var readyTask: Task? = null
      var multipleReadyTasks = false

      // Decide what to run. This loop's goal wants to:
      //  * Find out what this thread should do (either run a task or sleep)
      //  * Find out if there's enough work to start another thread.
      eachQueue@ for (queue in readyQueues) {
        val candidate = queue.futureTasks[0]
        val candidateDelay = maxOf(0L, candidate.nextExecuteNanoTime - now)

        when {
          // Compute the delay of the soonest-executable task.
          candidateDelay > 0L -> {
            minDelayNanos = minOf(candidateDelay, minDelayNanos)
            continue@eachQueue
          }

          // If we already have more than one task, that's enough work for now. Stop searching.
          readyTask != null -> {
            multipleReadyTasks = true
            break@eachQueue
          }

          // We have a task to execute when we complete the loop.
          else -> {
            readyTask = candidate
          }
        }
      }

      // Implement the decision.
      when {
        // We have a task ready to go. Get ready.
        readyTask != null -> {
          beforeRun(readyTask)

          // Also start another thread if there's more work or scheduling to do.
          if (multipleReadyTasks || !coordinatorWaiting && readyQueues.isNotEmpty()) {
            startAnotherThread()
          }

          return readyTask
        }

        // Notify the coordinator of a task that's coming up soon.
        coordinatorWaiting -> {
          if (minDelayNanos < coordinatorWakeUpAt - now) {
            backend.coordinatorNotify(this@TaskRunner)
          }
          return null
        }

        // No other thread is coordinating. Become the coordinator!
        else -> {
          coordinatorWaiting = true
          coordinatorWakeUpAt = now + minDelayNanos
          try {
            backend.coordinatorWait(this@TaskRunner, minDelayNanos)
          } catch (_: InterruptedException) {
            // Will cause all tasks to exit unless more are scheduled!
            cancelAll()
          } finally {
            coordinatorWaiting = false
          }
        }
      }
    }
  }

  /** Start another thread, unless a new thread is already scheduled to start. */
  private fun startAnotherThread() {
    assertLockHeld()
    if (executeCallCount > runCallCount) return // A thread is still starting.

    executeCallCount++
    try {
      backend.execute(this@TaskRunner, runnable)
    } catch (e: Throwable) {
      // When thread creation fails, an Error will be thrown, and the counter must be properly released at this time.
      executeCallCount--
      throw e
    }
  }

  fun newQueue(): TaskQueue {
    val name = this.withLock { nextQueueName++ }
    return TaskQueue(this, "Q$name")
  }

  /**
   * Returns a snapshot of queues that currently have tasks scheduled. The task runner does not
   * necessarily track queues that have no tasks scheduled.
   */
  fun activeQueues(): List<TaskQueue> {
    this.withLock {
      return busyQueues + readyQueues
    }
  }

  fun cancelAll() {
    assertLockHeld()
    for (i in busyQueues.size - 1 downTo 0) {
      busyQueues[i].cancelAllAndDecide()
    }
    for (i in readyQueues.size - 1 downTo 0) {
      val queue = readyQueues[i]
      queue.cancelAllAndDecide()
      if (queue.futureTasks.isEmpty()) {
        readyQueues.removeAt(i)
      }
    }
  }

  interface Backend {
    fun nanoTime(): Long

    fun coordinatorNotify(taskRunner: TaskRunner)

    fun coordinatorWait(
      taskRunner: TaskRunner,
      nanos: Long,
    )

    fun <T> decorate(queue: BlockingQueue<T>): BlockingQueue<T>

    fun execute(
      taskRunner: TaskRunner,
      runnable: Runnable,
    )
  }

  class RealBackend(
    threadFactory: ThreadFactory,
  ) : Backend {
    val executor =
      ThreadPoolExecutor(
        // corePoolSize:
        0,
        // maximumPoolSize:
        Int.MAX_VALUE,
        // keepAliveTime:
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        threadFactory,
      )

    override fun nanoTime() = System.nanoTime()

    override fun coordinatorNotify(taskRunner: TaskRunner) {
      taskRunner.notify()
    }

    /**
     * Wait a duration in nanoseconds. Unlike [java.lang.Object.wait] this interprets 0 as
     * "don't wait" instead of "wait forever".
     */
    @Throws(InterruptedException::class)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    override fun coordinatorWait(
      taskRunner: TaskRunner,
      nanos: Long,
    ) {
      taskRunner.assertLockHeld()
      if (nanos > 0) {
        taskRunner.awaitNanos(nanos)
      }
    }

    override fun <T> decorate(queue: BlockingQueue<T>) = queue

    override fun execute(
      taskRunner: TaskRunner,
      runnable: Runnable,
    ) {
      executor.execute(runnable)
    }

    fun shutdown() {
      executor.shutdown()
    }
  }

  companion object {
    val logger: Logger = Logger.getLogger(TaskRunner::class.java.name)

    @JvmField
    val INSTANCE = TaskRunner(RealBackend(threadFactory("$okHttpName TaskRunner", daemon = true)))
  }
}
