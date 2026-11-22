/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp.regression.compare;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import static org.junit.Assert.assertEquals;

import okhttp3.Response;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * OkHttp.
 *
 * https://lysine.dev/okhttp/
 */
@RunWith(AndroidJUnit4.class)
public class OkHttpClientTest {
  @Test public void get() throws IOException {
    OkHttpClient client = new OkHttpClient();
    Request request = new Request.Builder()
        .url("https://google.com/robots.txt")
        .build();
    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
      assertEquals(Protocol.HTTP_2, response.protocol());
    }
  }
}
