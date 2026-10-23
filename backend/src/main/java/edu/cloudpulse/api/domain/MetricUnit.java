package edu.cloudpulse.api.domain;

import java.util.Locale;

/** Unit of a rule's measurement; used for evidence text and by the dashboard for display. */
public enum MetricUnit {
  SECONDS {
    @Override
    public String format(double value) {
      return String.format(Locale.ROOT, "%.3f s", value);
    }
  },
  RATIO {
    @Override
    public String format(double value) {
      return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }
  },
  BOOLEAN {
    @Override
    public String format(double value) {
      return value >= 1 ? "up" : "down";
    }
  };

  public abstract String format(double value);
}
