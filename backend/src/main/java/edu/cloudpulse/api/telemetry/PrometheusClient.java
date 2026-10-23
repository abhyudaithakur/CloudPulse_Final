package edu.cloudpulse.api.telemetry;

import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.config.HttpClients;
import edu.cloudpulse.api.domain.Comparison;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Telemetry module (RAD §Backend Modules): the only class that talks to Prometheus. Every failure
 * is converted to an explicit {@link TelemetryStatus}; nothing here ever fabricates a value.
 */
@Component
public class PrometheusClient {

  private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

  private final RestClient http;
  private final URI baseUrl;

  public PrometheusClient(CloudPulseProperties properties) {
    CloudPulseProperties.Prometheus prometheus = properties.prometheus();
    this.baseUrl = prometheus.url();
    this.http = HttpClients.restClient(prometheus.connectTimeout(), prometheus.readTimeout());
  }

  /** Evaluates an instant query for a rule. Never throws. */
  public TelemetryReading instant(String promql, Comparison comparison) {
    try {
      PrometheusQueryResponse body =
          http.get()
              .uri(PrometheusResults.instantQueryUri(baseUrl, promql))
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(PrometheusQueryResponse.class);
      return PrometheusResults.toReading(body, comparison);
    } catch (RuntimeException e) {
      String detail = describe(e);
      log.warn("Prometheus instant query failed: {}", detail);
      return TelemetryReading.unavailable(detail);
    }
  }

  /** Fetches history for charts. Throws {@link TelemetryUnavailableException} on failure. */
  public List<SeriesPoint> range(String promql, Instant start, Instant end, Duration step) {
    try {
      PrometheusQueryResponse body =
          http.get()
              .uri(PrometheusResults.rangeQueryUri(baseUrl, promql, start, end, step))
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(PrometheusQueryResponse.class);
      if (body == null || !"success".equals(body.status())) {
        throw new TelemetryUnavailableException(
            "Prometheus range query failed: " + (body == null ? "empty body" : body.error()));
      }
      return PrometheusResults.toSeries(body);
    } catch (TelemetryUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TelemetryUnavailableException(describe(e));
    }
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    if (message.length() > 300) {
      message = message.substring(0, 300) + "…";
    }
    return e.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
  }
}
