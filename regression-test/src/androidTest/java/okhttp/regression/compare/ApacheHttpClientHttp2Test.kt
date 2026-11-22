/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package okhttp.regression.compare

import org.apache.hc.client5.http.async.methods.SimpleHttpRequests
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse
import org.apache.hc.client5.http.impl.async.HttpAsyncClients
import org.apache.hc.core5.concurrent.FutureCallback
import org.apache.hc.core5.http.ProtocolVersion
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test

/**
 * Simplified from
 * https://hc.apache.org/httpcomponents-client-5.0.x/httpclient5/examples/AsyncClientTlsAlpn.java
 *
 * Mainly intended to verify behaviour of popular clients across Android versions, similar
 * to observing Firefox or Chrome browser behaviour.
 */
@Ignore("Failing with Netty errors")
class ApacheHttpClientHttp2Test {
  @Test
  fun testHttp2() {
    val client = HttpAsyncClients.createHttp2Default()

    client.use { client ->
      client.start()
      val request = SimpleHttpRequests.get("https://google.com/robots.txt")
      val response = client.execute(request, LoggingCallback).get()

      println("Protocol ${response.version}")
      println("Response ${response.code}")
      println("${response.body.bodyText.substring(0, 20)}...")

      Assert.assertEquals(ProtocolVersion("HTTP", 2, 0), response.version)
    }
  }
}

object LoggingCallback : FutureCallback<SimpleHttpResponse> {
  override fun completed(response: SimpleHttpResponse) {
  }

  override fun failed(ex: Exception) {
    println("Failed: $ex")
  }

  override fun cancelled() {
    println("Cancelled")
  }
}
