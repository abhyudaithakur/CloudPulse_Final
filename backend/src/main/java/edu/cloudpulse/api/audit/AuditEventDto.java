package edu.cloudpulse.api.audit;

import java.time.Instant;

public record AuditEventDto(
    long id,
    Instant occurredAt,
    AuditAction action,
    String actor,
    String target,
    Long incidentId,
    AuditResult result,
    String details) {

  public static AuditEventDto from(AuditEvent event) {
    return new AuditEventDto(
        event.getId(),
        event.getOccurredAt(),
        event.getAction(),
        event.getActor(),
        event.getTarget(),
        event.getIncidentId(),
        event.getResult(),
        event.getDetails());
  }
}
