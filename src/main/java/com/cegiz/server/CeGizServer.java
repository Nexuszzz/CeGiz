package com.cegiz.server;

import com.cegiz.app.model.FoodRecognition;
import com.cegiz.shared.GeminiRequest;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.*;
import org.json.*;

/** Owner-managed local or hosted service. The phone never receives the Gemini provider key. */
public final class CeGizServer implements AutoCloseable {
  @FunctionalInterface
  public interface Provider {
    String analyze(byte[] photo) throws Exception;
  }

  private final HttpServer server;
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final Semaphore slots = new Semaphore(2);
  private final Provider provider;
  private final String accessToken;
  private final boolean hosted;
  private long window = System.nanoTime();
  private int requests;

  public CeGizServer(int port, Provider provider) throws IOException {
    this("127.0.0.1", port, provider, "");
  }

  public CeGizServer(String host, int port, Provider provider, String accessToken)
      throws IOException {
    this.hosted = !host.equals("127.0.0.1") && !host.equals("::1");
    if (hosted && (accessToken == null || !accessToken.matches("[A-Za-z0-9_-]{32,128}")))
      throw new IllegalArgumentException(
          "Hosted service requires CEGIZ_CLIENT_TOKEN (32–128 URL-safe characters).");
    this.provider = provider;
    this.accessToken = accessToken == null ? "" : accessToken;
    server = HttpServer.create(new InetSocketAddress(host, port), 16);
    server.createContext("/health", this::health);
    server.createContext("/", this::health);
    server.createContext("/v1/food:analyze", this::analyze);
    server.setExecutor(workers);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
  }

  private void health(HttpExchange x) throws IOException {
    if (!x.getRequestURI().getPath().equals("/health")
        && !x.getRequestURI().getPath().equals("/")) {
      send(x, 404, "NOT_FOUND");
      return;
    }
    if (!x.getRequestMethod().equals("GET")) {
      send(x, 405, "METHOD_NOT_ALLOWED");
      return;
    }
    send(
        x,
        200,
        new JSONObject()
            .put("status", "ready")
            .put("mode", hosted ? "hosted" : "local")
            .toString());
  }

  private synchronized boolean allowed() {
    long now = System.nanoTime();
    if (now - window > TimeUnit.MINUTES.toNanos(1)) {
      requests = 0;
      window = now;
    }
    return requests++ < 20;
  }

  private void analyze(HttpExchange x) throws IOException {
    try {
      if (!x.getRequestURI().getPath().equals("/v1/food:analyze")) {
        send(x, 404, "NOT_FOUND");
        return;
      }
      if (!x.getRequestMethod().equals("POST")) {
        send(x, 405, "METHOD_NOT_ALLOWED");
        return;
      }
      if (!accessToken.isEmpty()) {
        String authorization = x.getRequestHeaders().getFirst("Authorization");
        String supplied =
            authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : "";
        if (supplied.length() > 128
            || !MessageDigest.isEqual(
                accessToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
          send(x, 401, "APP_ACCESS_REQUIRED");
          return;
        }
      }
      if (x.getRequestHeaders().getFirst("Origin") != null) {
        send(x, 403, "ORIGIN_NOT_ALLOWED");
        return;
      }
      if (!"image/jpeg".equalsIgnoreCase(x.getRequestHeaders().getFirst("Content-Type"))) {
        send(x, 415, "JPEG_REQUIRED");
        return;
      }
      if (!allowed() || !slots.tryAcquire()) {
        send(x, 429, "BUSY");
        return;
      }
      try {
        byte[] photo = readBounded(x.getRequestBody(), 3 * 1024 * 1024);
        if (photo.length < 4 || (photo[0] & 255) != 255 || (photo[1] & 255) != 216) {
          send(x, 400, "INVALID_PHOTO");
          return;
        }
        String response = provider.analyze(photo);
        // Independent validation happens here and again in Android. No numerical fallback.
        FoodRecognition.parse(response);
        send(x, 200, response);
      } catch (TooLarge e) {
        send(x, 413, "PHOTO_TOO_LARGE");
      } catch (ProviderFailure e) {
        send(x, e.status, e.code);
      } catch (Exception e) {
        send(x, 422, "ANALYSIS_UNAVAILABLE");
      } finally {
        slots.release();
      }
    } finally {
      x.close();
    }
  }

  private static void send(HttpExchange x, int status, String body) throws IOException {
    if (!body.startsWith("{")) body = new JSONObject().put("error", body).toString();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    x.getResponseHeaders().set("Cache-Control", "no-store");
    x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    x.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = x.getResponseBody()) {
      out.write(bytes);
    }
  }

  static byte[] readBounded(InputStream input, int max) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if (output.size() + n > max) throw new TooLarge();
      output.write(buffer, 0, n);
    }
    return output.toByteArray();
  }

  private static final class TooLarge extends IOException {}

  private static final class ProviderFailure extends IOException {
    final int status;
    final String code;

    ProviderFailure(int status, String code) {
      this.status = status;
      this.code = code;
    }
  }

  public static String extract(String raw) throws Exception {
    JSONObject result = new JSONObject(raw);
    JSONArray candidates = result.optJSONArray("candidates");
    if (candidates == null || candidates.isEmpty()) throw new ProviderFailure(422, "NO_RESULT");
    JSONObject candidate = candidates.getJSONObject(0);
    if (!"STOP".equals(candidate.optString("finishReason")))
      throw new ProviderFailure(422, "INCOMPLETE_RESULT");
    JSONArray parts = candidate.getJSONObject("content").getJSONArray("parts");
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < parts.length(); i++)
      if (!parts.getJSONObject(i).optBoolean("thought"))
        text.append(parts.getJSONObject(i).optString("text", ""));
    String recognized = text.toString();
    FoodRecognition.parse(recognized);
    return recognized;
  }

  static Provider google(String key, String model) {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    URI destination =
        URI.create(
            "https://generativelanguage.googleapis.com/v1beta/models/"
                + model
                + ":generateContent");
    return photo -> {
      HttpRequest request =
          HttpRequest.newBuilder(destination)
              .timeout(Duration.ofSeconds(65))
              .header("x-goog-api-key", key)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(GeminiRequest.create(photo).toString()))
              .build();
      HttpResponse<InputStream> response;
      try {
        response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      } catch (Exception e) {
        throw new ProviderFailure(503, "PROVIDER_UNAVAILABLE");
      }
      try (InputStream in = response.body()) {
        if (response.statusCode() != 200)
          throw new ProviderFailure(
              response.statusCode() == 429 ? 429 : 503, "PROVIDER_UNAVAILABLE");
        return extract(new String(readBounded(in, 256 * 1024), StandardCharsets.UTF_8));
      }
    };
  }

  public static void main(String[] args) throws Exception {
    String key = System.getenv("GEMINI_API_KEY");
    String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.1-flash-lite");
    if (key == null || !key.matches("[A-Za-z0-9._-]{20,512}")) {
      System.err.println(
          "Administrator: configure GEMINI_API_KEY in server variables, or use Save-Key.ps1"
              + " locally.");
      System.exit(2);
    }
    if (!model.matches("gemini-[a-z0-9.-]{2,80}"))
      throw new IllegalArgumentException("Invalid server model");
    String railwayPort = System.getenv("PORT");
    boolean railway = railwayPort != null && !railwayPort.isBlank();
    int port =
        Integer.parseInt(
            railway ? railwayPort : System.getenv().getOrDefault("CEGIZ_PORT", "8787"));
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid listening port");
    String host =
        System.getenv().getOrDefault("CEGIZ_BIND_HOST", railway ? "0.0.0.0" : "127.0.0.1");
    String token = System.getenv().getOrDefault("CEGIZ_CLIENT_TOKEN", "");
    CeGizServer service = new CeGizServer(host, port, google(key, model), token);
    Runtime.getRuntime().addShutdownHook(new Thread(service::close));
    service.start();
    System.out.println("CeGiz service ready on " + host + ":" + service.port());
  }
}
