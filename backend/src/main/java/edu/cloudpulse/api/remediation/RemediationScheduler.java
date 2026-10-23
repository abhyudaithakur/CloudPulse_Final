package edu.cloudpulse.api.remediation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances approved remediations every 2 s. Disabled with the rest of the scheduling in
 * integration tests, which call {@link RemediationService#tick()} directly.
 */
@Component
@ConditionalOnProperty(
    prefix = "cloudpulse.scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RemediationScheduler {

  private final RemediationService remediation;

  public RemediationScheduler(RemediationService remediation) {
    this.remediation = remediation;
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 5000)
  public void tick() {
    remediation.tick();
  }
}
