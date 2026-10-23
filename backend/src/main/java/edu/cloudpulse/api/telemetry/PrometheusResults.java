package edu.cloudpulse.api.telemetry;

import edu.cloudpulse.api.domain.Comparison;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Pure helpers for building Prometheus URIs and interpreting responses. */
public final class PrometheusResults {

  private PrometheusResults() {}

  /**
   * Builds {@code /api/v1/query?query=...}. The query is percent-encoded here and passed to the
   * HTTP client as a finished {@link URI}, so the client cannot re-encode it or mistake PromQL label
   * braces for URI template variables (the cause of the midpoint's "p95 shows 0" bug).
   */
  public static URI instantQueryUri(URI baseUrl, String promql) {
    return URI.create(base(baseUrl) + "/api/v1/query?query=" + encode(promql));
  }

  public static URI rangeQueryUri(
      URI baseUrl, String promql, Instant start, Instant end, Duration step) {
    return URI.create(
        base(baseUrl)
            + "/api/v1/query_range?query="
            + encode(promql)
            + "&start="
            + start.getEpochSecond()
            + "&end="
            + end.getEpochSecond()
            + "&step="
            + Math.max(1, step.toSeconds())
            + "s");
  }

  /**
   * Interprets an instant-query response for a rule. An empty vector or a non-finite value is
   * NO_DATA, never zero: zero latency would look healthy and could wrongly resolve an incident.
   * Rule queries should aggregate to one series; if several come back, the one furthest into
   * violation (per {@code comparison}) is used.
   */
  public static TelemetryReading toReading(
      PrometheusQueryResponse response, Comparison comparison) {
    if (response == null) {
      return TelemetryReading.unavailable("Prometheus returned an empty body");
    }
    if (!"success".equals(response.status())) {
      return TelemetryReading.unavailable(
          "Prometheus query failed: " + response.errorType() + " " + response.error());
    }
    if (response.data() == null || response.data().result() == null) {
      return TelemetryReading.unavailable("Prometheus response had no data section");
    }
    List<PrometheusQueryResponse.Sample> samples = response.data().result();
    if (samples.isEmpty()) {
      return TelemetryReading.noData("query returned no series");
    }
    Double worst = null;
    for (PrometheusQueryResponse.Sample sample : samples) {
      Double value = parseSampleValue(sample.value());
      if (value == null) {
        continue;
      }
      if (worst == null || comparison.isWorse(value, worst)) {
        worst = value;
      }
    }
    if (worst == null) {
      return TelemetryReading.noData("query returned NaN or Inf (no requests in the window?)");
    }
    return TelemetryReading.ok(worst);
  }

  /** Converts a range-query matrix to points; non-finite samples become gaps (null). */
  public static List<SeriesPoint> toSeries(PrometheusQueryResponse response) {
    List<SeriesPoint> points = new ArrayList<>();
    if (response == null
        || !"success".equals(response.status())
        || response.data() == null
        || response.data().result() == null
        || response.data().result().isEmpty()) {
      return points;
    }
    List<List<Object>> values = response.data().result().get(0).values();
    if (values == null) {
      return points;
    }
    for (List<Object> pair : values) {
      Instant at = parseTimestamp(pair);
      if (at == null) {
        continue;
      }
      points.add(new SeriesPoint(at, parseSampleValue(pair)));
    }
    return points;
  }

  /** Returns the finite value of a {@code [timestamp, "value"]} pair, or null for NaN/Inf/garbage. */
  static Double parseSampleValue(List<Object> pair) {
    if (pair == null || pair.size() < 2 || pair.get(1) == null) {
      return null;
    }
    String text = String.valueOf(pair.get(1)).trim();
    if (text.equalsIgnoreCase("NaN") || text.endsWith("Inf")) {
      return null;
    }
    try {
      double value = Double.parseDouble(text);
      return Double.isFinite(value) ? value : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Instant parseTimestamp(List<Object> pair) {
    if (pair == null || pair.isEmpty() || !(pair.get(0) instanceof Number seconds)) {
      return null;
    }
    return Instant.ofEpochMilli(Math.round(seconds.doubleValue() * 1000));
  }

  private static String base(URI baseUrl) {
    String text = baseUrl.toString();
    return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
  }

  private static String encode(String promql) {
    return URLEncoder.encode(promql, StandardCharsets.UTF_8);
  }
}
