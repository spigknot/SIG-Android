# Testes silenciosos de redaction e limites do diagnóstico.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $root "scripts\lib\diagnostics.ps1")

$secretText = @(
    "linha comum"
    "Authorization: Bearer bearer-secret-123"
    "api_key=api-secret-123"
    "password: password-secret-123"
    "token=token-secret-123"
    "xai-1234567890abcdef"
    "linha final"
) -join [Environment]::NewLine

$redacted = Redact-Diagnostic -Text $secretText -MaxLines 5 -MaxBytes 180
foreach ($secret in @("bearer-secret-123", "api-secret-123", "password-secret-123", "token-secret-123", "xai-1234567890abcdef")) {
    if ($redacted.Contains($secret)) {
        throw "segredo não redigido: $secret"
    }
}
if ($redacted -notmatch "<redacted>") {
    throw "diagnóstico redigido não contém marcador seguro"
}
if ((@($redacted -split "\r?\n")).Count -gt 5) {
    throw "limite de linhas não foi aplicado"
}
if ([Text.UTF8Encoding]::new($false).GetByteCount($redacted) -gt 180) {
    throw "limite de bytes não foi aplicado"
}

if (-not $Quiet) {
    Write-Output "OK: redaction de tokens, headers, senhas e limites"
}
exit 0
