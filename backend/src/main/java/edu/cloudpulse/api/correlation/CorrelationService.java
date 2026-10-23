package edu.cloudpulse.api.correlation;

import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.detection.DetectionEngine;
import edu.cloudpulse.api.detection.RuleSnapshot;
import edu.cloudpulse.api.domain.ServiceTopology;
import edu.cloudpulse.api.incident.Incident;
import edu.cloudpulse.api.incident.IncidentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Correlation module (RAD §Backend Modules): loads the incidents around an incident and the
 * current telemetry state, then lets {@link CorrelationEngine} name a probable source.
 */
@Service
public class CorrelationService {

  private final ServiceTopology topology;
  private final IncidentService incidents;
  private final DetectionEngine detection;
  private final Clock clock;
  private final CorrelationEngine engine;
  private final Duration tolerance;

  public CorrelationService(
      ServiceTopology topology,
      IncidentService incidents,
      DetectionEngine detection,
      Clock clock,
      CloudPulseProperties properties) {
    this.topology = topology;
    this.incidents = incidents;
    this.detection = detection;
    this.clock = clock;
    this.tolerance = properties.detection().correlationTolerance();
    this.engine = new CorrelationEngine(topology, tolerance);
  }

  public ProbableSource forIncident(Incident incident) {
    Set<String> involved = new LinkedHashSet<>();
    involved.addAll(topology.dependenciesOf(incident.getServiceName()));
    involved.addAll(topology.dependentsOf(incident.getServiceName()));
    List<Incident> others =
        incidents.touching(involved, incident.getBreachStartedAt().minus(tolerance));
    return engine.analyze(incident, others, blindServices(), clock.instant());
  }

  /** One query for a whole list (dashboard, incident list), then pure analysis per incident. */
  public Map<Long, ProbableSource> forIncidents(List<Incident> list) {
    Map<Long, ProbableSource> result = new LinkedHashMap<>();
    if (list.isEmpty()) {
      return result;
    }
    Instant since =
        list.stream()
            .map(Incident::getBreachStartedAt)
            .min(Comparator.naturalOrder())
            .orElseThrow()
            .minus(tolerance);
    List<Incident> pool = incidents.touching(topology.names(), since);
    Set<String> blind = blindServices();
    Instant now = clock.instant();
    for (Incident incident : list) {
      result.put(incident.getId(), engine.analyze(incident, pool, blind, now));
    }
    return result;
  }

  /** Services with at least one rule that has no usable telemetry right now. */
  private Set<String> blindServices() {
    return detection.rules().stream()
        .filter(rule -> !rule.phase().isEvaluable())
        .map(RuleSnapshot::serviceName)
        .collect(Collectors.toSet());
  }
}
