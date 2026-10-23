package edu.cloudpulse.demo;

/**
 * A service this one calls on every request (checkout-api calls payment-api). Its latency adds
 * to ours and its failures become ours, which is exactly the propagation CloudPulse's probable-
 * source correlation reasons about (RAD FR-08).
 */
public interface Downstream {

  /** True when a dependency is configured; a leaf service (payment-api) has none. */
  boolean configured();

  /** Name used in responses and error messages. */
  String name();

  /** Calls the dependency; returns on 2xx, throws {@link DownstreamFailure} otherwise. */
  void call();

  /** The dependency answered with an error or could not be reached. */
  class DownstreamFailure extends RuntimeException {
    public DownstreamFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
