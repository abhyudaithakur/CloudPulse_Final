package edu.cloudpulse.api.audit;

import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit module. Records join the caller's transaction, so an incident state change and its audit
 * event commit or roll back together.
 */
@Service
public class AuditService {

  public static final String ACTOR_DETECTOR = "detector";
  public static final String ACTOR_OPERATOR = "operator";
  public static final String ACTOR_REMEDIATOR = "remediator";

  private final AuditRepository events;
  private final Clock clock;

  public AuditService(AuditRepository events, Clock clock) {
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public AuditEvent record(
      AuditAction action,
      String actor,
      String target,
      Long incidentId,
      AuditResult result,
      String details) {
    return events.save(
        new AuditEvent(clock.instant(), action, actor, target, incidentId, result, details));
  }

  @Transactional(readOnly = true)
  public List<AuditEvent> forIncident(long incidentId) {
    return events.findByIncidentIdOrderByOccurredAtAscIdAsc(incidentId);
  }

  @Transactional(readOnly = true)
  public List<AuditEvent> recent(int limit) {
    return events.findAllByOrderByOccurredAtDescIdDesc(PageRequest.of(0, limit));
  }
}
