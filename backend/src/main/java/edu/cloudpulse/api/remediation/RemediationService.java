package edu.cloudpulse.api.remediation;

import edu.cloudpulse.api.audit.AuditAction;
import edu.cloudpulse.api.audit.AuditResult;
import edu.cloudpulse.api.audit.AuditService;
import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.correlation.ProbableSource;
import edu.cloudpulse.api.domain.MonitoredService;
import edu.cloudpulse.api.domain.ServiceTopology;
import edu.cloudpulse.api.incident.Incident;
import edu.cloudpulse.api.incident.IncidentService;
import edu.cloudpulse.api.remediation.RemediationDtos.ApprovalRequest;
import edu.cloudpulse.api.remediation.RemediationDtos.RemediationConfig;
import edu.cloudpulse.api.remediation.RemediationDtos.RunbookOption;
import edu.cloudpulse.api.remediation.WorkloadClient.RolloutState;
import edu.cloudpulse.api.remediation.WorkloadClient.WorkloadException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Guarded remediation (FR-10, FR-11, FR-12, NFR-02, UC-04).
 *
 * <p>Safety properties, each enforced in code and tested:
 *
 * <ul>
 *   <li>Nothing runs without an approval naming an operator and confirming explicitly.
 *   <li>Only predefined runbooks, only against the incident's service or a service it depends on,
 *       only in the configured namespace. Kubernetes RBAC enforces the same limits (NFR-03).
 *   <li>One remediation in progress per incident (also a partial unique index in PostgreSQL).
 *   <li>Every step is persisted before the next one; an attempt interrupted mid-call is marked
 *       FAILED, never retried automatically.
 *   <li>Every step leaves an audit event; a rejected or unsuccessful attempt leaves the incident
 *       active.
 * </ul>
 */
@Service
public class RemediationService {

  private static final Logger log = LoggerFactory.getLogger(RemediationService.class);

  private final RemediationRepository actions;
  private final IncidentService incidents;
  private final ServiceTopology topology;
  private final WorkloadClient workloads;
  private final AuditService audit;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate tx;
  private final CloudPulseProperties.Remediation config;

  public RemediationService(
      RemediationRepository actions,
      IncidentService incidents,
      ServiceTopology topology,
      WorkloadClient workloads,
      AuditService audit,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactions,
      CloudPulseProperties properties) {
    this.actions = actions;
    this.incidents = incidents;
    this.topology = topology;
    this.workloads = workloads;
    this.audit = audit;
    this.clock = clock;
    this.meters = meters;
    this.tx = new TransactionTemplate(transactions);
    this.config = properties.remediation();
  }

  public RemediationConfig config() {
    boolean enabled = workloads.enabled();
    return new RemediationConfig(
        enabled,
        enabled ? "kubernetes" : "disabled",
        config.namespace(),
        config.verificationWindow().toSeconds(),
        enabled
            ? "Approved restarts change deployments in namespace " + config.namespace() + " only."
            : "Remediation needs Kubernetes. In Docker Compose mode CloudPulse detects and explains,"
                + " but never changes anything.");
  }

  /** Runbook options for an incident: its own service, plus the probable source if different. */
  public List<RunbookOption> options(Incident incident, ProbableSource source) {
    Set<String> targets = new LinkedHashSet<>();
    String recommended = null;
    if (source != null && source.service() != null) {
      recommended = source.service();
      targets.add(source.service());
    }
    targets.add(incident.getServiceName());
    boolean busy = actions.existsByIncidentIdAndStatusIn(incident.getId(), RemediationStatus.IN_PROGRESS);
    String reason =
        !workloads.enabled()
            ? "Remediation is off in Docker Compose mode; run CloudPulse on Kubernetes to approve a restart."
            : !incident.isActive()
                ? "The incident is resolved; there is nothing to remediate."
                : busy ? "A remediation is already in progress for this incident." : null;
    List<RunbookOption> options = new ArrayList<>();
    for (String target : targets) {
      MonitoredService service = topology.find(target).orElse(null);
      if (service == null) {
        continue;
      }
      Runbook runbook = Runbook.RESTART_DEPLOYMENT;
      options.add(
          new RunbookOption(
              runbook.name(),
              "Restart " + service.name(),
              runbook.description(),
              service.name(),
              config.namespace(),
              service.deployment(),
              target.equals(recommended),
              reason == null,
              reason));
    }
    return options;
  }

  public List<RemediationAction> forIncident(long incidentId) {
    return actions.findByIncidentIdOrderByApprovedAtDescIdDesc(incidentId);
  }

  public List<RemediationAction> recent() {
    return actions.findTop50ByOrderByIdDesc();
  }

  /**
   * Records an operator's approval (FR-11). Nothing changes in the cluster here: execution starts
   * on the next {@link #tick()}, from the persisted approval.
   */
  public RemediationAction approve(long incidentId, String runbookId, ApprovalRequest request) {
    Runbook runbook =
        Runbook.fromId(runbookId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown runbook " + runbookId));
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An approval body is required.");
    }
    String operator = request.operator() == null ? "" : request.operator().trim();
    if (operator.isEmpty() || operator.length() > 64) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Name the operator approving this (1 to 64 characters).");
    }
    if (!Boolean.TRUE.equals(request.confirm())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Explicit confirmation is required: send \"confirm\": true.");
    }
    Incident incident =
        incidents
            .find(incidentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident " + incidentId + " not found"));
    if (!workloads.enabled()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Remediation is not enabled: CloudPulse is not running on Kubernetes with its service account.");
    }
    if (!incident.isActive()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, incident.key() + " is already resolved; there is nothing to remediate.");
    }
    String targetName =
        request.targetService() == null || request.targetService().isBlank()
            ? incident.getServiceName()
            : request.targetService().trim();
    List<String> allowed = new ArrayList<>();
    allowed.add(incident.getServiceName());
    allowed.addAll(topology.dependenciesOf(incident.getServiceName()));
    if (!allowed.contains(targetName)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "A restart for " + incident.key() + " may target only " + String.join(" or ", allowed) + ".");
    }
    MonitoredService target = topology.find(targetName).orElseThrow();

    Instant now = clock.instant();
    try {
      return tx.execute(
          status -> {
            if (actions.existsByIncidentIdAndStatusIn(incident.getId(), RemediationStatus.IN_PROGRESS)) {
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "A remediation is already in progress for " + incident.key() + ".");
            }
            RemediationAction action =
                actions.saveAndFlush(
                    RemediationAction.approved(
                        incident, runbook, target, config.namespace(), operator, request.note(), now));
            audit.record(
                AuditAction.REMEDIATION_APPROVED,
                "operator:" + operator,
                action.target(),
                incident.getId(),
                AuditResult.SUCCESS,
                String.format(
                    "%s approved %s of %s for %s.%s",
                    operator,
                    runbook.name(),
                    target.name(),
                    incident.key(),
                    request.note() == null || request.note().isBlank() ? "" : " Note: " + request.note().trim()));
            return action;
          });
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A remediation is already in progress for " + incident.key() + ".");
    }
  }

  /** Advances every in-progress remediation by at most one step. Called every few seconds. */
  public synchronized void tick() {
    for (RemediationAction action : actions.findByStatusInOrderByIdAsc(RemediationStatus.IN_PROGRESS)) {
      try {
        advance(action.getId());
      } catch (RuntimeException e) {
        log.error("Remediation {} could not advance; retrying next tick", action.getId(), e);
      }
    }
  }

  private void advance(long id) {
    RemediationAction action = actions.findById(id).orElseThrow();
    Instant now = clock.instant();
    switch (action.getStatus()) {
      case APPROVED -> execute(action, now);
      case EXECUTING ->
          // Only possible if the API stopped in the middle of the Kubernetes call. Whether the
          // restart happened is unknown, so it is reported, never repeated without a new approval.
          finish(
              id,
              RemediationStatus.FAILED,
              now,
              AuditAction.REMEDIATION_FAILED,
              AuditResult.FAILURE,
              "Interrupted while calling Kubernetes, so the outcome is unknown. Not retried"
                  + " automatically: check the deployment and approve again if needed.");
      case ROLLING_OUT -> checkRollout(action, now);
      case VERIFYING -> verify(action, now);
      default -> {
        // finished
      }
    }
  }

  private void execute(RemediationAction approved, Instant now) {
    long id = approved.getId();
    tx.executeWithoutResult(
        status -> {
          RemediationAction action = actions.findById(id).orElseThrow();
          action.executing(now);
        });
    try {
      // The only cluster-changing call in CloudPulse, outside any database transaction.
      long generation =
          workloads.restartDeployment(
              approved.getNamespace(),
              approved.getDeployment(),
              now.toString(),
              "incident-" + approved.getIncidentId() + "-approved-by-" + slug(approved.getApprovedBy()));
      tx.executeWithoutResult(
          status -> {
            RemediationAction action = actions.findById(id).orElseThrow();
            String text =
                String.format(
                    "Kubernetes accepted the rolling restart of %s (generation %d); replacing pods.",
                    action.target(), generation);
            action.rollingOut(generation, text);
            audit.record(
                AuditAction.REMEDIATION_EXECUTED,
                AuditService.ACTOR_REMEDIATOR,
                action.target(),
                action.getIncidentId(),
                AuditResult.SUCCESS,
                text + " Approved by " + action.getApprovedBy() + ".");
          });
      count(approved, "executed");
    } catch (WorkloadException e) {
      finish(
          id,
          RemediationStatus.FAILED,
          clock.instant(),
          AuditAction.REMEDIATION_EXECUTED,
          AuditResult.FAILURE,
          "Kubernetes rejected the restart of " + approved.target() + ": " + e.getMessage()
              + ". The incident stays active.");
    }
  }

  private void checkRollout(RemediationAction action, Instant now) {
    RolloutState state = null;
    try {
      state = workloads.rolloutState(action.getNamespace(), action.getDeployment());
    } catch (WorkloadException e) {
      log.warn("Rollout status of {} unavailable: {}", action.target(), e.getMessage());
    }
    long id = action.getId();
    if (state != null && state.complete() && state.generation() >= nullToZero(action.getGeneration())) {
      Instant until = now.plus(config.verificationWindow());
      RolloutState done = state;
      tx.executeWithoutResult(
          status -> {
            RemediationAction a = actions.findById(id).orElseThrow();
            String text =
                String.format(
                    "Rollout complete: %d of %d pods replaced and ready. Watching the incident for %d s.",
                    done.updated(), done.desired(), config.verificationWindow().toSeconds());
            a.verifying(now, until, text);
            audit.record(
                AuditAction.REMEDIATION_ROLLED_OUT,
                AuditService.ACTOR_REMEDIATOR,
                a.target(),
                a.getIncidentId(),
                AuditResult.SUCCESS,
                text);
          });
      return;
    }
    if (action.getExecutedAt() != null
        && Duration.between(action.getExecutedAt(), now).compareTo(config.rolloutTimeout()) > 0) {
      finish(
          id,
          RemediationStatus.FAILED,
          now,
          AuditAction.REMEDIATION_FAILED,
          AuditResult.FAILURE,
          String.format(
              "The rollout of %s did not complete within %d s. The incident stays active.",
              action.target(), config.rolloutTimeout().toSeconds()));
    }
  }

  /** FR-12: did the condition clear after the remediation? */
  private void verify(RemediationAction action, Instant now) {
    Incident incident = incidents.find(action.getIncidentId()).orElseThrow();
    if (!incident.isActive()) {
      Duration after = Duration.between(action.getRolledOutAt(), incident.getResolvedAt());
      finish(
          action.getId(),
          RemediationStatus.RECOVERED,
          now,
          AuditAction.REMEDIATION_VERIFIED,
          AuditResult.SUCCESS,
          String.format(
              "Recovered: %s resolved %d s after the rollout finished, inside the %d s verification window.",
              incident.key(), Math.max(0, after.toSeconds()), config.verificationWindow().toSeconds()));
      return;
    }
    if (!now.isBefore(action.getVerifyUntil())) {
      finish(
          action.getId(),
          RemediationStatus.NOT_RECOVERED,
          now,
          AuditAction.REMEDIATION_VERIFIED,
          AuditResult.FAILURE,
          String.format(
              "Not recovered: %s was still active when the %d s verification window ended. The"
                  + " incident stays open for the operator.",
              incident.key(), config.verificationWindow().toSeconds()));
    }
  }

  private void finish(
      long id,
      RemediationStatus outcome,
      Instant at,
      AuditAction auditAction,
      AuditResult result,
      String text) {
    RemediationAction finished =
        tx.execute(
            status -> {
              RemediationAction action = actions.findById(id).orElseThrow();
              action.finish(outcome, at, text);
              audit.record(
                  auditAction,
                  AuditService.ACTOR_REMEDIATOR,
                  action.target(),
                  action.getIncidentId(),
                  result,
                  text);
              return action;
            });
    count(finished, outcome.name().toLowerCase(java.util.Locale.ROOT));
    log.info("Remediation {} finished: {}", id, outcome);
  }

  private void count(RemediationAction action, String outcome) {
    meters
        .counter("cloudpulse.remediations", "runbook", action.getRunbook().name(), "outcome", outcome)
        .increment();
  }

  private static long nullToZero(Long value) {
    return value == null ? 0 : value;
  }

  private static String slug(String text) {
    String s = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    return s.length() > 40 ? s.substring(0, 40) : s;
  }
}
