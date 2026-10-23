package edu.cloudpulse.api.remediation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.cloudpulse.api.config.CloudPulseProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Talks to the Kubernetes API directly over HTTPS, the way any in-cluster client does: the pod's
 * service account token as a bearer token, and the cluster CA certificate to trust the API
 * server. No Kubernetes client library: two REST calls are easier to read, test and explain.
 *
 * <pre>
 * PATCH /apis/apps/v1/namespaces/{ns}/deployments/{name}   (strategic merge patch: restartedAt)
 * GET   /apis/apps/v1/namespaces/{ns}/deployments/{name}   (rollout status)
 * </pre>
 */
public final class KubernetesWorkloadClient implements WorkloadClient {

  private static final MediaType MERGE_PATCH =
      MediaType.parseMediaType("application/strategic-merge-patch+json");
  private static final Pattern STATUS_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  private final URI apiUrl;
  private final Path tokenPath;
  private final RestClient http;

  public KubernetesWorkloadClient(CloudPulseProperties.Kubernetes config) {
    this.apiUrl = config.apiUrl();
    this.tokenPath = Path.of(config.tokenPath());
    HttpClient.Builder builder =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(config.timeout());
    Path ca = Path.of(config.caPath());
    if ("https".equalsIgnoreCase(apiUrl.getScheme()) && Files.isReadable(ca)) {
      builder.sslContext(trusting(ca));
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
    factory.setReadTimeout(config.timeout());
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public long restartDeployment(String namespace, String deployment, String restartedAt, String reason) {
    Map<String, Object> patch =
        Map.of(
            "spec",
            Map.of(
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        Map.of(
                            "kubectl.kubernetes.io/restartedAt", restartedAt,
                            "cloudpulse.io/remediation", reason)))));
    Deployment result =
        call(
            () ->
                http.patch()
                    .uri(deploymentUri(namespace, deployment))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .contentType(MERGE_PATCH)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(patch)
                    .retrieve()
                    .body(Deployment.class));
    return result == null || result.metadata() == null || result.metadata().generation() == null
        ? 0
        : result.metadata().generation();
  }

  @Override
  public RolloutState rolloutState(String namespace, String deployment) {
    Deployment d =
        call(
            () ->
                http.get()
                    .uri(deploymentUri(namespace, deployment))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Deployment.class));
    if (d == null) {
      throw new WorkloadException(0, "empty response for deployment " + namespace + "/" + deployment);
    }
    long generation = d.metadata() == null || d.metadata().generation() == null ? 0 : d.metadata().generation();
    int desired = d.spec() == null || d.spec().replicas() == null ? 1 : d.spec().replicas();
    Deployment.Status s = d.status() == null ? new Deployment.Status(null, null, null, null) : d.status();
    return new RolloutState(
        generation,
        s.observedGeneration() == null ? 0 : s.observedGeneration(),
        desired,
        s.updatedReplicas() == null ? 0 : s.updatedReplicas(),
        s.availableReplicas() == null ? 0 : s.availableReplicas(),
        s.replicas() == null ? 0 : s.replicas());
  }

  private URI deploymentUri(String namespace, String deployment) {
    String base = apiUrl.toString().replaceAll("/+$", "");
    return URI.create(base + "/apis/apps/v1/namespaces/" + namespace + "/deployments/" + deployment);
  }

  private String token() {
    try {
      // Read on every call: Kubernetes rotates projected service account tokens.
      return Files.readString(tokenPath).trim();
    } catch (IOException e) {
      throw new WorkloadException(0, "no service account token at " + tokenPath);
    }
  }

  private static <T> T call(java.util.function.Supplier<T> request) {
    try {
      return request.get();
    } catch (RestClientResponseException e) {
      throw new WorkloadException(
          e.getStatusCode().value(),
          "HTTP " + e.getStatusCode().value() + ": " + statusMessage(e.getResponseBodyAsString()));
    } catch (WorkloadException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new WorkloadException(0, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** The human-readable {@code message} of a Kubernetes {@code Status} error body. */
  static String statusMessage(String body) {
    if (body == null || body.isBlank()) {
      return "no details";
    }
    Matcher m = STATUS_MESSAGE.matcher(body);
    String message = m.find() ? m.group(1).replace("\\\"", "\"") : body;
    return message.length() > 400 ? message.substring(0, 400) + "…" : message;
  }

  private static SSLContext trusting(Path caFile) {
    try (InputStream in = Files.newInputStream(caFile)) {
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      int i = 0;
      for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
        store.setCertificateEntry("cluster-ca-" + i++, certificate);
      }
      TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(store);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return context;
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException("cannot load the cluster CA from " + caFile, e);
    }
  }

  /** The parts of an apps/v1 Deployment CloudPulse reads. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Deployment(Metadata metadata, Spec spec, Status status) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(String name, Long generation) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(Integer replicas) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
        Long observedGeneration, Integer replicas, Integer updatedReplicas, Integer availableReplicas) {}
  }
}
