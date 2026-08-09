/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.dns.CLASS_IN
import okhttp3.internal.dns.DnsMessage
import okhttp3.internal.dns.DnsMessageReader
import okhttp3.internal.dns.DnsMessageWriter
import okhttp3.internal.dns.Question
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.dns.TYPE_A
import okhttp3.internal.dns.TYPE_AAAA
import okhttp3.internal.dns.TYPE_HTTPS
import okio.Buffer
import okio.ByteString.Companion.decodeBase64

/**
 * Handles DNS calls using in-memory records.
 */
class FakeDns(
  private val taskRunner: TaskRunner = TaskRunner.INSTANCE,
) : Dns {
  private val data = ConcurrentHashMap<String, List<ResourceRecord>>()

  var extraHeaders: Headers = Headers.headersOf()
  private val requests = LinkedBlockingDeque<Request>()
  val nextSequenceIndex = AtomicInteger(0)
  val sequenceIndexToOverride = ConcurrentHashMap<Int, MockResponse>()
  private var nextAddress = 0xff000064L // 255.0.0.100 in IPv4; ::ff00:64 in IPv6.

  /** Returns a MockWebServer dispatcher that serves DNS over HTTPS records. */
  val dispatcher =
    object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val sequenceIndex = nextSequenceIndex.getAndIncrement()
        val override = sequenceIndexToOverride.remove(sequenceIndex)
        if (override != null) return override

        val requestBody = request.body
        val queryParameter = request.url.queryParameter("dns")
        val encodedDnsQuery =
          when {
            requestBody != null -> Buffer().write(requestBody)
            queryParameter != null -> Buffer().write(queryParameter.decodeBase64()!!)
            else -> return MockResponse(code = 400)
          }

        val dnsRequest = DnsMessageReader(encodedDnsQuery).read()
        requests.put(Request.DnsRequest(dnsRequest, request))

        val dnsResponse = invoke(dnsRequest)

        val body = Buffer()
        DnsMessageWriter(body).write(dnsResponse)

        return MockResponse
          .Builder()
          .addHeader("content-type", "application/dns-message")
          .apply {
            for ((name, value) in extraHeaders) {
              addHeader(name, value)
            }
          }.body(body)
          .build()
      }
    }

  operator fun set(
    hostname: String,
    records: List<ResourceRecord>,
  ) {
    data[hostname] = records
  }

  @JvmName("set-inetAddresses") // Avoid raw types collision.
  operator fun set(
    hostname: String,
    inetAddresses: List<InetAddress>,
  ) {
    set(
      hostname,
      inetAddresses.map { inetAddress ->
        ResourceRecord.IpAddress(
          name = hostname,
          timeToLive = 5,
          address = inetAddress,
        )
      },
    )
  }

  override fun newCall(request: Dns.Request): Dns.Call = FakeDnsCall(request)

  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    requests.put(Request.FunctionCall(hostname))
    return get(hostname)
  }

  /** Note that this request is not recorded. */
  @Throws(UnknownHostException::class)
  operator fun get(hostname: String): List<InetAddress> {
    val records = data[hostname] ?: throw UnknownHostException()
    return records
      .filterIsInstance<ResourceRecord.IpAddress>()
      .map { it.address }
  }

  /** Clears the results for `hostname`. */
  fun clear(hostname: String) {
    data.remove(hostname)
  }

  fun takeRequest(): Request = requests.take()

  fun pollRequest(): Request? = requests.poll()

  fun takeAllRequests(): List<Request> =
    buildList {
      while (true) {
        val pair = pollRequest()
        add(pair ?: break)
      }
    }

  /**
   * Takes all requests received thus far (for all question types), and asserts that they match
   * [expectedHosts].
   */
  fun assertRequests(vararg expectedHosts: String?) {
    val actualHostnames = takeAllRequests().map { it.hostname }
    assertThat(actualHostnames).containsExactly(*expectedHosts)
  }

  /** Allocates and returns `count` fake IPv4 addresses like [255.0.0.100, 255.0.0.101].  */
  fun allocate(count: Int): List<InetAddress> {
    val from = nextAddress
    nextAddress += count
    return (from until nextAddress)
      .map {
        return@map InetAddress.getByAddress(
          Buffer().writeInt(it.toInt()).readByteArray(),
        )
      }
  }

  /** Allocates and returns `count` fake IPv6 addresses like [::ff00:64, ::ff00:65].  */
  fun allocateIpv6(count: Int): List<InetAddress> {
    val from = nextAddress
    nextAddress += count
    return (from until nextAddress)
      .map {
        return@map InetAddress.getByAddress(
          Buffer().writeLong(0L).writeLong(it).readByteArray(),
        )
      }
  }

  fun query(request: DnsMessage): DnsMessage {
    requests.put(Request.DnsRequest(request))
    return invoke(request)
  }

  private fun invoke(request: DnsMessage): DnsMessage {
    val answers =
      buildList {
        for (question in request.questions) {
          val records = data[question.name] ?: continue
          for (record in records) {
            if (!record.matches(question)) continue
            add(record)
          }
        }
      }

    return DnsMessage.response(
      id = request.id,
      questions = request.questions,
      answers = answers,
    )
  }

  private fun ResourceRecord.matches(question: Question): Boolean {
    if (question.`class` != CLASS_IN) return false

    return when (question.type) {
      TYPE_A -> (this as? ResourceRecord.IpAddress)?.address is Inet4Address
      TYPE_AAAA -> (this as? ResourceRecord.IpAddress)?.address is Inet6Address
      TYPE_HTTPS -> this is ResourceRecord.Https
      else -> false
    }
  }

  /**
   * Deliver each kind of DNS record in a separate callback, to most accurately simulate what a real
   * DNS server does.
   */
  private inner class FakeDnsCall(
    override val request: Dns.Request,
  ) : Dns.Call {
    @Volatile private var canceled = false
    private var executed = false
    private val taskQueue = taskRunner.newQueue()

    override fun cancel() {
      canceled = true
    }

    override fun isCanceled() = canceled

    override fun enqueue(callback: Dns.Callback) {
      check(!executed)
      executed = true

      requests.put(Request.FunctionCall(request.hostname))

      if (canceled) {
        taskQueue.execute("${request.hostname} dns") {
          callback.onFailure(
            call = this,
            e = IOException("canceled"),
          )
        }
        return
      }

      val resourceRecords = data[request.hostname] ?: listOf()
      if (resourceRecords.isEmpty()) {
        taskQueue.execute("${request.hostname} dns") {
          callback.onRecords(
            call = this,
            last = true,
            records = listOf(),
          )
        }
        return
      }

      val serviceMetadataRecords = mutableListOf<Dns.Record.ServiceMetadata>()
      val ipv6Records = mutableListOf<Dns.Record.IpAddress>()
      val ipv4Records = mutableListOf<Dns.Record.IpAddress>()
      for (resourceRecord in resourceRecords) {
        when (resourceRecord) {
          is ResourceRecord.Https -> {
            serviceMetadataRecords +=
              Dns.Record.ServiceMetadata(
                hostname = resourceRecord.targetName.takeIf { it != "" } ?: request.hostname,
                port = resourceRecord.port,
                alpnIds = resourceRecord.alpnIds?.map { Protocol.get(it) },
                ipAddressHints = resourceRecord.ipAddressHints,
                echConfigList = resourceRecord.echConfigList,
              )
          }

          is ResourceRecord.IpAddress -> {
            val ipAddressRecord = Dns.Record.IpAddress(request.hostname, resourceRecord.address)
            when (resourceRecord.address) {
              is Inet4Address -> ipv4Records += ipAddressRecord
              is Inet6Address -> ipv6Records += ipAddressRecord
              else -> error("unexpected address")
            }
          }
        }
      }

      taskQueue.execute("${request.hostname} dns") {
        if (serviceMetadataRecords.isNotEmpty()) {
          callback.onRecords(
            call = this,
            last = ipv6Records.isEmpty() && ipv4Records.isEmpty(),
            records = serviceMetadataRecords,
          )
        }
        if (ipv6Records.isNotEmpty()) {
          callback.onRecords(
            call = this,
            last = ipv4Records.isEmpty(),
            records = ipv6Records,
          )
        }
        if (ipv4Records.isNotEmpty()) {
          callback.onRecords(
            call = this,
            last = true,
            records = ipv4Records,
          )
        }
      }
    }
  }

  sealed interface Request {
    val hostname: String

    data class DnsRequest(
      val dnsRequest: DnsMessage,
      val httpRequest: RecordedRequest? = null,
    ) : Request {
      override val hostname: String
        get() = dnsRequest.questions.single().name
    }

    data class FunctionCall(
      override val hostname: String,
    ) : Request
  }
}
