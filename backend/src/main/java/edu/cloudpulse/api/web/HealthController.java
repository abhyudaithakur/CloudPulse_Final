package edu.cloudpulse.api.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Simple liveness endpoint kept for scripts; Kubernetes probes use /actuator/health/*. */
@RestController
public class HealthController {

  @GetMapping("/api/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "application", "cloudpulse-api");
  }
}
