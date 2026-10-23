package edu.cloudpulse.api.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cloudpulse.api.domain.Comparison;
import edu.cloudpulse.api.support.TestRules;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrometheusResultsTest {

  private static PrometheusQueryResponse vector(Object... values) {
    List<PrometheusQueryResponse.Sample> samples =
        java.util.Arrays.stream(values)
            .map(v -> new PrometheusQueryResponse.Sample(Map.of(), List.of(1_760_000_000.5, v), null))
            .toList();
    return new PrometheusQueryResponse(
        "success", new PrometheusQueryResponse.Data("vector", samples), null, null);
  }

  @Test
  @DisplayName("FR-01: a finite instant value becomes an OK reading")
  void fr01_finiteValueIsOk() {
    TelemetryReading reading = PrometheusResults.toReading(vector("1.432"), Comparison.ABOVE);

    assertThat(reading.status()).isEqualTo(TelemetryStatus.OK);
    assertThat(reading.value()).isEqualTo(1.432);
  }

  @Test
  @DisplayName("NFR-04: an empty vector is NO_DATA, never zero")
  void nfr04_emptyResultIsNoData() {
    TelemetryReading reading = PrometheusResults.toReading(vector(), Comparison.ABOVE);

    assertThat(reading.status()).isEqualTo(TelemetryStatus.NO_DATA);
    assertThat(reading.value()).isNull();
  }

  @Test
  @DisplayName("NFR-04: NaN and Inf (e.g. no requests in the window) are NO_DATA, never zero")
  void nfr04_nanAndInfAreNoData() {
    assertThat(PrometheusResults.toReading(vector("NaN"), Comparison.ABOVE).status())
        .isEqualTo(TelemetryStatus.NO_DATA);
    assertThat(PrometheusResults.toReading(vector("+Inf"), Comparison.ABOVE).status())
        .isEqualTo(TelemetryStatus.NO_DATA);
  }

  @Test
  @DisplayName("NFR-04: a Prometheus error envelope is UNAVAILABLE")
  void nfr04_errorEnvelopeIsUnavailable() {
    PrometheusQueryResponse error =
        new PrometheusQueryResponse("error", null, "bad_data", "parse error at char 5");

    TelemetryReading reading = PrometheusResults.toReading(error, Comparison.ABOVE);
    assertThat(reading.status()).isEqualTo(TelemetryStatus.UNAVAILABLE);
    assertThat(reading.detail()).contains("parse error");
    assertThat(PrometheusResults.toReading(null, Comparison.ABOVE).status())
        .isEqualTo(TelemetryStatus.UNAVAILABLE);
  }

  @Test
  @DisplayName("FR-01: with several series, the one furthest into violation is used")
  void fr01_multipleSeriesUseWorstValue() {
    assertThat(PrometheusResults.toReading(vector("0.2", "1.7", "NaN"), Comparison.ABOVE).value())
        .isEqualTo(1.7);
    assertThat(PrometheusResults.toReading(vector("1", "0"), Comparison.BELOW).value())
        .isEqualTo(0.0);
  }

  @Test
  @DisplayName("FR-01: PromQL is fully percent-encoded so label braces survive the HTTP client")
  void fr01_queryIsEncodedIntoUri() {
    URI uri =
        PrometheusResults.instantQueryUri(URI.create("http://prometheus:9090/"), TestRules.LATENCY_QUERY);

    assertThat(uri.toString()).startsWith("http://prometheus:9090/api/v1/query?query=");
    assertThat(uri.getRawQuery()).doesNotContain("{");
    String decoded =
        URLDecoder.decode(uri.getRawQuery().substring("query=".length()), StandardCharsets.UTF_8);
    assertThat(decoded).isEqualTo(TestRules.LATENCY_QUERY);
  }

  @Test
  @DisplayName("FR-06: range results become chart points with gaps for NaN")
  void fr06_rangeResultBecomesPointsWithGaps() {
    PrometheusQueryResponse.Sample series =
        new PrometheusQueryResponse.Sample(
            Map.of(),
            null,
            List.of(List.of(1_760_000_000, "0.04"), List.of(1_760_000_005, "NaN")));
    PrometheusQueryResponse matrix =
        new PrometheusQueryResponse(
            "success", new PrometheusQueryResponse.Data("matrix", List.of(series)), null, null);

    List<SeriesPoint> points = PrometheusResults.toSeries(matrix);
    assertThat(points).hasSize(2);
    assertThat(points.get(0).v()).isEqualTo(0.04);
    assertThat(points.get(0).t()).isEqualTo(Instant.ofEpochSecond(1_760_000_000));
    assertThat(points.get(1).v()).isNull();

    URI range =
        PrometheusResults.rangeQueryUri(
            URI.create("http://p:9090"), "up", Instant.ofEpochSecond(100), Instant.ofEpochSecond(400),
            Duration.ofSeconds(5));
    assertThat(range.toString()).isEqualTo("http://p:9090/api/v1/query_range?query=up&start=100&end=400&step=5s");
  }
}
