/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.sample;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.util.Collections;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OkHttpContributors {
  private static final String ENDPOINT = "https://api.github.com/repos/lysine-dev/okhttp/contributors";
  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<List<Contributor>> CONTRIBUTORS_JSON_ADAPTER = MOSHI.adapter(
      Types.newParameterizedType(List.class, Contributor.class));

  static class Contributor {
    String login;
    int contributions;
  }

  public static void main(String... args) throws Exception {
    OkHttpClient client = new OkHttpClient();

    // Create request for remote resource.
    Request request = new Request.Builder()
        .url(ENDPOINT)
        .build();

    // Execute the request and retrieve the response.
    try (Response response = client.newCall(request).execute()) {
      // Deserialize HTTP response to concrete type.
      ResponseBody body = response.body();
      List<Contributor> contributors = CONTRIBUTORS_JSON_ADAPTER.fromJson(body.source());

      // Sort list by the most contributions.
      Collections.sort(contributors, (c1, c2) -> c2.contributions - c1.contributions);

      // Output list of contributors.
      for (Contributor contributor : contributors) {
        System.out.println(contributor.login + ": " + contributor.contributions);
      }
    }
  }

  private OkHttpContributors() {
    // No instances.
  }
}
