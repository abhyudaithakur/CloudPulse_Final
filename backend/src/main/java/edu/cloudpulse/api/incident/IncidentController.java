package edu.cloudpulse.api.incident;

import edu.cloudpulse.api.audit.AuditEventDto;
import edu.cloudpulse.api.audit.AuditService;
import edu.cloudpulse.api.correlation.CorrelationService;
import edu.cloudpulse.api.correlation.ProbableSource;
import edu.cloudpulse.api.incident.IncidentDtos.EvidenceItem;
import edu.cloudpulse.api.incident.IncidentDtos.IncidentDetail;
import edu.cloudpulse.api.incident.IncidentDtos.IncidentSummary;
import edu.cloudpulse.api.remediation.RemediationDtos.RemediationView;
import edu.cloudpulse.api.remediation.RemediationService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Incident REST API (RAD §Initial REST Endpoints, FR-06, FR-07, FR-08). */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

  private final IncidentService incidents;
  private final AuditService audit;
  private final CorrelationService correlation;
  private final RemediationService remediation;

  public IncidentController(
      IncidentService incidents,
      AuditService audit,
      CorrelationService correlation,
      RemediationService remediation) {
    this.incidents = incidents;
    this.audit = audit;
    this.correlation = correlation;
    this.remediation = remediation;
  }

  /** Incidents newest first, optionally filtered by status, service and rule. */
  @GetMapping
  public List<IncidentSummary> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String service,
      @RequestParam(required = false) String rule,
      @RequestParam(defaultValue = "50") int limit) {
    int bounded = Math.max(1, Math.min(limit, 200));
    List<Incident> found = incidents.search(parseStatus(status), service, rule, bounded);
    Map<Long, ProbableSource> sources = correlation.forIncidents(found);
    return found.stream().map(i -> IncidentSummary.from(i, sources.get(i.getId()))).toList();
  }

  /** Incident detail with evidence, timeline, probable source and remediation (FR-07, FR-08). */
  @GetMapping("/{id}")
  public IncidentDetail get(@PathVariable long id) {
    Incident incident = require(id);
    ProbableSource source = correlation.forIncident(incident);
    return new IncidentDetail(
        IncidentSummary.from(incident, source),
        evidence(id),
        timeline(id),
        source,
        remediation.options(incident, source),
        remediation.forIncident(id).stream().map(RemediationView::from).toList());
  }

  @GetMapping("/{id}/evidence")
  public List<EvidenceItem> evidence(@PathVariable long id) {
    require(id);
    return incidents.evidenceFor(id).stream().map(EvidenceItem::from).toList();
  }

  @GetMapping("/{id}/audit")
  public List<AuditEventDto> timeline(@PathVariable long id) {
    require(id);
    return audit.forIncident(id).stream().map(AuditEventDto::from).toList();
  }

  @GetMapping("/{id}/probable-source")
  public ProbableSource probableSource(@PathVariable long id) {
    return correlation.forIncident(require(id));
  }

  private Incident require(long id) {
    return incidents
        .find(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident " + id + " not found"));
  }

  private static IncidentStatus parseStatus(String status) {
    if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) {
      return null;
    }
    try {
      return IncidentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown status '" + status + "'. Use ACTIVE or RESOLVED.");
    }
  }
}
