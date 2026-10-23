<#
.SYNOPSIS
  Starts CloudPulse in Kubernetes mode on a local kind cluster (Windows, Docker Desktop).

.DESCRIPTION
  Builds the same images Docker Compose uses, creates a one-node Kubernetes cluster inside
  Docker ("kind"), loads the images, deploys deploy/k8s and waits until everything is ready.
  If kind or kubectl is not installed, a pinned copy is downloaded into .tools\ (no admin needed).

  Run from the repository folder:
    powershell -ExecutionPolicy Bypass -File .\scripts\k8s-up.ps1

  Then open http://localhost:30080 (dashboard) and http://localhost:30090 (Prometheus).
  Stop and delete the cluster with .\scripts\k8s-down.ps1
#>
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
# Windows PowerShell 5.1 may default to TLS 1.0, which GitHub (where kind is hosted) refuses.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Cluster = 'cloudpulse'
$KindVersion = 'v0.29.0'
$KubectlVersion = 'v1.33.1'
$Images = @('cloudpulse/api:local', 'cloudpulse/demo-service:local', 'cloudpulse/traffic-generator:local', 'cloudpulse/dashboard:local')
$Tools = Join-Path (Get-Location) '.tools'

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }

function Invoke-Checked {
  param([string]$Exe, [string[]]$Arguments)
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & $Exe @Arguments
  $code = $LASTEXITCODE
  $ErrorActionPreference = $previous
  if ($code -ne 0) { throw "'$Exe $($Arguments -join ' ')' failed with exit code $code" }
}

function Get-Tool {
  param([string]$Name, [string]$Url)
  $onPath = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
  if ($onPath) { return $onPath.Source }
  $local = Join-Path $Tools "$Name.exe"
  if (-not (Test-Path $local)) {
    Step "Downloading $Name ($Url)"
    New-Item -ItemType Directory -Force -Path $Tools | Out-Null
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $Url -OutFile $local -UseBasicParsing
  }
  return $local
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Desktop is not installed (see SETUP_WINDOWS.md).' }
# Native tools write progress to stderr; Windows PowerShell must not treat that as an error.
$ErrorActionPreference = 'Continue'
docker info *> $null
$dockerOk = $LASTEXITCODE -eq 0
$ErrorActionPreference = 'Stop'
if (-not $dockerOk) { throw 'Docker Desktop is not running. Start it and wait for "Engine running".' }

$Kind = Get-Tool 'kind' "https://kind.sigs.k8s.io/dl/$KindVersion/kind-windows-amd64"
$Kubectl = Get-Tool 'kubectl' "https://dl.k8s.io/release/$KubectlVersion/bin/windows/amd64/kubectl.exe"

Step 'Building images (the same ones Docker Compose uses)'
Invoke-Checked docker @('compose', 'build', 'api', 'checkout-api', 'traffic-generator', 'dashboard')

$ErrorActionPreference = 'Continue'
$clusters = & $Kind get clusters 2>$null
$ErrorActionPreference = 'Stop'
if (-not ($clusters -contains $Cluster)) {
  Step "Creating kind cluster '$Cluster' (about a minute)"
  Invoke-Checked $Kind @('create', 'cluster', '--config', 'deploy/kind/cluster.yaml', '--wait', '120s')
}
Invoke-Checked $Kubectl @('config', 'use-context', "kind-$Cluster")

Step 'Loading images into the cluster'
Invoke-Checked $Kind (@('load', 'docker-image', '--name', $Cluster) + $Images)

Step 'Deploying'
Invoke-Checked $Kubectl @('apply', '-k', 'deploy/k8s')
# A rebuilt image keeps its :local tag, so restart to pick it up (harmless on the first deploy).
Invoke-Checked $Kubectl @('-n', 'cloudpulse-demo', 'rollout', 'restart', 'deployment/checkout-api', 'deployment/payment-api', 'deployment/traffic-generator')
Invoke-Checked $Kubectl @('-n', 'cloudpulse', 'rollout', 'restart', 'deployment/cloudpulse-api', 'deployment/dashboard')

Step 'Waiting for everything to be ready (first start takes 1-3 minutes)'
Invoke-Checked $Kubectl @('-n', 'cloudpulse', 'rollout', 'status', 'statefulset/postgres', '--timeout=300s')
foreach ($d in 'prometheus', 'cloudpulse-api', 'dashboard') {
  Invoke-Checked $Kubectl @('-n', 'cloudpulse', 'rollout', 'status', "deployment/$d", '--timeout=300s')
}
foreach ($d in 'payment-api', 'checkout-api', 'traffic-generator') {
  Invoke-Checked $Kubectl @('-n', 'cloudpulse-demo', 'rollout', 'status', "deployment/$d", '--timeout=300s')
}

Write-Host ''
Write-Host 'CloudPulse is running in Kubernetes mode.' -ForegroundColor Green
Write-Host '  Dashboard   http://localhost:30080'
Write-Host '  Prometheus  http://localhost:30090'
Write-Host ''
Write-Host 'Try the guarded restart: Overview > Inject a fault > payment-api > Stuck, wait for the'
Write-Host 'incident, open it, and approve "Restart payment-api".'
Write-Host 'What CloudPulse may change:'
Write-Host "  $Kubectl auth can-i --list -n cloudpulse-demo --as=system:serviceaccount:cloudpulse:cloudpulse-api"
Write-Host 'Stop: .\scripts\k8s-down.ps1'
