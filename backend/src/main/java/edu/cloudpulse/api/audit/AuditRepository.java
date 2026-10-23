package edu.cloudpulse.api.audit;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Deliberately not a CrudRepository: audit history can be appended and read, never edited. */
public interface AuditRepository extends Repository<AuditEvent, Long> {

  AuditEvent save(AuditEvent event);

  List<AuditEvent> findByIncidentIdOrderByOccurredAtAscIdAsc(Long incidentId);

  List<AuditEvent> findAllByOrderByOccurredAtDescIdDesc(Pageable pageable);

  long count();
}
