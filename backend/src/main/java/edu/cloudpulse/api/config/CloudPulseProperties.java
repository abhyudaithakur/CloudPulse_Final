package edu.cloudpulse.api.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the {@code cloudpulse.*} configuration (NFR-10: interval, monitored services,
 * thresholds, windows and the remediation namespace are configuration, not code). Defaults live in
 * {@code application.yml}; the constructors below only fill gaps so a partial override cannot null
 * out a section.
 */
@ConfigurationProperties(prefix = "cloudpulse")
public record CloudPulseProperties(
    Scheduling scheduling,
    Detection detection,
    Prometheus prometheus,
    List<ServiceProperties> services,
    List<RuleProperties> ruleTemplates,
    Remediation remediation) {

  public CloudPulseProperties {
    scheduling = scheduling == null ? new Scheduling(null, null) : scheduling;
    detection = detection == null ? new Detection(null, null, null) : detection;
    prometheus = prometheus == null ? new Prometheus(null, null, null, null) : prometheus;
    services = services == null ? List.of() : List.copyOf(services);
    ruleTemplates = ruleTemplates == null ? List.of() : List.copyOf(ruleTemplates);
    remediation = remediation == null ? Remediation.disabled() : remediation;
  }

  /** How often the detection engine evaluates every rule (FR-01). */
  public record Scheduling(Boolean enabled, Long intervalMs) {
    public Scheduling {
      enabled = enabled == null || enabled;
      intervalMs = intervalMs == null ? 5000L : intervalMs;
    }
  }

  /**
   * @param maxSampleGap two usable samples further apart than this break a sustain/recovery
   *     window, because the rule cannot claim the condition held in between
   * @param evidenceSampleInterval how often an ongoing breach adds a SAMPLE evidence row
   * @param correlationTolerance how much later than the affected service a dependency's problem may
   *     start and still be named its probable source (FR-08)
   */
  public record Detection(
      Duration maxSampleGap, Duration evidenceSampleInterval, Duration correlationTolerance) {
    public Detection {
      maxSampleGap = maxSampleGap == null ? Duration.ofSeconds(20) : maxSampleGap;
      evidenceSampleInterval =
          evidenceSampleInterval == null ? Duration.ofSeconds(30) : evidenceSampleInterval;
      correlationTolerance =
          correlationTolerance == null ? Duration.ofSeconds(30) : correlationTolerance;
    }
  }

  /**
   * @param url where the API queries Prometheus
   * @param uiUrl where a person's browser can open Prometheus (differs between Compose and
   *     Kubernetes)
   */
  public record Prometheus(URI url, URI uiUrl, Duration connectTimeout, Duration readTimeout) {
    public Prometheus {
      url = url == null ? URI.create("http://localhost:9090") : url;
      uiUrl = uiUrl == null ? URI.create("http://localhost:9090") : uiUrl;
      connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
      readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
  }

  /**
   * Guarded remediation (FR-10 to FR-12, NFR-02, NFR-03). Off unless CloudPulse runs inside
   * Kubernetes with its restricted service account.
   *
   * @param namespace the only namespace CloudPulse may change
   * @param rolloutTimeout how long a restart may take to roll out before it counts as failed
   * @param verificationWindow how long after the rollout the incident must resolve to count as
   *     recovered (FR-12)
   */
  public record Remediation(
      Boolean enabled,
      String namespace,
      Kubernetes kubernetes,
      Duration rolloutTimeout,
      Duration verificationWindow) {
    public Remediation {
      enabled = enabled != null && enabled;
      namespace = namespace == null || namespace.isBlank() ? "cloudpulse-demo" : namespace;
      kubernetes = kubernetes == null ? new Kubernetes(null, null, null, null) : kubernetes;
      rolloutTimeout = rolloutTimeout == null ? Duration.ofSeconds(180) : rolloutTimeout;
      verificationWindow =
          verificationWindow == null ? Duration.ofSeconds(150) : verificationWindow;
    }

    public static Remediation disabled() {
      return new Remediation(false, null, null, null, null);
    }
  }

  /**
   * How to reach the Kubernetes API from inside a pod: the service account token and the
   * cluster's CA certificate are mounted by Kubernetes at the standard paths.
   */
  public record Kubernetes(URI apiUrl, String tokenPath, String caPath, Duration timeout) {
    public Kubernetes {
      apiUrl = apiUrl == null ? URI.create("https://kubernetes.default.svc") : apiUrl;
      tokenPath =
          tokenPath == null ? "/var/run/secrets/kubernetes.io/serviceaccount/token" : tokenPath;
      caPath = caPath == null ? "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt" : caPath;
      timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
  }
}
