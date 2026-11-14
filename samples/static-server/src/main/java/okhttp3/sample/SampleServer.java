/*
 * Copyright (C) 2015 Square, Inc.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.Okio;

public class SampleServer extends Dispatcher {
  private final SSLContext sslContext;
  private final String root;
  private final int port;

  public SampleServer(SSLContext sslContext, String root, int port) {
    this.sslContext = sslContext;
    this.root = root;
    this.port = port;
  }

  public void run() throws IOException {
    MockWebServer server = new MockWebServer();
    server.useHttps(sslContext.getSocketFactory(), false);
    server.setDispatcher(this);
    server.start(port);
  }

  @Override public MockResponse dispatch(RecordedRequest request) {
    String path = request.getPath();
    try {
      if (!path.startsWith("/") || path.contains("..")) throw new FileNotFoundException();

      File file = new File(root + path);
      return file.isDirectory()
          ? directoryToResponse(path, file)
          : fileToResponse(path, file);
    } catch (FileNotFoundException e) {
      return new MockResponse()
          .setStatus("HTTP/1.1 404")
          .addHeader("content-type: text/plain; charset=utf-8")
          .setBody("NOT FOUND: " + path);
    } catch (IOException e) {
      return new MockResponse()
          .setStatus("HTTP/1.1 500")
          .addHeader("content-type: text/plain; charset=utf-8")
          .setBody("SERVER ERROR: " + e);
    }
  }

  private MockResponse directoryToResponse(String basePath, File directory) {
    if (!basePath.endsWith("/")) basePath += "/";

    StringBuilder response = new StringBuilder();
    response.append(String.format("<html><head><title>%s</title></head><body>", basePath));
    response.append(String.format("<h1>%s</h1>", basePath));
    for (String file : directory.list()) {
      response.append(String.format("<div class='file'><a href='%s'>%s</a></div>",
          basePath + file, file));
    }
    response.append("</body></html>");

    return new MockResponse()
        .setStatus("HTTP/1.1 200")
        .addHeader("content-type: text/html; charset=utf-8")
        .setBody(response.toString());
  }

  private MockResponse fileToResponse(String path, File file) throws IOException {
    return new MockResponse()
        .setStatus("HTTP/1.1 200")
        .setBody(fileToBytes(file))
        .addHeader("content-type: " + contentType(path));
  }

  private Buffer fileToBytes(File file) throws IOException {
    Buffer result = new Buffer();
    result.writeAll(Okio.source(file));
    return result;
  }

  private String contentType(String path) {
    if (path.endsWith(".png")) return "image/png";
    if (path.endsWith(".jpg")) return "image/jpeg";
    if (path.endsWith(".jpeg")) return "image/jpeg";
    if (path.endsWith(".gif")) return "image/gif";
    if (path.endsWith(".html")) return "text/html; charset=utf-8";
    if (path.endsWith(".txt")) return "text/plain; charset=utf-8";
    return "application/octet-stream";
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      System.out.println("Usage: SampleServer <keystore> <password> <root file> <port>");
      return;
    }

    String keystoreFile = args[0];
    String password = args[1];
    String root = args[2];
    int port = Integer.parseInt(args[3]);

    SSLContext sslContext = sslContext(keystoreFile, password);
    SampleServer server = new SampleServer(sslContext, root, port);
    server.run();
  }

  private static SSLContext sslContext(String keystoreFile, String password)
      throws GeneralSecurityException, IOException {
    KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
    try (InputStream in = new FileInputStream(keystoreFile)) {
      keystore.load(in, password.toCharArray());
    }
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keystore, password.toCharArray());

    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keystore);

    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(
        keyManagerFactory.getKeyManagers(),
        trustManagerFactory.getTrustManagers(),
        new SecureRandom());

    return sslContext;
  }
}
