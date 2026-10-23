package edu.cloudpulse.api.detection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the engine on a fixed delay (FR-01). Disabled in integration tests, which call {@link
 * DetectionEngine#evaluateAll()} directly with a synthetic clock.
 */
@Component
@ConditionalOnProperty(
    prefix = "cloudpulse.scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DetectionScheduler {

  private final DetectionEngine engine;

  public DetectionScheduler(DetectionEngine engine) {
    this.engine = engine;
  }

  @Scheduled(
      fixedDelayString = "${cloudpulse.scheduling.interval-ms:5000}",
      initialDelayString = "${cloudpulse.scheduling.initial-delay-ms:3000}")
  public void tick() {
    engine.evaluateAll();
  }
}
