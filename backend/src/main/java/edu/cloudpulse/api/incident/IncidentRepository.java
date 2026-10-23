package edu.cloudpulse.api.incident;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository
    extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {

  Optional<Incident> findFirstByRuleIdAndServiceNameAndStatus(
      String ruleId, String serviceName, IncidentStatus status);

  boolean existsByRuleIdAndServiceNameAndStatus(
      String ruleId, String serviceName, IncidentStatus status);

  long countByStatus(IncidentStatus status);

  /** Incidents on these services that were still open at or after {@code since} (FR-08). */
  @Query(
      "select i from Incident i where i.serviceName in :services"
          + " and (i.resolvedAt is null or i.resolvedAt >= :since)")
  List<Incident> findTouching(
      @Param("services") Collection<String> services, @Param("since") Instant since);
}
