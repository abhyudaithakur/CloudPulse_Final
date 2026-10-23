<#
.SYNOPSIS
  Deletes the local kind cluster created by k8s-up.ps1 (incident history included).
#>
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$kind = Get-Command kind.exe -ErrorAction SilentlyContinue
$exe = if ($kind) { $kind.Source } else { Join-Path (Get-Location) '.tools\kind.exe' }
if (-not (Test-Path $exe)) { throw 'kind is not installed; nothing to delete.' }
& $exe delete cluster --name cloudpulse
