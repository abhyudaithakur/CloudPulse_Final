package edu.cloudpulse.api.demo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.cloudpulse.api.config.HttpClients;
import edu.cloudpulse.api.domain.MonitoredService;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Talks to a demo service's fault endpoint. Each demo service is the single source of truth for
 * its own active fault; the API keeps no copy that could drift.
 */
@Component
public class DemoServiceClient {

  private final RestClient http = HttpClients.restClient(Duration.ofSeconds(1), Duration.ofSeconds(1));

  /** Current fault, or {@code reachable=false} when the service does not answer. */
  public FaultState current(MonitoredService service) {
    try {
      FaultResponse body = http.get().uri(endpoint(service)).retrieve().body(FaultResponse.class);
      return body == null
          ? FaultState.reachable(service.name(), "unknown", false)
          : FaultState.reachable(service.name(), body.faultMode(), body.needsRestart());
    } catch (RuntimeException e) {
      return FaultState.unreachable(service.name());
    }
  }

  /**
   * Applies a fault mode.
   *
   * @throws FaultRefusedException when the service refuses (a stuck service cannot be cleared)
   * @throws RuntimeException when the service cannot be reached
   */
  public FaultState apply(MonitoredService service, String mode) {
    try {
      FaultResponse body =
          http.post()
              .uri(endpoint(service))
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("mode", mode))
              .retrieve()
              .body(FaultResponse.class);
      return body == null
          ? FaultState.reachable(service.name(), mode, false)
          : FaultState.reachable(service.name(), body.faultMode(), body.needsRestart());
    } catch (HttpClientErrorException.Conflict e) {
      throw new FaultRefusedException(
          service.name() + " is stuck: the process is wedged and only a restart clears it.");
    }
  }

  private static URI endpoint(MonitoredService service) {
    String base = service.url().toString();
    return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/api/fault");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FaultResponse(String service, String faultMode, boolean needsRestart) {}

  /** A service's fault mode as the dashboard shows it. */
  public record FaultState(String service, String mode, boolean reachable, boolean needsRestart) {
    static FaultState reachable(String service, String mode, boolean needsRestart) {
      return new FaultState(service, mode, true, needsRestart);
    }

    static FaultState unreachable(String service) {
      return new FaultState(service, "unknown", false, false);
    }
  }

  /** The demo service refused the change (HTTP 409). */
  public static class FaultRefusedException extends RuntimeException {
    public FaultRefusedException(String message) {
      super(message);
    }
  }
}
