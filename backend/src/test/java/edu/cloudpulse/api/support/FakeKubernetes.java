package edu.cloudpulse.api.support;

import edu.cloudpulse.api.support.StubHttpServer.Response;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for the two Kubernetes API calls CloudPulse makes (PATCH and GET of a Deployment in
 * namespace {@code cloudpulse-demo}). Tests choose whether a restart is accepted, rejected by
 * RBAC, or never finishes rolling out.
 */
public final class FakeKubernetes {

  public static final String NAMESPACE = "cloudpulse-demo";

  /** Simulated state of one Deployment. */
  public static final class FakeDeployment {
    public volatile long generation = 1;
    public volatile long observedGeneration = 1;
    public volatile boolean rolledOut = true;
    public volatile int patches;
  }

  /** When true, PATCH is answered 403 with the Status body the real API server sends. */
  public volatile boolean forbidden;

  /** When false, a restart is accepted but its rollout never completes. */
  public volatile boolean rolloutCompletes = true;

  /** Called after an accepted PATCH, e.g. to clear the demo service's fault like a restart would. */
  public volatile java.util.function.Consumer<String> onRestart = name -> {};

  private final Map<String, FakeDeployment> deployments = new ConcurrentHashMap<>();

  public FakeKubernetes() {
    reset();
  }

  public void reset() {
    forbidden = false;
    rolloutCompletes = true;
    onRestart = name -> {};
    deployments.put("checkout-api", new FakeDeployment());
    deployments.put("payment-api", new FakeDeployment());
  }

  public FakeDeployment deployment(String name) {
    return deployments.get(name);
  }

  public static String path(String deployment) {
    return "/apis/apps/v1/namespaces/" + NAMESPACE + "/deployments/" + deployment;
  }

  public void install(StubHttpServer server) {
    for (String name : deployments.keySet()) {
      server.route(path(name), request -> handle(name, request));
    }
  }

  private Response handle(String name, StubHttpServer.Request request) {
    FakeDeployment d = deployments.get(name);
    if ("PATCH".equals(request.method())) {
      if (forbidden) {
        return Response.of(
            403,
            "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"message\":"
                + "\"deployments.apps \\\""
                + name
                + "\\\" is forbidden: User \\\"system:serviceaccount:cloudpulse:cloudpulse-api\\\""
                + " cannot patch resource \\\"deployments\\\"\",\"reason\":\"Forbidden\",\"code\":403}");
      }
      d.patches++;
      d.generation++;
      d.rolledOut = false;
      onRestart.accept(name);
      return Response.json(body(name, d));
    }
    if (rolloutCompletes) {
      d.observedGeneration = d.generation;
      d.rolledOut = true;
    }
    return Response.json(body(name, d));
  }

  private static String body(String name, FakeDeployment d) {
    int updated = d.rolledOut ? 1 : 0;
    int total = d.rolledOut ? 1 : 2;
    return "{\"kind\":\"Deployment\",\"metadata\":{\"name\":\""
        + name
        + "\",\"namespace\":\""
        + NAMESPACE
        + "\",\"generation\":"
        + d.generation
        + "},\"spec\":{\"replicas\":1},\"status\":{\"observedGeneration\":"
        + d.observedGeneration
        + ",\"replicas\":"
        + total
        + ",\"updatedReplicas\":"
        + updated
        + ",\"readyReplicas\":1,\"availableReplicas\":"
        + updated
        + "}}";
  }
}
