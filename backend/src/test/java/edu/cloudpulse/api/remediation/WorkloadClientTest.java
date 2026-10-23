package edu.cloudpulse.api.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cloudpulse.api.remediation.WorkloadClient.RolloutState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkloadClientTest {

  @Test
  @DisplayName("FR-12: a rollout is complete only when every pod is new and ready (kubectl rollout status)")
  void fr12_rolloutCompleteness() {
    assertThat(new RolloutState(3, 3, 1, 1, 1, 1).complete()).isTrue();
    assertThat(new RolloutState(3, 2, 1, 1, 1, 1).complete()).as("controller has not seen it").isFalse();
    assertThat(new RolloutState(3, 3, 1, 1, 0, 1).complete()).as("new pod not ready").isFalse();
    assertThat(new RolloutState(3, 3, 1, 1, 1, 2).complete()).as("old pod still running").isFalse();
    assertThat(new RolloutState(3, 3, 2, 1, 1, 1).complete()).as("not all replicas updated").isFalse();
  }

  @Test
  @DisplayName("NFR-03: the Kubernetes error message is shown, not a raw response body")
  void nfr03_statusMessageExtracted() {
    String body =
        "{\"kind\":\"Status\",\"status\":\"Failure\",\"message\":\"deployments.apps \\\"x\\\" is"
            + " forbidden\",\"reason\":\"Forbidden\",\"code\":403}";
    assertThat(KubernetesWorkloadClient.statusMessage(body)).isEqualTo("deployments.apps \"x\" is forbidden");
    assertThat(KubernetesWorkloadClient.statusMessage("")).isEqualTo("no details");
    assertThat(KubernetesWorkloadClient.statusMessage("plain text")).isEqualTo("plain text");
  }

  @Test
  @DisplayName("FR-10: in Docker Compose mode the workload client refuses every change")
  void fr10_disabledClientRefuses() {
    DisabledWorkloadClient client = new DisabledWorkloadClient();
    assertThat(client.enabled()).isFalse();
    assertThatThrownBy(() -> client.restartDeployment("ns", "d", "now", "r"))
        .isInstanceOf(WorkloadClient.WorkloadException.class)
        .hasMessageContaining("not enabled");
  }
}
