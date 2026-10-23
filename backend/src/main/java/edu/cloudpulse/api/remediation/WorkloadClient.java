package edu.cloudpulse.api.remediation;

/**
 * The only way CloudPulse can change running workloads (FR-10). Two calls, both scoped to one
 * namespace and one deployment: a rolling restart, and reading how far that restart has rolled
 * out. Kubernetes RBAC limits the real identity behind it to exactly these calls (NFR-03).
 */
public interface WorkloadClient {

  /** False in Docker Compose mode, where there is no cluster to change. */
  boolean enabled();

  /**
   * Starts a rolling restart, the same change {@code kubectl rollout restart} makes: a new
   * annotation on the pod template makes Kubernetes replace the pods one at a time.
   *
   * @return the deployment's new {@code metadata.generation}, used to recognise this rollout
   * @throws WorkloadException if the cluster rejects the request or cannot be reached
   */
  long restartDeployment(String namespace, String deployment, String restartedAt, String reason);

  /** Reads the deployment's rollout progress. */
  RolloutState rolloutState(String namespace, String deployment);

  /**
   * @param generation desired state version ({@code metadata.generation})
   * @param observedGeneration the version the deployment controller has acted on
   * @param desired replicas the deployment asks for
   * @param updated pods already running the new template
   * @param available updated pods that are ready
   * @param total all pods, old and new
   */
  record RolloutState(
      long generation, long observedGeneration, int desired, int updated, int available, int total) {

    /** Same test as {@code kubectl rollout status}: every pod is new and ready, no old ones left. */
    public boolean complete() {
      return observedGeneration >= generation
          && updated >= desired
          && available >= desired
          && total <= updated;
    }
  }

  /** The cluster refused or could not be reached. {@code status} is the HTTP status, 0 if none. */
  class WorkloadException extends RuntimeException {
    private final int status;

    public WorkloadException(int status, String message) {
      super(message);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
