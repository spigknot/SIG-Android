# Ativa os hooks versionados deste clone sem alterar outros repositórios.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    & git config --local core.hooksPath .githooks
    if ($LASTEXITCODE -ne 0) { throw "não foi possível configurar core.hooksPath" }
} finally {
    Pop-Location
}

if (-not $Quiet) { Write-Output "OK: hooks Git configurados em .githooks" }
exit 0
