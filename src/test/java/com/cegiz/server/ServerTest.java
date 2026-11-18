package com.cegiz.server;

import com.cegiz.shared.GeminiRequest;
import java.net.*;
import java.net.http.*;
import org.json.*;

/** Standalone HTTP regression checks; the fake provider exists only inside these tests. */
public final class ServerTest {
  private static int checks;

  private static void check(boolean ok) {
    if (!ok) throw new AssertionError("Server check " + (checks + 1));
    checks++;
  }

  private static final String FOOD =
      "{\"contains_food\":true,\"meal_name\":\"Roti"
          + " foto\",\"uncertain\":false,\"assumptions\":[],\"ingredients\":[{\"name\":\"Roti\",\"image_key\":\"other\",\"estimated_grams\":80,\"energy_kcal_per_100g\":220,\"protein_g_per_100g\":8,\"carbs_g_per_100g\":40,\"fat_g_per_100g\":3}]}";

  public static void main(String[] args) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    try (CeGizServer server = new CeGizServer(0, photo -> FOOD)) {
      server.start();
      String base = "http://127.0.0.1:" + server.port();
      var health =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/health")).build(),
              HttpResponse.BodyHandlers.ofString());
      check(health.statusCode() == 200 && !health.body().contains("key"));
      var endpoint = URI.create(base + "/v1/food:analyze");
      byte[] jpeg = {(byte) 255, (byte) 216, 0, 0, (byte) 255, (byte) 217};
      var good =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Content-Type", "image/jpeg")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(
          good.statusCode() == 200
              && new JSONObject(good.body()).getString("meal_name").equals("Roti foto"));
      var bad =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Content-Type", "image/jpeg")
                  .POST(HttpRequest.BodyPublishers.ofString("invalid"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(bad.statusCode() == 400);
      var mime =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(mime.statusCode() == 415);
      var origin =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Content-Type", "image/jpeg")
                  .header("Origin", "https://untrusted.example")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(origin.statusCode() == 403);
      var method =
          client.send(
              HttpRequest.newBuilder(endpoint).build(), HttpResponse.BodyHandlers.ofString());
      check(method.statusCode() == 405);
      var other =
          client.send(
              HttpRequest.newBuilder(URI.create(endpoint + "/other"))
                  .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(other.statusCode() == 404);
      byte[] huge = new byte[3 * 1024 * 1024 + 1];
      huge[0] = (byte) 255;
      huge[1] = (byte) 216;
      var size =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Content-Type", "image/jpeg")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(huge))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(size.statusCode() == 413);
    }
    String raw =
        new JSONObject()
            .put(
                "candidates",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("finishReason", "STOP")
                            .put(
                                "content",
                                new JSONObject()
                                    .put(
                                        "parts",
                                        new JSONArray().put(new JSONObject().put("text", FOOD))))))
            .toString();
    check(CeGizServer.extract(raw).equals(FOOD));
    try {
      CeGizServer.extract(raw.replace("STOP", "MAX_TOKENS"));
      throw new AssertionError();
    } catch (java.io.IOException expected) {
      checks++;
    }
    String request = GeminiRequest.create(new byte[] {1, 2, 3}).toString();
    check(
        !request.contains("catalog_id")
            && !request.contains("api_key")
            && request.contains("energy_kcal_per_100g"));
    try (CeGizServer server = new CeGizServer(0, photo -> "{}")) {
      server.start();
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.port() + "/v1/food:analyze"))
                  .header("Content-Type", "image/jpeg")
                  .POST(
                      HttpRequest.BodyPublishers.ofByteArray(
                          new byte[] {(byte) 255, (byte) 216, 0, 0}))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(response.statusCode() == 422 && !response.body().contains("Roti"));
    }
    try {
      new CeGizServer("0.0.0.0", 0, photo -> FOOD, "");
      throw new AssertionError(
          "Public mode must not start without application access configuration");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    String token = "test_access_0123456789_0123456789_0123456789";
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    try (CeGizServer server =
        new CeGizServer(
            "0.0.0.0",
            0,
            photo -> {
              calls.incrementAndGet();
              return FOOD;
            },
            token)) {
      server.start();
      URI base = URI.create("http://127.0.0.1:" + server.port());
      var health =
          client.send(
              HttpRequest.newBuilder(base.resolve("/health")).build(),
              HttpResponse.BodyHandlers.ofString());
      check(
          health.statusCode() == 200
              && new JSONObject(health.body()).getString("mode").equals("hosted")
              && !health.body().contains(token));
      var root =
          client.send(HttpRequest.newBuilder(base).build(), HttpResponse.BodyHandlers.ofString());
      check(root.statusCode() == 200);
      byte[] jpeg = {(byte) 255, (byte) 216, 0, 0};
      var endpoint = base.resolve("/v1/food:analyze");
      var missing =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Content-Type", "image/jpeg")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(missing.statusCode() == 401 && calls.get() == 0);
      var wrong =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Content-Type", "image/jpeg")
                  .header("Authorization", "Bearer incorrect")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(wrong.statusCode() == 401 && calls.get() == 0);
      var correct =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Content-Type", "image/jpeg")
                  .header("Authorization", "Bearer " + token)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(
          correct.statusCode() == 200
              && calls.get() == 1
              && new JSONObject(correct.body()).getString("meal_name").equals("Roti foto"));
      var browser =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .header("Content-Type", "image/jpeg")
                  .header("Authorization", "Bearer " + token)
                  .header("Origin", "https://example.com")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(browser.statusCode() == 403 && calls.get() == 1);
    }
    System.out.println("OK (" + checks + " server checks)");
  }
}
