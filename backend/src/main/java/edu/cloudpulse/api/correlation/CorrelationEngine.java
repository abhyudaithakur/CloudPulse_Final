package edu.cloudpulse.api.correlation;

import edu.cloudpulse.api.correlation.ProbableSource.RelatedIncident;
import edu.cloudpulse.api.correlation.ProbableSource.Verdict;
import edu.cloudpulse.api.domain.ServiceTopology;
import edu.cloudpulse.api.incident.Incident;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Pure correlation logic (RAD FR-08): no Spring, no database, no clock. Given an incident, the
 * dependency map and the incidents that overlapped it in time, it names a probable source:
 *
 * <ol>
 *   <li>If a service the incident's service depends on (directly or not) had its own incident that
 *       began no later than {@code tolerance} after this one, that dependency is the probable
 *       source. When several did, the one furthest downstream wins: failures travel upstream.
 *   <li>Otherwise, if the incident is still active and a dependency has no usable telemetry, the
 *       result is INCONCLUSIVE: a problem there cannot be ruled out (UC-03 alternative flow).
 *   <li>Otherwise the incident's own service is the probable source.
 * </ol>
 *
 * Services that depend on the incident's service and had overlapping incidents are listed as
 * affected, so an operator sees the blast radius.
 */
public final class CorrelationEngine {

  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  private final ServiceTopology topology;
  private final Duration tolerance;

  public CorrelationEngine(ServiceTopology topology, Duration tolerance) {
    this.topology = topology;
    this.tolerance = tolerance;
  }

  /**
   * @param incident the incident to explain
   * @param others incidents on other services (any time; this method picks the overlapping ones)
   * @param blindServices services with no usable telemetry right now
   * @param now current time, used as the end of a still-active incident
   */
  public ProbableSource analyze(
      Incident incident, Collection<Incident> others, Set<String> blindServices, Instant now) {
    String service = incident.getServiceName();
    Instant from = incident.getBreachStartedAt();
    Instant until = incident.getResolvedAt() == null ? now : incident.getResolvedAt();
    List<String> dependencies = topology.dependenciesOf(service);
    List<String> dependents = topology.dependentsOf(service);

    List<RelatedIncident> related = new ArrayList<>();
    List<Incident> dependencyIncidents = new ArrayList<>();
    List<String> evidence = new ArrayList<>();
    for (Incident other : others) {
      if (other.getId() != null && other.getId().equals(incident.getId())) {
        continue;
      }
      if (!overlaps(other, from.minus(tolerance), until)) {
        continue;
      }
      if (dependencies.contains(other.getServiceName())) {
        dependencyIncidents.add(other);
        related.add(relatedOf(other, "dependency"));
      } else if (dependents.contains(other.getServiceName())) {
        related.add(relatedOf(other, "dependent"));
      }
    }
    related.sort(Comparator.comparing(RelatedIncident::breachStartedAt));

    // 1. A dependency whose problem began first (or within the tolerance) is the probable source.
    Incident source =
        dependencyIncidents.stream()
            .filter(other -> !other.getBreachStartedAt().isAfter(from.plus(tolerance)))
            .max(
                Comparator.<Incident>comparingInt(
                        other -> dependencies.indexOf(other.getServiceName()))
                    .thenComparing(Incident::getBreachStartedAt, Comparator.reverseOrder()))
            .orElse(null);
    for (Incident later : dependencyIncidents) {
      if (later.getBreachStartedAt().isAfter(from.plus(tolerance))) {
        evidence.add(
            String.format(
                "%s also had an incident (%s), but it began %s after %s's, so it is more likely an"
                    + " effect than the cause.",
                later.getServiceName(),
                later.key(),
                seconds(Duration.between(from, later.getBreachStartedAt())),
                service));
      }
    }

    if (source != null) {
      String dependency = source.getServiceName();
      List<String> lines = new ArrayList<>();
      lines.add(
          String.format(
              "%s depends on %s (configured dependency map: %s).",
              service, dependency, path(service, dependency)));
      lines.add(
          String.format(
              "%s: %s (%s) began breaching at %s, %s.",
              dependency,
              source.getTitle().toLowerCase(java.util.Locale.ROOT),
              source.key(),
              CLOCK.format(source.getBreachStartedAt()),
              relativeTiming(source.getBreachStartedAt(), from, service)));
      lines.add(
          String.format(
              "%s peaked at %s against a threshold of %s.",
              dependency,
              source.getUnit().format(source.getPeakValue()),
              source.getUnit().format(source.getThresholdValue())));
      lines.addAll(evidence);
      lines.addAll(affected(related));
      String statement =
          String.format(
              "Probable source: %s. %s calls %s, and %s's problem started %s. Inferred from the"
                  + " dependency map and timing; not proof of root cause.",
              dependency,
              service,
              dependency,
              dependency,
              Duration.between(source.getBreachStartedAt(), from).isNegative()
                  ? "within the correlation window"
                  : "first or at the same time");
      return new ProbableSource(Verdict.DEPENDENCY, dependency, statement, lines, related);
    }

    // 2. Not enough evidence: a dependency we cannot see may be the cause.
    List<String> blindDependencies =
        dependencies.stream().filter(blindServices::contains).toList();
    if (incident.isActive() && !blindDependencies.isEmpty()) {
      List<String> lines = new ArrayList<>(evidence);
      lines.add(
          String.format(
              "No usable telemetry right now from %s, which %s depends on.",
              String.join(", ", blindDependencies),
              service));
      lines.addAll(affected(related));
      return new ProbableSource(
          Verdict.INCONCLUSIVE,
          null,
          String.format(
              "No probable source can be named yet: a problem in %s cannot be ruled out without"
                  + " its telemetry. The symptoms on %s are shown as measured.",
              String.join(", ", blindDependencies),
              service),
          lines,
          related);
    }

    // 3. The incident's own service.
    List<String> lines = new ArrayList<>(evidence);
    String statement;
    if (dependencies.isEmpty()) {
      lines.add(service + " has no configured dependencies.");
      statement =
          String.format(
              "Probable source: %s itself. It calls no other monitored service, so the condition"
                  + " started there.",
              service);
    } else {
      lines.add(
          String.format(
              "No dependency of %s (%s) had an incident that started before or with this one.",
              service, String.join(", ", dependencies)));
      statement =
          String.format(
              "Probable source: %s itself. The services it depends on showed no problem that"
                  + " started first.",
              service);
    }
    lines.addAll(affected(related));
    return new ProbableSource(Verdict.SELF, service, statement, lines, related);
  }

  private static boolean overlaps(Incident other, Instant from, Instant until) {
    boolean startedBeforeEnd = !other.getBreachStartedAt().isAfter(until);
    boolean endedAfterStart = other.getResolvedAt() == null || !other.getResolvedAt().isBefore(from);
    return startedBeforeEnd && endedAfterStart;
  }

  private static RelatedIncident relatedOf(Incident incident, String relation) {
    return new RelatedIncident(
        incident.getId() == null ? 0 : incident.getId(),
        incident.key(),
        incident.getServiceName(),
        incident.getRuleId(),
        incident.getTitle(),
        relation,
        incident.getStatus(),
        incident.getSeverity(),
        incident.getBreachStartedAt());
  }

  private static List<String> affected(List<RelatedIncident> related) {
    return related.stream()
        .filter(r -> r.relation().equals("dependent"))
        .map(
            r ->
                String.format(
                    "Also affected: %s (%s, %s), which depends on this service.",
                    r.serviceName(), r.key(), r.title().toLowerCase(java.util.Locale.ROOT)))
        .toList();
  }

  private String path(String from, String to) {
    List<String> hops = new ArrayList<>();
    hops.add(from);
    String current = from;
    // Walk the nearest-first dependency list to rebuild one path from -> ... -> to.
    while (!current.equals(to)) {
      String next = null;
      for (String candidate : topology.find(current).orElseThrow().dependsOn()) {
        if (candidate.equals(to) || topology.dependenciesOf(candidate).contains(to)) {
          next = candidate;
          break;
        }
      }
      if (next == null) {
        break;
      }
      hops.add(next);
      current = next;
    }
    return String.join(" → ", hops);
  }

  private static String relativeTiming(Instant dependencyStart, Instant incidentStart, String service) {
    Duration gap = Duration.between(dependencyStart, incidentStart);
    if (gap.abs().compareTo(Duration.ofSeconds(5)) < 0) {
      return "in the same 5 s evaluation cycle as " + service + "'s";
    }
    return gap.isNegative()
        ? seconds(gap.negated()) + " after " + service + "'s, within the correlation window"
        : seconds(gap) + " before " + service + "'s";
  }

  private static String seconds(Duration duration) {
    long s = Math.max(0, duration.toSeconds());
    return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
  }
}
