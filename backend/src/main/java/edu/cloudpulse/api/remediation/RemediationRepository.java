package edu.cloudpulse.api.remediation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemediationRepository extends JpaRepository<RemediationAction, Long> {

  List<RemediationAction> findByStatusInOrderByIdAsc(Collection<RemediationStatus> statuses);

  List<RemediationAction> findByIncidentIdOrderByApprovedAtDescIdDesc(Long incidentId);

  boolean existsByIncidentIdAndStatusIn(Long incidentId, Collection<RemediationStatus> statuses);

  List<RemediationAction> findTop50ByOrderByIdDesc();
}
