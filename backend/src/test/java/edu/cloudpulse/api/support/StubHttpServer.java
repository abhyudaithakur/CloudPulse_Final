package edu.cloudpulse.api.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Minimal in-process HTTP server (JDK built-in) standing in for Prometheus and the demo service.
 * Avoids a WireMock dependency and lets tests script outages, errors and slow responses.
 */
public final class StubHttpServer implements AutoCloseable {

  /** A received request. Header names are lower-case. */
  public record Request(
      String method, String path, Map<String, String> query, String body, Map<String, String> headers) {
    public String header(String name) {
      return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }
  }

  public record Response(int status, String body, long delayMillis) {
    public static Response json(String body) {
      return new Response(200, body, 0);
    }

    public static Response status(int status) {
      return new Response(status, "{\"status\":\"error\"}", 0);
    }

    public static Response of(int status, String body) {
      return new Response(status, body, 0);
    }

    public Response delayedBy(long millis) {
      return new Response(status, body, millis);
    }
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, Function<Request, Response>> routes = new ConcurrentHashMap<>();
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  private StubHttpServer(HttpServer server) {
    this.server = server;
  }

  public static StubHttpServer start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      StubHttpServer stub = new StubHttpServer(server);
      server.createContext("/", stub::handle);
      server.setExecutor(stub.executor);
      server.start();
      return stub;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void route(String path, Function<Request, Response> handler) {
    routes.put(path, handler);
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  /** Requests received on one path, oldest first. */
  public List<Request> requests(String path) {
    return requests.stream().filter(r -> r.path().equals(path)).toList();
  }

  public void clearRequests() {
    requests.clear();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach(
            (name, values) ->
                headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
    Request request =
        new Request(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            parseQuery(exchange.getRequestURI().getRawQuery()),
            body,
            headers);
    requests.add(request);
    Function<Request, Response> handler = routes.get(request.path());
    Response response = handler == null ? Response.status(404) : handler.apply(request);
    if (response.delayMillis() > 0) {
      try {
        Thread.sleep(response.delayMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(response.status(), bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, String> parseQuery(String raw) {
    Map<String, String> query = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      query.put(
          URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return query;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
