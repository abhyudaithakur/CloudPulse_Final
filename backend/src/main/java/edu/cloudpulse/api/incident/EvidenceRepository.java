package edu.cloudpulse.api.incident;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceRepository extends JpaRepository<Evidence, Long> {

  List<Evidence> findByIncidentIdOrderByObservedAtAscIdAsc(Long incidentId);
}
