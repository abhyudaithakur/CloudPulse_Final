package edu.cloudpulse.api.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.domain.Comparison;
import edu.cloudpulse.api.support.StubHttpServer;
import edu.cloudpulse.api.support.StubHttpServer.Response;
import edu.cloudpulse.api.support.TestRules;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Prometheus adapter against a real HTTP server (JDK built-in), no Spring context. */
class PrometheusClientTest {

  private static final String VECTOR =
      "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":"
          + "[{\"metric\":{},\"value\":[1760000000.123,\"%s\"]}]},\"stats\":{\"ignored\":true}}";

  private StubHttpServer server;
  private PrometheusClient client;

  @BeforeEach
  void setUp() {
    server = StubHttpServer.start();
    client = clientFor(server.baseUrl());
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private static PrometheusClient clientFor(String url) {
    CloudPulseProperties properties =
        new CloudPulseProperties(
            null,
            null,
            new CloudPulseProperties.Prometheus(
                URI.create(url), null, Duration.ofMillis(500), Duration.ofMillis(700)),
            null,
            null,
            null);
    return new PrometheusClient(properties);
  }

  @Test
  @DisplayName("FR-01: the exact PromQL reaches Prometheus and the value is parsed")
  void fr01_queryRoundTrip() {
    server.route("/api/v1/query", request -> Response.json(String.format(VECTOR, "1.25")));

    TelemetryReading reading = client.instant(TestRules.LATENCY_QUERY, Comparison.ABOVE);

    assertThat(reading.status()).isEqualTo(TelemetryStatus.OK);
    assertThat(reading.value()).isEqualTo(1.25);
    assertThat(server.requests().get(0).query().get("query")).isEqualTo(TestRules.LATENCY_QUERY);
  }

  @Test
  @DisplayName("NFR-04: NaN from Prometheus (no traffic) is NO_DATA, not 0 s")
  void nfr04_nanIsNoData() {
    server.route("/api/v1/query", request -> Response.json(String.format(VECTOR, "NaN")));

    assertThat(client.instant("q", Comparison.ABOVE).status()).isEqualTo(TelemetryStatus.NO_DATA);
  }

  @Test
  @DisplayName("NFR-04: HTTP 503 from Prometheus is UNAVAILABLE")
  void nfr04_serverErrorIsUnavailable() {
    server.route("/api/v1/query", request -> Response.status(503));

    TelemetryReading reading = client.instant("q", Comparison.ABOVE);
    assertThat(reading.status()).isEqualTo(TelemetryStatus.UNAVAILABLE);
    assertThat(reading.value()).isNull();
    assertThat(reading.detail()).contains("503");
  }

  @Test
  @DisplayName("NFR-04: Prometheus not running is UNAVAILABLE")
  void nfr04_connectionRefusedIsUnavailable() {
    String url = server.baseUrl();
    server.close();

    assertThat(clientFor(url).instant("q", Comparison.ABOVE).status())
        .isEqualTo(TelemetryStatus.UNAVAILABLE);
  }

  @Test
  @DisplayName("NFR-04: a hung Prometheus hits the read timeout instead of stalling detection")
  void nfr04_slowPrometheusTimesOut() {
    server.route(
        "/api/v1/query", request -> Response.json(String.format(VECTOR, "1.0")).delayedBy(2_000));

    long started = System.nanoTime();
    TelemetryReading reading = client.instant("q", Comparison.ABOVE);
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;

    assertThat(reading.status()).isEqualTo(TelemetryStatus.UNAVAILABLE);
    assertThat(elapsedMs).isLessThan(1_800);
  }

  @Test
  @DisplayName("FR-06: range queries return chart points, and fail loudly when Prometheus is down")
  void fr06_rangeQuery() {
    server.route(
        "/api/v1/query_range",
        request ->
            Response.json(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},"
                    + "\"values\":[[1760000000,\"0.041\"],[1760000005,\"0.052\"]]}]}}"));

    List<SeriesPoint> points =
        client.range("q", Instant.ofEpochSecond(1_759_999_700), Instant.ofEpochSecond(1_760_000_005),
            Duration.ofSeconds(5));
    assertThat(points).hasSize(2);
    assertThat(points.get(1).v()).isEqualTo(0.052);

    server.route("/api/v1/query_range", request -> Response.status(503));
    assertThatThrownBy(
            () -> client.range("q", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), Duration.ofSeconds(5)))
        .isInstanceOf(TelemetryUnavailableException.class);
  }
}
