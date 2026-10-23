package edu.cloudpulse.api.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Subset of the Prometheus HTTP API response envelope used by CloudPulse.
 *
 * <p>Instant queries return {@code result[i].value = [unixSeconds, "value"]}; range queries return
 * {@code result[i].values = [[unixSeconds, "value"], ...]}. Prometheus encodes sample values as
 * strings so that NaN and ±Inf survive JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrometheusQueryResponse(String status, Data data, String errorType, String error) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(String resultType, List<Sample> result) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Sample(Map<String, String> metric, List<Object> value, List<List<Object>> values) {}
}
