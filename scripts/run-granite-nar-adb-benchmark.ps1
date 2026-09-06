[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$AudioPath,

    [ValidateSet("CPU", "GPU_QNN", "NPU_QNN_HTP")]
    [string[]]$Backends = @("CPU", "GPU_QNN", "NPU_QNN_HTP"),

    [ValidateRange(0, 5)]
    [int]$WarmupRuns = 1,

    [ValidateRange(1, 20)]
    [int]$MeasuredRuns = 3,

    [ValidateRange(30, 3600)]
    [int]$TimeoutSeconds = 900,

    [ValidateRange(0, 300)]
    [int]$CooldownSeconds = 10,

    [bool]$RequireFullAcceleration = $true,

    [switch]$LoadOnly,
    [switch]$IncludeTranscript,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$RequireAllPassed,
    [string]$Serial,
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = "br.gov.sp.pcsp.launcher"
$componentName = "$packageName/.GraniteNarSmokeTestActivity"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$audioResolved = (Resolve-Path -LiteralPath $AudioPath).Path

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot "build/granite-nar-adb/$timestamp"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot $OutputDirectory
}
$outputResolved = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputResolved -Force | Out-Null

$adbCommand = Get-Command adb -ErrorAction Stop
$adbBase = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $adbBase += @("-s", $Serial)
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = & $adbCommand.Source @adbBase @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb falhou ($exitCode): adb $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return ,$output
}

function Save-AdbOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [switch]$AllowFailure
    )

    $lines = Invoke-Adb -Arguments $Arguments -AllowFailure:$AllowFailure
    $lines | Set-Content -LiteralPath $Path -Encoding utf8
}

function ConvertTo-AdbBool {
    param([bool]$Value)
    if ($Value) { return "true" }
    return "false"
}

Push-Location $projectRoot
try {
    $deviceLines = Invoke-Adb -Arguments @("devices")
    $connected = @(
        $deviceLines |
            Select-Object -Skip 1 |
            Where-Object { $_ -match "^([^\s]+)\s+device$" } |
            ForEach-Object { $Matches[1] }
    )
    if ($connected.Count -ne 1 -and [string]::IsNullOrWhiteSpace($Serial)) {
        throw "Esperado exatamente um aparelho ADB em estado device; encontrados: $($connected.Count). Use -Serial se necessário."
    }

    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "assembleDebug falhou." }
    }

    $apkPath = Join-Path $projectRoot "app/build/outputs/apk/debug/app-debug.apk"
    if (-not $SkipInstall) {
        if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
            throw "APK debug ausente: $apkPath"
        }
        Invoke-Adb -Arguments @("install", "-r", "-t", $apkPath) | Out-Host
    }

    Save-AdbOutput -Arguments @("shell", "getprop") -Path (Join-Path $outputResolved "device-getprop.txt")
    Save-AdbOutput -Arguments @("shell", "dumpsys", "package", $packageName) -Path (Join-Path $outputResolved "package.txt") -AllowFailure

    $remoteDirectory = "/sdcard/Android/data/$packageName/files/bench"
    $remoteAudioName = "nar-benchmark-$timestamp.wav"
    $remoteAudioPath = "$remoteDirectory/$remoteAudioName"
    Invoke-Adb -Arguments @("shell", "mkdir", "-p", $remoteDirectory) | Out-Null
    Invoke-Adb -Arguments @("push", $audioResolved, $remoteAudioPath) | Out-Host

    $runSummaries = [System.Collections.Generic.List[object]]::new()
    foreach ($backend in $Backends) {
        if ($CooldownSeconds -gt 0) {
            Write-Host "Aguardando cooldown de $CooldownSeconds s antes de $backend..."
            Start-Sleep -Seconds $CooldownSeconds
        }

        $backendSlug = $backend.ToLowerInvariant()
        $runId = "$timestamp-$backendSlug"
        $runDirectory = Join-Path $outputResolved $backendSlug
        New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

        Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null
        Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
        Save-AdbOutput -Arguments @("shell", "dumpsys", "battery") -Path (Join-Path $runDirectory "battery-before.txt") -AllowFailure
        Save-AdbOutput -Arguments @("shell", "dumpsys", "thermalservice") -Path (Join-Path $runDirectory "thermal-before.txt") -AllowFailure
        Save-AdbOutput -Arguments @("shell", "dumpsys", "meminfo", $packageName) -Path (Join-Path $runDirectory "meminfo-before.txt") -AllowFailure

        $startArguments = @(
            "shell", "am", "start", "-W",
            "-n", $componentName,
            "--es", "backend", $backend,
            "--es", "audio_path", $remoteAudioPath,
            "--es", "run_id", $runId,
            "--ez", "require_full_acceleration", (ConvertTo-AdbBool $RequireFullAcceleration),
            "--ei", "warmup_runs", $WarmupRuns.ToString(),
            "--ei", "measured_runs", $MeasuredRuns.ToString(),
            "--ez", "load_only", (ConvertTo-AdbBool ([bool]$LoadOnly)),
            "--ez", "include_text", (ConvertTo-AdbBool ([bool]$IncludeTranscript))
        )
        Write-Host "Executando $backend (run_id=$runId)..."
        Invoke-Adb -Arguments $startArguments | Tee-Object -FilePath (Join-Path $runDirectory "am-start.txt") | Out-Host

        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $finished = $false
        while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            $protocolLog = Invoke-Adb -Arguments @("logcat", "-d", "-s", "GraniteNarBench:I", "*:S") -AllowFailure
            $joined = $protocolLog -join "`n"
            if ($joined -match ([regex]::Escape('"run_id":"' + $runId + '"')) -and
                $joined -match '"event":"end"') {
                $finished = $true
                break
            }
            Start-Sleep -Seconds 2
        }

        Save-AdbOutput -Arguments @("logcat", "-d", "-v", "threadtime") -Path (Join-Path $runDirectory "logcat.txt") -AllowFailure
        Save-AdbOutput -Arguments @("shell", "dumpsys", "meminfo", $packageName) -Path (Join-Path $runDirectory "meminfo-after.txt") -AllowFailure
        Save-AdbOutput -Arguments @("shell", "dumpsys", "battery") -Path (Join-Path $runDirectory "battery-after.txt") -AllowFailure
        Save-AdbOutput -Arguments @("shell", "dumpsys", "thermalservice") -Path (Join-Path $runDirectory "thermal-after.txt") -AllowFailure

        $jsonLines = [System.Collections.Generic.List[string]]::new()
        $events = [System.Collections.Generic.List[object]]::new()
        foreach ($line in Get-Content -LiteralPath (Join-Path $runDirectory "logcat.txt")) {
            if ($line -match 'NAR_BENCH_JSON (\{.*\})$') {
                $json = $Matches[1]
                try {
                    $event = $json | ConvertFrom-Json
                    if ($event.run_id -eq $runId) {
                        $jsonLines.Add($json)
                        $events.Add($event)
                    }
                } catch {
                    # O logcat completo permanece como evidência para diagnosticar truncamento.
                }
            }
        }
        $jsonLines | Set-Content -LiteralPath (Join-Path $runDirectory "events.jsonl") -Encoding utf8

        $endEvent = $events | Where-Object { $_.event -eq "end" } | Select-Object -Last 1
        $status = if (-not $finished) { "timeout" } elseif ($null -eq $endEvent) { "protocol_error" } else { [string]$endEvent.status }
        $runSummaries.Add([pscustomobject]@{
            backend = $backend
            run_id = $runId
            status = $status
            protocol_events = $events.Count
            elapsed_seconds = [math]::Round($watch.Elapsed.TotalSeconds, 3)
            evidence_directory = $runDirectory
        })

        if (-not $finished) {
            Write-Warning "$backend excedeu o timeout de $TimeoutSeconds s."
            Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) -AllowFailure | Out-Null
        } else {
            Write-Host "$backend concluído com status: $status"
        }
    }

    $summary = [pscustomobject]@{
        created_at = (Get-Date).ToString("o")
        audio_name = [System.IO.Path]::GetFileName($audioResolved)
        audio_sha256 = (Get-FileHash -LiteralPath $audioResolved -Algorithm SHA256).Hash.ToLowerInvariant()
        require_full_acceleration = $RequireFullAcceleration
        warmup_runs = $WarmupRuns
        measured_runs = $MeasuredRuns
        load_only = [bool]$LoadOnly
        transcript_included = [bool]$IncludeTranscript
        runs = $runSummaries
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputResolved "summary.json") -Encoding utf8

    Invoke-Adb -Arguments @("shell", "rm", "-f", $remoteAudioPath) -AllowFailure | Out-Null
    Write-Host "Evidências salvas em: $outputResolved"

    if ($RequireAllPassed -and @($runSummaries | Where-Object { $_.status -ne "passed" }).Count -gt 0) {
        throw "Um ou mais backends não passaram; consulte summary.json."
    }
} finally {
    Pop-Location
}
