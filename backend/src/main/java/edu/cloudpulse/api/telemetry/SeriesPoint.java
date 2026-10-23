package edu.cloudpulse.api.telemetry;

import java.time.Instant;

/** One point of a metric history; {@code value} is null where Prometheus had no usable sample. */
public record SeriesPoint(Instant t, Double v) {}
