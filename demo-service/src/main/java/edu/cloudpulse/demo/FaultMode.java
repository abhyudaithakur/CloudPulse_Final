package edu.cloudpulse.demo;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Controlled, repeatable faults (UC-06, FR-09). Errors are deterministic, not random: in
 * error-storm mode exactly 3 of every 10 requests fail, so every run produces the same signal.
 *
 * <p>{@link #STUCK} models a workload failure (FR-09's third class): the process is wedged, every
 * request takes 3 s, and the fault cannot be cleared through the API. Only restarting the process
 * (a new pod or container) clears it, which is what CloudPulse's guarded restart runbook is for.
 */
public enum FaultMode {
  NONE("none", Duration.ofMillis(40), 0, false),
  HIGH_LATENCY("high-latency", Duration.ofMillis(1400), 0, false),
  EXTREME_LATENCY("extreme-latency", Duration.ofMillis(2200), 0, false),
  ERROR_STORM("error-storm", Duration.ofMillis(40), 3, false),
  STUCK("stuck", Duration.ofMillis(3000), 0, true);

  private final String id;
  private final Duration delay;
  private final int failuresPerTen;
  private final boolean needsRestart;

  FaultMode(String id, Duration delay, int failuresPerTen, boolean needsRestart) {
    this.id = id;
    this.delay = delay;
    this.failuresPerTen = failuresPerTen;
    this.needsRestart = needsRestart;
  }

  public String id() {
    return id;
  }

  public Duration delay() {
    return delay;
  }

  /** True when the mode can only be cleared by restarting the process. */
  public boolean needsRestart() {
    return needsRestart;
  }

  /** Whether request number {@code n} (1-based) should return HTTP 500 in this mode. */
  public boolean shouldFail(long n) {
    return failuresPerTen > 0 && Math.floorMod(n, 10) < failuresPerTen;
  }

  public static Optional<FaultMode> fromId(String id) {
    if (id == null) {
      return Optional.empty();
    }
    String normalized = id.trim().toLowerCase(Locale.ROOT);
    return Arrays.stream(values()).filter(mode -> mode.id.equals(normalized)).findFirst();
  }

  public static List<String> ids() {
    return Arrays.stream(values()).map(FaultMode::id).toList();
  }
}
