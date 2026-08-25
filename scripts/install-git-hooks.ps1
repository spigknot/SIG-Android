# Ativa os hooks versionados do SIG no clone atual.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & git config --local core.hooksPath .githooks
    if ($LASTEXITCODE -ne 0) {
        throw "nao foi possivel configurar core.hooksPath"
    }
} finally {
    Pop-Location
}

if (-not $Quiet) {
    Write-Output "Hooks SIG ativados: .githooks"
}

exit 0
