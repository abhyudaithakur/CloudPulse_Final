package edu.cloudpulse.api.remediation;

import edu.cloudpulse.api.remediation.RemediationDtos.ApprovalRequest;
import edu.cloudpulse.api.remediation.RemediationDtos.RemediationConfig;
import edu.cloudpulse.api.remediation.RemediationDtos.RemediationView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Guarded remediation API (RAD §Initial REST Endpoints, FR-10, FR-11). */
@RestController
public class RemediationController {

  private final RemediationService remediation;

  public RemediationController(RemediationService remediation) {
    this.remediation = remediation;
  }

  /**
   * Explicitly approves a predefined runbook for an incident. Returns 202: the approval is
   * recorded now and executed asynchronously; follow it with GET .../remediations.
   */
  @PostMapping("/api/incidents/{id}/remediations/{runbook}/approve")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public RemediationView approve(
      @PathVariable long id, @PathVariable String runbook, @RequestBody(required = false) ApprovalRequest request) {
    return RemediationView.from(remediation.approve(id, runbook, request));
  }

  @GetMapping("/api/incidents/{id}/remediations")
  public List<RemediationView> forIncident(@PathVariable long id) {
    return remediation.forIncident(id).stream().map(RemediationView::from).toList();
  }

  @GetMapping("/api/remediations")
  public List<RemediationView> recent() {
    return remediation.recent().stream().map(RemediationView::from).toList();
  }

  @GetMapping("/api/remediation")
  public RemediationConfig config() {
    return remediation.config();
  }
}
