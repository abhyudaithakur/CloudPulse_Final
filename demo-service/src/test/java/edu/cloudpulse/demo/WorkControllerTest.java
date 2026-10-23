package edu.cloudpulse.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WorkControllerTest {

  /** A dependency the test controls: healthy, or failing like a payment service under fault. */
  private static final class FakeDownstream implements Downstream {
    private final boolean configured;
    volatile boolean failing;
    final AtomicInteger calls = new AtomicInteger();

    FakeDownstream(boolean configured) {
      this.configured = configured;
    }

    @Override
    public boolean configured() {
      return configured;
    }

    @Override
    public String name() {
      return "payment-api";
    }

    @Override
    public void call() {
      calls.incrementAndGet();
      if (failing) {
        throw new DownstreamFailure("payment-api failed: HttpServerErrorException", null);
      }
    }
  }

  private final FakeDownstream leaf = new FakeDownstream(false);
  private final WorkController controller = new WorkController("payment-api", leaf);

  private static int status(WorkController controller) {
    try {
      return controller.work().getStatusCode().value();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("FR-09/UC-06: error-storm fails exactly 3 of every 10 requests, deterministically")
  void fr09_errorStormIsDeterministic() {
    controller.setFault(Map.of("mode", "error-storm"));

    long failures = IntStream.range(0, 20).map(i -> status(controller)).filter(s -> s == 500).count();

    assertThat(failures).isEqualTo(6);
  }

  @Test
  @DisplayName("FR-09/UC-06: clearing the fault returns every request to 200")
  void uc06_faultCanBeCleared() {
    controller.setFault(Map.of("mode", "error-storm"));
    controller.setFault(Map.of("mode", "none"));

    assertThat(IntStream.range(0, 10).map(i -> status(controller)).allMatch(s -> s == 200)).isTrue();
    assertThat(controller.currentFault()).containsEntry("faultMode", "none");
  }

  @Test
  @DisplayName("UC-06: unknown fault modes are rejected with 400")
  void uc06_unknownModeRejected() {
    assertThatThrownBy(() -> controller.setFault(Map.of("mode", "meltdown")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  @DisplayName("FR-09: each latency mode has the documented delay")
  void fr09_latencyModes() {
    assertThat(FaultMode.fromId("high-latency")).contains(FaultMode.HIGH_LATENCY);
    assertThat(FaultMode.HIGH_LATENCY.delay().toMillis()).isEqualTo(1400);
    assertThat(FaultMode.EXTREME_LATENCY.delay().toMillis()).isEqualTo(2200);
    assertThat(FaultMode.NONE.delay().toMillis()).isEqualTo(40);
    assertThat(FaultMode.fromId(" Error-Storm ")).contains(FaultMode.ERROR_STORM);
  }

  @Test
  @DisplayName("FR-09: a stuck workload cannot be cleared through the API; only a restart clears it")
  void fr09_stuckNeedsARestart() {
    controller.setFault(Map.of("mode", "stuck"));

    assertThat(controller.currentFault()).containsEntry("needsRestart", true);
    assertThatThrownBy(() -> controller.setFault(Map.of("mode", "none")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(FaultMode.STUCK.delay().toMillis()).isEqualTo(3000);

    // A restart is a new process: a fresh controller starts healthy.
    assertThat(new WorkController("payment-api", leaf).currentFault()).containsEntry("faultMode", "none");
  }

  @Test
  @DisplayName("FR-08: checkout calls payment on every request, and a payment failure becomes a 502")
  void fr08_dependencyFailurePropagatesUpstream() {
    FakeDownstream payment = new FakeDownstream(true);
    WorkController checkout = new WorkController("checkout-api", payment);

    assertThat(status(checkout)).isEqualTo(200);
    assertThat(payment.calls).hasValue(1);

    payment.failing = true;
    assertThat(status(checkout)).isEqualTo(502);
  }

  @Test
  @DisplayName("FR-08: a leaf service makes no downstream call")
  void fr08_leafServiceHasNoDependency() {
    assertThat(status(controller)).isEqualTo(200);
    assertThat(leaf.calls).hasValue(0);
  }
}
