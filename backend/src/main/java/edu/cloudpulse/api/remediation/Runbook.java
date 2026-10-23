package edu.cloudpulse.api.remediation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Predefined remediations (FR-10). There is deliberately no way to run an arbitrary command: a
 * runbook is a fixed, reviewed action with a fixed target type.
 */
public enum Runbook {
  RESTART_DEPLOYMENT(
      "Restart deployment",
      "Rolling restart: Kubernetes replaces the pods one at a time, so the service keeps answering."
          + " Clears problems that live in a running process (a wedged thread pool, a leak).");

  private final String title;
  private final String description;

  Runbook(String title, String description) {
    this.title = title;
    this.description = description;
  }

  public String title() {
    return title;
  }

  public String description() {
    return description;
  }

  public static Optional<Runbook> fromId(String id) {
    if (id == null) {
      return Optional.empty();
    }
    String normalized = id.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(values()).filter(r -> r.name().equals(normalized)).findFirst();
  }
}
