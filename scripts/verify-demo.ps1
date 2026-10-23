<#
  CloudPulse end-to-end check for Windows (PowerShell 5+). Prints PASS lines and measured timings.

  Docker Compose mode (after `docker compose up --build -d`), about 6 minutes:
      powershell -ExecutionPolicy Bypass -File .\scripts\verify-demo.ps1
    1. checkout-api latency: detect, deduplicate, escalate, recover
    2. payment-api errors: both services break, and checkout's incident names payment-api as the
       probable source (FR-08); then both recover
    Use -Quick to run only step 1 (about 3 minutes).

  Kubernetes mode (after .\scripts\k8s-up.ps1), about 5 minutes:
      powershell -ExecutionPolicy Bypass -File .\scripts\verify-demo.ps1 -Kubernetes
    payment-api gets stuck; CloudPulse traces checkout's incident to it; the script approves the
    restart runbook as operator "verify-demo" and waits until CloudPulse verifies the recovery.
#>
param(
  [switch]$Kubernetes,
  [switch]$Quick,
  [string]$Api
)

$ErrorActionPreference = 'Stop'
if (-not $Api) { $Api = if ($Kubernetes) { 'http://localhost:30080' } else { 'http://localhost:8080' } }
$logsHint = if ($Kubernetes) { 'kubectl -n cloudpulse logs deployment/cloudpulse-api --tail 80' } else { 'docker compose logs --tail 80 api prometheus checkout-api payment-api' }

function Set-Fault([string]$service, [string]$mode) {
  $body = @{ service = $service; mode = $mode } | ConvertTo-Json
  Invoke-RestMethod -Method Post -Uri "$Api/api/demo/fault" -ContentType 'application/json' -Body $body | Out-Null
}

function Wait-Until([scriptblock]$probe, [int]$timeoutSeconds, [string]$what) {
  $watch = [Diagnostics.Stopwatch]::StartNew()
  while ($watch.Elapsed.TotalSeconds -lt $timeoutSeconds) {
    $value = $null
    try { $value = & $probe } catch { }
    if ($value) { return @{ Value = $value; Seconds = [math]::Round($watch.Elapsed.TotalSeconds) } }
    Start-Sleep -Seconds 3
  }
  throw "Timed out after $timeoutSeconds s waiting for $what. Check: $logsHint"
}

function Get-Active([string]$rule, [string]$service) {
  # Assign first: Windows PowerShell 5.1 returns a JSON array as one un-enumerated object.
  $list = Invoke-RestMethod "$Api/api/incidents?status=active&rule=$rule&service=$service"
  @($list)
}

function Get-Incident([long]$id) { Invoke-RestMethod "$Api/api/incidents/$id" }

function Pass([string]$text) { Write-Host "PASS: $text" -ForegroundColor Green }
function Step([string]$text) { Write-Host $text -ForegroundColor Cyan }

$measured = [ordered]@{}
try {
  Step "Checking that CloudPulse at $Api is up and both services are healthy..."
  $null = Invoke-RestMethod "$Api/api/health"
  $null = Wait-Until {
    $d = Invoke-RestMethod "$Api/api/dashboard"
    ($d.pipeline.status -eq 'CONNECTED') -and ($d.health -eq 'HEALTHY') -and ($d.counts.active -eq 0)
  } 240 'both services HEALTHY with no active incident'
  $dashboard = Invoke-RestMethod "$Api/api/dashboard"
  if ($Kubernetes -and -not $dashboard.remediation.enabled) { throw 'Remediation is not enabled: is this the Kubernetes deployment?' }
  Pass "$(($dashboard.services | ForEach-Object { $_.name }) -join ' and ') HEALTHY, Prometheus CONNECTED, remediation $($dashboard.remediation.mode)"

  if (-not $Kubernetes) {
    # ---- 1. latency on checkout-api: detect, deduplicate, escalate, recover ------------------
    Step 'Injecting high latency (1.4 s) on checkout-api. Detection waits for a 30 s sustained breach...'
    Set-Fault 'checkout-api' 'high-latency'
    $opened = Wait-Until { Get-Active 'LATENCY_P95_SUSTAINED' 'checkout-api' | Select-Object -First 1 } 180 'a latency incident'
    $incident = $opened.Value
    $measured['latency detect (s)'] = $opened.Seconds
    Pass "$($incident.key) opened $($opened.Seconds) s after injection, severity $($incident.severity), p95 $([math]::Round($incident.lastValue, 3)) s"

    Start-Sleep -Seconds 12
    $active = Get-Active 'LATENCY_P95_SUSTAINED' 'checkout-api'
    if ($active.Count -ne 1 -or $active[0].id -ne $incident.id) { throw "Deduplication failed: $($active.Count) active latency incidents." }
    Pass 'sustained fault is still one incident (deduplicated)'

    Step 'Switching to extreme latency (2.2 s); the same incident should escalate to CRITICAL...'
    Set-Fault 'checkout-api' 'extreme-latency'
    $escalated = Wait-Until { $i = (Get-Incident $incident.id).incident; if ($i.severity -eq 'CRITICAL') { $i } } 150 'escalation'
    $measured['escalate (s)'] = $escalated.Seconds
    Pass "$($incident.key) escalated to CRITICAL after $($escalated.Seconds) s (peak $([math]::Round($escalated.Value.peakValue, 2)) s)"

    Step 'Clearing the fault; waiting for the 1-minute window to drain and 10 s of recovery...'
    Set-Fault 'checkout-api' 'none'
    $resolved = Wait-Until { (Get-Incident $incident.id).incident.status -eq 'RESOLVED' } 240 'resolution'
    $measured['latency resolve (s)'] = $resolved.Seconds
    $timeline = (Get-Incident $incident.id).timeline | ForEach-Object { $_.action }
    Pass "$($incident.key) resolved $($resolved.Seconds) s after clearing; audit: $($timeline -join ' -> ')"

    if (-not $Quick) {
      # ---- 2. errors on payment-api: probable source -----------------------------------------
      Step 'Injecting an error storm on payment-api (3 in 10 requests fail). checkout-api calls it, so it fails too...'
      Set-Fault 'payment-api' 'error-storm'
      $both = Wait-Until {
        $c = Get-Active 'ERROR_RATE_SUSTAINED' 'checkout-api' | Select-Object -First 1
        $p = Get-Active 'ERROR_RATE_SUSTAINED' 'payment-api' | Select-Object -First 1
        if ($c -and $p) { @{ checkout = $c; payment = $p } }
      } 180 'error-rate incidents on both services'
      $measured['probable source detect (s)'] = $both.Seconds
      $source = (Get-Incident $both.Value.checkout.id).probableSource
      if ($source.verdict -ne 'DEPENDENCY' -or $source.service -ne 'payment-api') { throw "Expected payment-api as the probable source, got $($source.verdict) $($source.service)" }
      Pass "$($both.Value.checkout.key) on checkout-api: $($source.statement)"

      Set-Fault 'payment-api' 'none'
      $bothResolved = Wait-Until {
        ((Get-Incident $both.Value.checkout.id).incident.status -eq 'RESOLVED') -and ((Get-Incident $both.Value.payment.id).incident.status -eq 'RESOLVED')
      } 240 'both incidents resolved'
      Pass "both incidents resolved $($bothResolved.Seconds) s after payment-api recovered"
    }
  }
  else {
    # ---- Kubernetes: stuck service, guarded restart, verified recovery ---------------------
    Step 'Making payment-api stuck (3 s per request; only a restart clears it)...'
    Set-Fault 'payment-api' 'stuck'
    $stuck = Wait-Until {
      $c = Get-Active 'LATENCY_P95_SUSTAINED' 'checkout-api' | Select-Object -First 1
      $p = Get-Active 'LATENCY_P95_SUSTAINED' 'payment-api' | Select-Object -First 1
      if ($c -and $p) { @{ checkout = $c; payment = $p } }
    } 240 'latency incidents on both services'
    $measured['stuck detect (s)'] = $stuck.Seconds
    $detail = Get-Incident $stuck.Value.checkout.id
    Pass "$($stuck.Value.checkout.key): $($detail.probableSource.statement)"
    $refusedWith = $null
    try { Set-Fault 'payment-api' 'none' } catch { $refusedWith = [int]$_.Exception.Response.StatusCode }
    if (-not $refusedWith) { throw 'Clearing a stuck service should have been refused.' }
    Pass "clearing the stuck fault is refused (HTTP $refusedWith): only a restart helps"

    Step 'Approving "Restart payment-api" as operator verify-demo...'
    $approval = @{ targetService = 'payment-api'; operator = 'verify-demo'; confirm = $true; note = 'verify-demo.ps1' } | ConvertTo-Json
    $action = Invoke-RestMethod -Method Post -Uri "$Api/api/incidents/$($stuck.Value.checkout.id)/remediations/RESTART_DEPLOYMENT/approve" -ContentType 'application/json' -Body $approval
    Pass "remediation $($action.id) $($action.status) for $($action.target)"
    $seen = New-Object System.Collections.Generic.List[string]
    $done = Wait-Until {
      $list = Invoke-RestMethod "$Api/api/incidents/$($stuck.Value.checkout.id)/remediations"
      $r = @($list)[0]
      if ($seen.Count -eq 0 -or $seen[$seen.Count - 1] -ne $r.status) { $seen.Add($r.status) }
      if (-not $r.inProgress) { $r }
    } 420 'the remediation to finish'
    $measured['approval to verified (s)'] = $done.Seconds
    if ($done.Value.status -ne 'RECOVERED') { throw "Remediation finished as $($done.Value.status): $($done.Value.detail)" }
    Pass "$($seen -join ' -> ') in $($done.Seconds) s. $($done.Value.detail)"
  }

  Write-Host ''
  Write-Host 'CloudPulse end-to-end check passed.' -ForegroundColor Green
  Write-Host ("Measured: " + (($measured.GetEnumerator() | ForEach-Object { "$($_.Key) $($_.Value)" }) -join ', ')) -ForegroundColor Green
}
finally {
  foreach ($service in 'checkout-api', 'payment-api') { try { Set-Fault $service 'none' } catch { } }
}
