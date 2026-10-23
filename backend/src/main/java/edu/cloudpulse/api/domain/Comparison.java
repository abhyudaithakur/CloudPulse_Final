package edu.cloudpulse.api.domain;

/** Direction in which a measurement violates a rule threshold. */
public enum Comparison {
  /** Violated when the value is at or above the threshold (latency, error rate). */
  ABOVE {
    @Override
    public boolean isBreaching(double value, double threshold) {
      return value >= threshold;
    }

    @Override
    public boolean isWorse(double candidate, double current) {
      return candidate > current;
    }

    @Override
    public String symbol() {
      return "≥";
    }
  },

  /** Violated when the value is strictly below the threshold (availability: up &lt; 1). */
  BELOW {
    @Override
    public boolean isBreaching(double value, double threshold) {
      return value < threshold;
    }

    @Override
    public boolean isWorse(double candidate, double current) {
      return candidate < current;
    }

    @Override
    public String symbol() {
      return "<";
    }
  };

  public abstract boolean isBreaching(double value, double threshold);

  /** True when {@code candidate} is further into violation than {@code current}. */
  public abstract boolean isWorse(double candidate, double current);

  public abstract String symbol();
}
