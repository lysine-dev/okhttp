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

@file:Suppress(
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
)
@file:OptIn(ExperimentalTime::class)

package okhttp3.internal.concurrent

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.Closeable
import java.util.AbstractQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import okhttp3.TestUtil.threadFactory

/**
 * Runs a [TaskRunner] in a controlled environment so that everything is sequential and
 * deterministic.
 *
 * This class ensures that at most one thread is running at a time. This is initially the JUnit test
 * thread, which yields its execution privilege while calling [runTasks], [runNextTask], or
 * [advanceUntil]. These functions don't return until the task threads are all idle.
 *
 * Task threads release their execution privilege in these ways:
 *
 *  * By yielding in [TaskRunner.Backend.coordinatorWait].
 *  * By yielding in [BlockingQueue.poll].
 *  * By completing.
 */
class TaskFaker : Closeable {
  val logger = Logger.getLogger("TaskFaker." + instance++)

  /** Though this executor service may hold many threads, they are not executed concurrently. */
  private val tasksExecutor = Executors.newCachedThreadPool(threadFactory("TaskFaker"))

  /**
   * True if this task faker has ever had multiple tasks scheduled to run concurrently. Guarded by
   * `this`.
   */
  var isParallel = false

  /** Number of calls to [TaskRunner.Backend.execute]. Guarded by `this`. */
  var executeCallCount = 0

  /** Guarded by [taskRunner]. */
  var nanoTime = 0L
    private set

  /** Backlog of tasks to run. Only one task runs at a time. Guarded by `this`. */
  private val serialTaskQueue = ArrayDeque<SerialTask>()

  /** The task that's currently executing. Guarded by `this`. */
  private var currentTask: SerialTask = TestThreadSerialTask

  /** The coordinator task if it's waiting, and how it will resume. Guarded by `this`. */
  private var waitingCoordinatorTask: SerialTask? = null
  private var waitingCoordinatorInterrupted = false
  private var waitingCoordinatorNotified = false

  /** How many times a new task has been started. Guarded by `this`. */
  private var contextSwitchCount = 0

  /** Guarded by `this`. */
  private var activeThreads = 0

  /** Adapt this API to Kotlin's time API. */
  val timeSource =
    object : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
      override fun read() = nanoTime
    }

  /** A task runner that posts tasks to this fake. Tasks won't be executed until requested. */
  val taskRunner: TaskRunner =
    TaskRunner(
      object : TaskRunner.Backend {
        override fun execute(
          taskRunner: TaskRunner,
          runnable: Runnable,
        ) {
          taskRunner.assertLockHeld()

          val queuedTask = RunnableSerialTask(runnable)
          serialTaskQueue += queuedTask
          executeCallCount++
          isParallel = serialTaskQueue.size > 1
        }

        override fun nanoTime() = nanoTime

        override fun coordinatorNotify(taskRunner: TaskRunner) {
          taskRunner.assertLockHeld()
          check(waitingCoordinatorTask != null)

          // Queue a task to resume the waiting coordinator.
          serialTaskQueue +=
            object : SerialTask {
              override fun start() {
                taskRunner.assertLockHeld()
                val coordinatorTask = waitingCoordinatorTask
                if (coordinatorTask != null) {
                  waitingCoordinatorNotified = true
                  currentTask = coordinatorTask
                  taskRunner.notifyAll()
                } else {
                  startNextTask()
                }
              }
            }
        }

        override fun coordinatorWait(
          taskRunner: TaskRunner,
          nanos: Long,
        ) {
          taskRunner.assertLockHeld()
          check(waitingCoordinatorTask == null)
          if (nanos == 0L) return

          // Yield until notified, interrupted, or the duration elapses.
          val waitUntil = nanoTime + nanos
          val self = currentTask
          waitingCoordinatorTask = self
          waitingCoordinatorNotified = false
          waitingCoordinatorInterrupted = false
          yieldUntil {
            waitingCoordinatorNotified || waitingCoordinatorInterrupted || nanoTime >= waitUntil
          }

          waitingCoordinatorTask = null
          waitingCoordinatorNotified = false
          if (waitingCoordinatorInterrupted) {
            waitingCoordinatorInterrupted = false
            throw InterruptedException()
          }
        }

        override fun <T> decorate(queue: BlockingQueue<T>) = TaskFakerBlockingQueue(queue)
      },
      logger = logger,
    )

  /** Runs all tasks that are ready. Used by the test thread only. */
  fun runTasks() {
    advanceUntil(nanoTime)
  }

  /** Advance the simulated clock, then runs tasks that are ready. Used by the test thread only. */
  fun advanceUntil(newTime: Long) {
    taskRunner.assertLockNotHeld()

    taskRunner.withLock {
      check(currentTask == TestThreadSerialTask)
      nanoTime = newTime
      yieldUntil(ResumePriority.AfterOtherTasks)
    }
  }

  /** Confirm all tasks have completed. Used by the test thread only. */
  fun assertNoMoreTasks() {
    taskRunner.assertLockNotHeld()

    taskRunner.withLock {
      assertThat(activeThreads).isEqualTo(0)
    }
  }

  /** Unblock a waiting task thread. Used by the test thread only. */
  fun interruptCoordinatorThread() {
    taskRunner.assertLockNotHeld()
    require(currentTask == TestThreadSerialTask)

    // Queue a task to interrupt the waiting coordinator.
    serialTaskQueue +=
      object : SerialTask {
        override fun start() {
          taskRunner.assertLockHeld()
          waitingCoordinatorInterrupted = true
          val coordinatorTask = waitingCoordinatorTask ?: error("no coordinator waiting")
          currentTask = coordinatorTask
          taskRunner.notifyAll()
        }
      }

    // Let the coordinator process its interruption.
    runTasks()
  }

  /** Ask a single task to proceed. Used by the test thread only. */
  fun runNextTask() {
    taskRunner.assertLockNotHeld()

    taskRunner.withLock {
      val contextSwitchCountBefore = contextSwitchCount
      yieldUntil(ResumePriority.BeforeOtherTasks) {
        contextSwitchCount > contextSwitchCountBefore
      }
    }
  }

  /** Sleep until [durationNanos] elapses. For use by the task threads. */
  fun sleep(durationNanos: Long) {
    taskRunner.withLock {
      val sleepUntil = nanoTime + durationNanos
      yieldUntil { nanoTime >= sleepUntil }
    }
  }

  /**
   * Artificially stall until manually resumed by the test thread with [runTasks]. Use this to
   * simulate races in tasks that doesn't have a deterministic sequence.
   */
  fun yield() {
    taskRunner.assertLockNotHeld()
    taskRunner.withLock {
      yieldUntil()
    }
  }

  /** Process the queue until [condition] returns true. */
  private tailrec fun yieldUntil(
    strategy: ResumePriority = ResumePriority.AfterEnqueuedTasks,
    condition: () -> Boolean = { true },
  ) {
    taskRunner.assertLockHeld()
    val self = currentTask

    val yieldCompleteTask =
      object : SerialTask {
        override fun isReady() = condition()

        override fun start() {
          taskRunner.assertLockHeld()
          currentTask = self
          taskRunner.notifyAll()
        }
      }

    if (strategy == ResumePriority.BeforeOtherTasks) {
      serialTaskQueue.addFirst(yieldCompleteTask)
    } else {
      serialTaskQueue.addLast(yieldCompleteTask)
    }

    val startedTask = startNextTask()
    val otherTasksStarted = startedTask != yieldCompleteTask

    try {
      while (currentTask != self) {
        taskRunner.wait()
      }
    } finally {
      serialTaskQueue.remove(yieldCompleteTask)
    }

    // If we're yielding until we're exhausted and a task run, keep going until a task doesn't run.
    if (strategy == ResumePriority.AfterOtherTasks && otherTasksStarted) {
      return yieldUntil(strategy, condition)
    }
  }

  private enum class ResumePriority {
    /** Resumes as soon as the condition is satisfied. */
    BeforeOtherTasks,

    /** Resumes after the already-enqueued tasks. */
    AfterEnqueuedTasks,

    /** Resumes after all other tasks, including tasks enqueued while yielding. */
    AfterOtherTasks,
  }

  /** Returns the task that was started, or null if there were no tasks to start. */
  private fun startNextTask(): SerialTask? {
    taskRunner.assertLockHeld()

    val index = serialTaskQueue.indexOfFirst { it.isReady() }
    if (index == -1) return null

    val nextTask = serialTaskQueue.removeAt(index)
    currentTask = nextTask
    contextSwitchCount++
    nextTask.start()
    return nextTask
  }

  private interface SerialTask {
    /** Returns true if this task is ready to start. */
    fun isReady() = true

    /** Do this task's work, and then start another, such as by calling [startNextTask]. */
    fun start()
  }

  private object TestThreadSerialTask : SerialTask {
    override fun start() = error("unexpected call")
  }

  inner class RunnableSerialTask(
    private val runnable: Runnable,
  ) : SerialTask {
    override fun start() {
      taskRunner.assertLockHeld()
      require(currentTask == this)
      activeThreads++

      tasksExecutor.execute {
        taskRunner.assertLockNotHeld()
        require(currentTask == this)
        try {
          runnable.run()
          require(currentTask == this) { "unexpected current task: $currentTask" }
        } finally {
          taskRunner.withLock {
            activeThreads--
            startNextTask()
          }
        }
      }
    }
  }

  /**
   * This blocking queue hooks into a fake clock rather than using regular JVM timing for functions
   * like [poll]. It is only usable within task faker tasks.
   */
  private inner class TaskFakerBlockingQueue<T>(
    val delegate: BlockingQueue<T>,
  ) : AbstractQueue<T>(),
    BlockingQueue<T> {
    override val size: Int = delegate.size

    private var editCount = 0

    override fun poll(): T = delegate.poll()

    override fun poll(
      timeout: Long,
      unit: TimeUnit,
    ): T? {
      return taskRunner.withLock {
        val waitUntil = nanoTime + unit.toNanos(timeout)
        while (true) {
          val result = poll()
          if (result != null) return@withLock result
          if (nanoTime >= waitUntil) return@withLock null
          val editCountBefore = editCount
          yieldUntil { nanoTime >= waitUntil || editCount > editCountBefore }
        }
        // TODO report compiler bug
        TODO("Can't get here")
      }
    }

    override fun put(element: T) {
      taskRunner.withLock {
        delegate.put(element)
        editCount++
      }
    }

    override fun iterator() = error("unsupported")

    override fun offer(e: T) = error("unsupported")

    override fun peek(): T = error("unsupported")

    override fun offer(
      element: T,
      timeout: Long,
      unit: TimeUnit,
    ) = error("unsupported")

    override fun take() = error("unsupported")

    override fun remainingCapacity() = error("unsupported")

    override fun drainTo(sink: MutableCollection<in T>) = error("unsupported")

    override fun drainTo(
      sink: MutableCollection<in T>,
      maxElements: Int,
    ) = error("unsupported")
  }

  /** Returns true if no tasks have been scheduled. This runs the coordinator for confirmation. */
  fun isIdle() = taskRunner.activeQueues().isEmpty()

  override fun close() {
    tasksExecutor.shutdownNow()
  }

  companion object {
    var instance = 0
  }
}
