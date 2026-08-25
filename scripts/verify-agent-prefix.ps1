# Verifica o contrato estável do prefixo global do agente.
# Saída silenciosa por padrão quando -Quiet é usado; -Json emite evidência
# determinística sem prompt, conteúdo de usuário ou segredo.
param(
    [string]$Path = "",
    [int]$BudgetBytes = 8192,
    [switch]$Quiet,
    [switch]$Json
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Path)) {
    $root = Split-Path -Parent $PSScriptRoot
    $Path = Join-Path $root "AGENTS.md"
}

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    $result = [ordered]@{
        contract = "sig-agent-prefix/v1"
        status = "fail"
        error = "arquivo não encontrado"
    }
    if ($Json) { $result | ConvertTo-Json -Depth 6 -Compress }
    else { Write-Error $result.error }
    exit 1
}

$fullPath = (Resolve-Path -LiteralPath $Path).Path
$raw = [IO.File]::ReadAllText($fullPath)
if ($raw.Length -gt 0 -and $raw[0] -eq [char]0xFEFF) {
    $raw = $raw.Substring(1)
}

# A canonicalização torna o fingerprint independente do checkout CRLF/LF.
$crlf = ([char]13).ToString() + ([char]10).ToString()
$lf = ([char]10).ToString()
$canonical = $raw.Replace($crlf, $lf).Replace(([char]13).ToString(), $lf)
$canonical = [regex]::Replace($canonical, "[ \t]+(?=" + $lf + "|$)", "")
if (-not $canonical.EndsWith($lf)) {
    $canonical += $lf
}

$utf8 = [Text.UTF8Encoding]::new($false)
$bytes = $utf8.GetBytes($canonical)
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $hash = $sha256.ComputeHash($bytes)
} finally {
    $sha256.Dispose()
}
$fingerprint = ([BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()

$rules = @(
    [ordered]@{
        name = "release-or-date"
        pattern = "(?i)\b(?:20\d{6}[-_]\d{3}|20\d{2}[-_]\d{2}[-_]\d{2}(?:[-_]\d{3})?)\b"
    },
    [ordered]@{
        name = "explicit-version"
        pattern = "(?i)\b(?:version(?:Code|Name)?|tag)\s*[:=]\s*[0-9]+(?:\.[0-9]+)*(?:[-_][0-9]+)?\b"
    },
    [ordered]@{
        name = "absolute-local-path"
        pattern = "(?i)\b[A-Z]:[\\/]|/Users/|/home/|/mnt/|/o/"
    },
    [ordered]@{
        name = "credential-prefix"
        pattern = "(?i)\b(?:sk-|xai-|cfat-|AKIA)[A-Za-z0-9_-]{8,}\b"
    },
    [ordered]@{
        name = "credential-assignment"
        pattern = "(?i)\b(?:api[_ -]?key|access[_ -]?key|secret[_ -]?access)\s*[:=]\s*\S+"
    }
)

$forbidden = @()
foreach ($rule in $rules) {
    foreach ($match in [regex]::Matches($canonical, $rule.pattern)) {
        $line = 1 + (($canonical.Substring(0, $match.Index).Split([char]10)).Count - 1)
        $forbidden += [ordered]@{
            rule = $rule.name
            line = $line
            length = $match.Length
        }
    }
}

$estimatedTokens = [math]::Ceiling($bytes.Length / 4.0)
$budgetTokens = [math]::Floor($BudgetBytes / 4.0)
$lineEnding = if ($raw.Contains([char]13)) { "crlf-or-mixed" } else { "lf" }
$status = if ($bytes.Length -le $BudgetBytes -and $forbidden.Count -eq 0) { "pass" } else { "fail" }

$result = [ordered]@{
    contract = "sig-agent-prefix/v1"
    status = $status
    canonicalization = "UTF-8 no BOM; LF; trailing whitespace removed; final newline"
    sourceLineEnding = $lineEnding
    prefixFingerprint = $fingerprint
    prefixBytes = $bytes.Length
    estimatedTokens = $estimatedTokens
    budgetBytes = $BudgetBytes
    budgetTokens = $budgetTokens
    telemetryStatus = "unavailable"
    forbiddenMatches = @($forbidden)
}

if ($Json) {
    $result | ConvertTo-Json -Depth 6 -Compress
} elseif (-not $Quiet) {
    Write-Output ("{0}: {1} bytes, ~{2} tokens, sha256={3}" -f $status.ToUpperInvariant(), $result.prefixBytes, $result.estimatedTokens, $result.prefixFingerprint)
}

if ($status -ne "pass") {
    if (-not $Json) {
        if ($result.prefixBytes -gt $BudgetBytes) {
            Write-Error ("prefixo excede o orçamento: {0} > {1} bytes" -f $result.prefixBytes, $BudgetBytes)
        }
        foreach ($entry in $forbidden) {
            Write-Error ("conteúdo volátil detectado: regra={0}, linha={1}" -f $entry.rule, $entry.line)
        }
    }
    exit 1
}

exit 0
