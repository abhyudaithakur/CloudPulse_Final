package edu.cloudpulse.api.remediation;

/** Docker Compose mode: there is no cluster, so every change is refused. */
public final class DisabledWorkloadClient implements WorkloadClient {

  @Override
  public boolean enabled() {
    return false;
  }

  @Override
  public long restartDeployment(String namespace, String deployment, String restartedAt, String reason) {
    throw new WorkloadException(0, "Kubernetes remediation is not enabled in this deployment");
  }

  @Override
  public RolloutState rolloutState(String namespace, String deployment) {
    throw new WorkloadException(0, "Kubernetes remediation is not enabled in this deployment");
  }
}
