# Funções compartilhadas para diagnósticos curtos e redigidos.

function Redact-Diagnostic {
    param(
        [AllowEmptyString()]
        [string]$Text = "",
        [int]$MaxLines = 80,
        [int]$MaxBytes = 12000
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return ""
    }

    if ($MaxLines -lt 1) { $MaxLines = 80 }
    if ($MaxBytes -lt 256) { $MaxBytes = 12000 }

    $redacted = [regex]::Replace($Text, "(?i)(?:sk-|xai-|cfat-|AKIA)[A-Za-z0-9_-]{8,}", "<redacted-token>")
    $redacted = [regex]::Replace($redacted, "(?im)^(Authorization|Proxy-Authorization)\s*:\s*.*$", '$1: <redacted>')
    $redacted = [regex]::Replace($redacted, "(?i)\bBearer\s+\S+", "Bearer <redacted>")
    $redacted = [regex]::Replace($redacted, "(?i)\b(api[_ -]?key|access[_ -]?key|secret[_ -]?access|password|passwd|token)\s*[:=]\s*\S+", '$1=<redacted>')

    $lines = $redacted -split "\r?\n"
    $bounded = (@($lines | Select-Object -Last $MaxLines) -join [Environment]::NewLine).Trim()
    $utf8 = [Text.UTF8Encoding]::new($false)
    if ($utf8.GetByteCount($bounded) -gt $MaxBytes) {
        $low = 0
        $high = $bounded.Length
        while ($low -lt $high) {
            $middle = [int][math]::Ceiling(($low + $high) / 2.0)
            if ($utf8.GetByteCount($bounded.Substring(0, $middle)) -le $MaxBytes) {
                $low = $middle
            } else {
                $high = $middle - 1
            }
        }
        $bounded = $bounded.Substring(0, $low).Trim()
    }
    return $bounded
}
