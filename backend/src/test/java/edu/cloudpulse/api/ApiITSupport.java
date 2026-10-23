package edu.cloudpulse.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import edu.cloudpulse.api.detection.DetectionEngine;
import edu.cloudpulse.api.support.FakeKubernetes;
import edu.cloudpulse.api.support.FakeTelemetry;
import edu.cloudpulse.api.support.MutableClock;
import edu.cloudpulse.api.support.StubHttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared set-up for the integration tests: one real PostgreSQL (Testcontainers) with the Flyway
 * schema, the real HTTP layer and JSON, and scripted stand-ins for Prometheus, the two demo
 * services and the Kubernetes API. The schedulers are off; tests drive detection cycles and
 * remediation steps themselves with a synthetic clock.
 */
abstract class ApiITSupport {

  @SuppressWarnings("resource")
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withEnv("POSTGRES_DB", "cloudpulse")
          .withEnv("POSTGRES_USER", "cloudpulse")
          .withEnv("POSTGRES_PASSWORD", "cloudpulse")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                  .withStartupTimeout(Duration.ofMinutes(2)));

  static final StubHttpServer STUB = StubHttpServer.start();
  static final FakeTelemetry TELEMETRY = new FakeTelemetry();
  static final FakeKubernetes KUBERNETES = new FakeKubernetes();
  private static final Configuration JSON =
      Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL);

  static {
    POSTGRES.start();
    TELEMETRY.install(STUB);
    KUBERNETES.install(STUB);
    // Both test classes share the container and the stub for the whole run; the JVM ends them.
  }

  @DynamicPropertySource
  static void commonProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(5432)
                + "/cloudpulse");
    registry.add("spring.datasource.username", () -> "cloudpulse");
    registry.add("spring.datasource.password", () -> "cloudpulse");
    registry.add("cloudpulse.prometheus.url", STUB::baseUrl);
    registry.add("CHECKOUT_URL", () -> STUB.baseUrl() + "/checkout");
    registry.add("PAYMENT_URL", () -> STUB.baseUrl() + "/payment");
    registry.add("cloudpulse.scheduling.enabled", () -> "false");
  }

  @TestConfiguration
  static class TestClockConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
    }
  }

  @Value("${local.server.port}")
  int port;

  @Autowired DetectionEngine engine;
  @Autowired MutableClock clock;
  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void resetState() {
    // TRUNCATE is allowed on the append-only audit table (the trigger blocks row UPDATE/DELETE).
    jdbc.execute(
        "TRUNCATE remediation_actions, incident_evidence, audit_events, incidents"
            + " RESTART IDENTITY CASCADE");
    TELEMETRY.healthy();
    KUBERNETES.reset();
    STUB.clearRequests();
    // A long jump breaks any detection window left by a previous test (max sample gap = 20 s).
    clock.advance(Duration.ofHours(1));
  }

  // ---- helpers -----------------------------------------------------------------------------

  record Reply(int status, String contentType, DocumentContext json, String body) {
    Object read(String path) {
      return json.read(path);
    }

    double number(String path) {
      return ((Number) json.read(path)).doubleValue();
    }

    long id(String path) {
      return ((Number) json.read(path)).longValue();
    }
  }

  Reply send(HttpRequest request) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      String body = response.body();
      DocumentContext json =
          body == null || body.isBlank() ? null : JsonPath.using(JSON).parse(body);
      return new Reply(
          response.statusCode(),
          response.headers().firstValue("Content-Type").orElse(""),
          json,
          body);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  Reply get(String path) {
    return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build());
  }

  Reply post(String path, String json) {
    return send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build());
  }

  void cycles(int count) {
    for (int i = 0; i < count; i++) {
      engine.evaluateAll();
      clock.advance(Duration.ofSeconds(5));
    }
  }

  /** Holds checkout-api's p95 at {@code p95} until a latency incident opens; returns its id. */
  long openLatencyIncident(double p95) {
    TELEMETRY.checkout.latency = p95;
    cycles(7); // samples at t = 0 … 30 s → opens on the 30 s sample
    Reply active = get("/api/incidents?status=active");
    assertThat((Integer) active.read("$.length()")).isEqualTo(1);
    return active.id("$[0].id");
  }
}
