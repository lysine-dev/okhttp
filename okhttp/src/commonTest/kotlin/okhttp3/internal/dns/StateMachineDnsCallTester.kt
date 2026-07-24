@file:OptIn(OkHttpInternalApi::class, ExperimentalTime::class)

package okhttp3.internal.dns

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.dnsResponse
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.dns.DnsMessage.Companion.query
import okhttp3.internal.dns.StateMachineDnsCall.Transport
import okhttp3.internal.dns.StateMachineDnsCallTester.CallEvent.OnFailure
import okhttp3.internal.dns.StateMachineDnsCallTester.CallEvent.OnRecords
import okhttp3.internal.dns.StateMachineDnsCallTester.TransportEvent.QueryCanceled
import okhttp3.internal.dns.StateMachineDnsCallTester.TransportEvent.QueryEnqueued
import okio.ByteString

/**
 * Test [StateMachineDnsCall].
 *
 * This has helpers to operate on the state machine:
 *
 * This tracks all effects from the state machine as events: creating queries, canceling queries,
 * calling callbacks.
 */
fun testStateMachineDnsCall(block: StateMachineDnsCallTester.() -> Unit) {
  val tester = StateMachineDnsCallTester()
  tester.block()
  assertThat(tester.transport.events.poll(), "unexpected transport event").isNull()
}

class StateMachineDnsCallTester internal constructor() {
  var onNextEvent: (() -> Unit)? = null

  /** Defend against re-entrant calls. */
  private var acceptCallbacks: Boolean = true

  val transport = Transport()

  private val taskFaker = TaskFaker()

  private val cachingTransport =
    CachingTransport<Query>(
      taskRunner = taskFaker.taskRunner,
      delegate = transport,
      timeSource = taskFaker.timeSource,
      minimumTimeToLive = 10.seconds,
      maximumTimeToLive = 60.seconds,
      failureTimeToLive = 5.seconds,
      revalidateBeforeExpire = 2.seconds,
      maxEntryCount = 4,
    )

  fun newCall(
    request: Dns.Request,
    includeIPv6: Boolean = true,
    includeServiceMetadata: Boolean = true,
    caching: Boolean = false,
  ) = CallTester(
    request = request,
    includeIPv6 = includeIPv6,
    includeServiceMetadata = includeServiceMetadata,
    caching = caching,
  )

  fun sleep(duration: Duration) {
    taskFaker.advanceUntil(taskFaker.nanoTime + duration.inWholeNanoseconds)
  }

  /** Scriptable transport for testing. */
  inner class Transport : StateMachineDnsCall.Transport<Query> {
    val events = LinkedBlockingDeque<TransportEvent>()

    override fun newQuery(question: Question) = Query(question)

    private fun postEvent(e: TransportEvent) {
      events.put(e)

      onNextEvent?.invoke()
      onNextEvent = null
    }

    private fun takeEvent(): TransportEvent {
      taskFaker.runTasks() // Run any queued async work first.
      return events.take()
    }

    /** Asserts that the next-posted event is a query enqueue. */
    fun takeQuery(
      hostname: String,
      type: Int,
    ): QueryEnqueued {
      val event = transport.takeEvent() as QueryEnqueued
      assertThat(event.hostname).isEqualTo(hostname)
      assertThat(event.type).isEqualTo(type)
      return event
    }

    /** Combines [takeQuery] and [QueryEnqueued.respondIpAddresses]. */
    fun respondToQuery(
      hostname: String,
      type: Int,
      timeToLive: Duration = 300.seconds,
      addresses: List<InetAddress> = listOf(),
    ): QueryEnqueued {
      val event = takeQuery(hostname, type)
      event.respondIpAddresses(timeToLive, addresses)
      return event
    }

    /** Asserts that the next-posted event is a query cancel. */
    fun takeCancel(
      hostname: String,
      type: Int,
    ): QueryCanceled {
      val event = transport.takeEvent() as QueryCanceled
      assertThat(event.hostname).isEqualTo(hostname)
      assertThat(event.type).isEqualTo(type)
      return event
    }

    override fun enqueue(
      query: Query,
      callback: Transport.Callback<Query>,
    ) {
      postEvent(QueryEnqueued(query, callback))
    }

    override fun cancel(query: Query) {
      postEvent(QueryCanceled(query))
    }
  }

  /** A DNS call for the fake state machine. */
  inner class CallTester(
    request: Dns.Request,
    includeIPv6: Boolean = true,
    includeServiceMetadata: Boolean = true,
    caching: Boolean = false,
  ) : Dns.Callback {
    private val events = LinkedBlockingDeque<CallEvent>()

    val call =
      StateMachineDnsCall(
        request = request,
        transport =
          when {
            caching -> cachingTransport
            else -> transport
          },
        canceledException = null,
        includeIPv6 = includeIPv6,
        includeServiceMetadata = includeServiceMetadata,
      )

    fun enqueue() {
      check(acceptCallbacks) { "unexpected enqueue" }
      acceptCallbacks = false
      try {
        enqueue(this)
      } finally {
        acceptCallbacks = true
      }
    }

    fun enqueue(callback: Dns.Callback) {
      call.enqueue(callback)
    }

    fun cancel() {
      call.cancel()
    }

    private fun postEvent(e: CallEvent) {
      events.put(e)

      onNextEvent?.invoke()
      onNextEvent = null
    }

    private fun takeEvent(): CallEvent {
      taskFaker.runTasks() // Run any queued async work first.
      return events.take()
    }

    override fun onRecords(
      call: Dns.Call,
      last: Boolean,
      records: List<Dns.Record>,
    ) {
      check(call == this.call)
      check(acceptCallbacks) { "unexpected callback" }

      acceptCallbacks = false
      try {
        postEvent(OnRecords(last, records))
      } finally {
        acceptCallbacks = true
      }
    }

    override fun onFailure(
      call: Dns.Call,
      e: IOException,
    ) {
      check(call == this.call)
      check(acceptCallbacks) { "unexpected callback" }

      acceptCallbacks = false
      try {
        postEvent(OnFailure(e))
      } finally {
        acceptCallbacks = true
      }
    }

    /**
     * Asserts that the next-posted event is a call to [Dns.Callback.onRecords] with a list of IP
     * addresses.
     */
    fun takeOnRecordsIpAddresses(
      last: Boolean = false,
      addresses: List<InetAddress>,
    ): OnRecords {
      val event = takeEvent() as OnRecords
      assertThat(event.last).isEqualTo(last)
      assertThat(event.records.map { (it as Dns.Record.IpAddress).address })
        .isEqualTo(addresses)
      return event
    }

    /**
     * Asserts that the next-posted event is a call to [Dns.Callback.onRecords] with service metadata.
     */
    fun takeOnRecordsServiceMetadata(
      last: Boolean = false,
      alpnIds: List<Protocol>? = null,
      echConfigList: ByteString? = null,
    ): OnRecords {
      val event = takeEvent() as OnRecords
      assertThat(event.last).isEqualTo(last)

      val serviceMetadata = event.records.single() as Dns.Record.ServiceMetadata
      assertThat(serviceMetadata.alpnIds).isEqualTo(alpnIds)
      assertThat(serviceMetadata.echConfigList).isEqualTo(echConfigList)
      return event
    }

    fun takeAllRecords(): List<Dns.Record> =
      buildList {
        do {
          val event = takeEvent() as OnRecords
          addAll(event.records)
        } while (!event.last)
      }

    /** Asserts that the next-posted event is a call to [Dns.Callback.onFailure]. */
    fun takeOnFailure(message: String): OnFailure {
      val event = takeEvent() as OnFailure
      assertThat(event.e).hasMessage(message)
      return event
    }
  }

  class Query(
    val question: Question,
  )

  sealed interface TransportEvent {
    class QueryEnqueued(
      val query: Query,
      val callback: Transport.Callback<Query>,
    ) : TransportEvent {
      val hostname: String
        get() = query.question.name
      val type: Int
        get() = query.question.type

      /** Respond to a [TYPE_HTTPS] query with service metadata. */
      fun respondServiceMetadata(
        timeToLive: Duration = 300.seconds,
        alpnIds: List<String>? = null,
        echConfigList: ByteString? = null,
      ) {
        callback.onResponse(
          dnsResponse(
            query(query.question),
            listOf(
              ResourceRecord.Https(
                name = query.question.name,
                timeToLive = timeToLive.inWholeSeconds.toInt(),
                alpnIds = alpnIds,
                echConfigList = echConfigList,
              ),
            ),
          ),
        )
      }

      /** Respond to any query with a failure. */
      fun respondFailure(message: String) {
        callback.onFailure(IOException(message))
      }

      /** Respond to a [TYPE_A] or [TYPE_AAAA] query with a (possibly-empty) list of IP addresses. */
      fun respondIpAddresses(
        timeToLive: Duration = 300.seconds,
        addresses: List<InetAddress> = listOf(),
      ) {
        callback.onResponse(
          dnsResponse(
            query(query.question),
            addresses.map { address ->
              ResourceRecord.IpAddress(
                name = query.question.name,
                timeToLive = timeToLive.inWholeSeconds.toInt(),
                address = address,
              )
            },
          ),
        )
      }
    }

    class QueryCanceled(
      val query: Query,
    ) : TransportEvent {
      val hostname: String
        get() = query.question.name
      val type: Int
        get() = query.question.type
    }
  }

  sealed interface CallEvent {
    class OnRecords(
      val last: Boolean,
      val records: List<Dns.Record>,
    ) : CallEvent

    class OnFailure(
      val e: IOException,
    ) : CallEvent
  }
}

fun List<Dns.Record>.addresses(): List<InetAddress> = map { (it as Dns.Record.IpAddress).address }
