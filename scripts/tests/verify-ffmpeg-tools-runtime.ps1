param(
    [string]$FfmpegCommand = "ffmpeg",
    [string]$FfprobeCommand = "ffprobe",
    [string]$FontPath = "C:\Windows\Fonts\arial.ttf"
)

$ffmpegRuntimeRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$ffmpegRuntimeOutput = Join-Path $ffmpegRuntimeRoot ("build\ffmpeg-runtime-audit\" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $ffmpegRuntimeOutput | Out-Null

$ffmpegRuntimeExe = (Get-Command $FfmpegCommand -ErrorAction Stop).Source
$ffprobeRuntimeExe = (Get-Command $FfprobeCommand -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath $FontPath)) {
    $ffmpegRuntimeFont = Get-ChildItem -LiteralPath "C:\Windows\Fonts" -Filter "*.ttf" | Select-Object -First 1
    if ($null -eq $ffmpegRuntimeFont) { throw "Nenhuma fonte TTF foi encontrada para o teste de anexo." }
    $FontPath = $ffmpegRuntimeFont.FullName
}

function Invoke-FfmpegRuntimeCommand {
    param([string[]]$Arguments, [string]$FailureMessage)
    & $ffmpegRuntimeExe @Arguments
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

$ffmpegRuntimeWavIn = Join-Path $ffmpegRuntimeOutput "input-10s.wav"
$ffmpegRuntimeWavOut = Join-Path $ffmpegRuntimeOutput "output-9_9s.wav"
Invoke-FfmpegRuntimeCommand @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000:duration=10",
    "-c:a", "pcm_s16le", $ffmpegRuntimeWavIn
) "Falha ao gerar o WAV de teste."
Invoke-FfmpegRuntimeCommand @(
    "-hide_banner", "-loglevel", "error", "-y", "-i", $ffmpegRuntimeWavIn,
    "-t", "9.900", "-vn", "-map", "0:a:0?", "-map_metadata", "0",
    "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le",
    "-avoid_negative_ts", "make_zero", $ffmpegRuntimeWavOut
) "Falha no corte WAV de 9,9 s."
$ffmpegRuntimeWavDuration = [double](& $ffprobeRuntimeExe -v error -show_entries format=duration -of "default=noprint_wrappers=1:nokey=1" $ffmpegRuntimeWavOut)
if ([Math]::Abs($ffmpegRuntimeWavDuration - 9.9) -gt 0.02) {
    throw "Duração WAV inesperada: $ffmpegRuntimeWavDuration"
}

$ffmpegRuntimeMkvIn = Join-Path $ffmpegRuntimeOutput "input-attached.mkv"
$ffmpegRuntimeMkvOut = Join-Path $ffmpegRuntimeOutput "output-attached-cut.mkv"
Invoke-FfmpegRuntimeCommand @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25:duration=2",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
    "-attach", $FontPath, "-metadata:s:t", "mimetype=application/x-truetype-font", $ffmpegRuntimeMkvIn
) "Falha ao gerar o MKV com anexo."
Invoke-FfmpegRuntimeCommand @(
    "-hide_banner", "-loglevel", "error", "-y", "-ss", "0.200", "-noautorotate", "-i", $ffmpegRuntimeMkvIn,
    "-t", "1.200", "-map", "0:v:0?", "-map", "0:a?", "-map", "0:s?", "-map", "0:d?", "-map", "0:t?",
    "-map_metadata", "0", "-map_chapters", "0", "-c", "copy", "-c:t", "copy",
    "-avoid_negative_ts", "make_zero", "-f", "matroska", $ffmpegRuntimeMkvOut
) "Falha no corte MKV com anexo."
$ffmpegRuntimeAttachment = (& $ffprobeRuntimeExe -v error -select_streams t -show_entries stream=codec_type,codec_name -of "csv=p=0" $ffmpegRuntimeMkvOut) -join "`n"
if ([string]::IsNullOrWhiteSpace($ffmpegRuntimeAttachment)) {
    throw "O anexo não foi preservado no MKV."
}

$ffmpegRuntimeMulti1 = Join-Path $ffmpegRuntimeOutput "multi1.mkv"
$ffmpegRuntimeMulti2 = Join-Path $ffmpegRuntimeOutput "multi2.mkv"
$ffmpegRuntimeMultiOut = Join-Path $ffmpegRuntimeOutput "multi-joined.mkv"
$ffmpegRuntimeMultiLosslessOut = Join-Path $ffmpegRuntimeOutput "multi-normalized.mka"
Invoke-FfmpegRuntimeCommand @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=10:duration=1",
    "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=48000:duration=1",
    "-f", "lavfi", "-i", "sine=frequency=600:sample_rate=48000:duration=1",
    "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0",
    "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-shortest", $ffmpegRuntimeMulti1
) "Falha ao gerar o primeiro MKV multifaixa."
Invoke-FfmpegRuntimeCommand @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=10:duration=1",
    "-f", "lavfi", "-i", "sine=frequency=400:sample_rate=44100:duration=1",
    "-f", "lavfi", "-i", "sine=frequency=800:sample_rate=44100:duration=1",
    "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0",
    "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-shortest", $ffmpegRuntimeMulti2
) "Falha ao gerar o segundo MKV multifaixa."

$ffmpegRuntimeFilter = @(
    "[0:v]scale=160:120:force_original_aspect_ratio=decrease,pad=160:120:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=10,format=yuv420p[v0]",
    "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[a0_0]",
    "[0:a:1]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[a1_0]",
    "[1:v]scale=160:120:force_original_aspect_ratio=decrease,pad=160:120:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=10,format=yuv420p[v1]",
    "[1:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[a0_1]",
    "[1:a:1]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo[a1_1]",
    "[v0][v1]concat=n=2:v=1:a=0[vout]",
    "[a0_0][a0_1]concat=n=2:v=0:a=1[aout0]",
    "[a1_0][a1_1]concat=n=2:v=0:a=1[aout1]"
) -join ";"
Invoke-FfmpegRuntimeCommand @(
    "-hide_banner", "-loglevel", "error", "-y", "-i", $ffmpegRuntimeMulti1, "-i", $ffmpegRuntimeMulti2,
    "-filter_complex", $ffmpegRuntimeFilter,
    "-map", "[vout]", "-map", "[aout0]", "-map", "[aout1]",
    "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-ar", "48000", "-ac", "2",
    "-avoid_negative_ts", "make_zero", $ffmpegRuntimeMultiOut
) "Falha na junção multifaixa."
$ffmpegRuntimeAudioStreams = @(& $ffprobeRuntimeExe -v error -select_streams a -show_entries stream=index -of "csv=p=0" $ffmpegRuntimeMultiOut)
if ($ffmpegRuntimeAudioStreams.Count -ne 2) {
    throw "Quantidade de faixas de áudio inesperada: $($ffmpegRuntimeAudioStreams.Count)"
}

$ffmpegRuntimeLosslessFilter = @(
    "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=mono,asetpts=PTS-STARTPTS[a0_0]",
    "[1:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=mono,asetpts=PTS-STARTPTS[a0_1]",
    "[a0_0][a0_1]concat=n=2:v=0:a=1[aout0]",
    "[0:a:1]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=mono,asetpts=PTS-STARTPTS[a1_0]",
    "[1:a:1]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=mono,asetpts=PTS-STARTPTS[a1_1]",
    "[a1_0][a1_1]concat=n=2:v=0:a=1[aout1]"
) -join ";"
Invoke-FfmpegRuntimeCommand @(
    "-hide_banner", "-loglevel", "error", "-y", "-i", $ffmpegRuntimeMulti1, "-i", $ffmpegRuntimeMulti2,
    "-filter_complex", $ffmpegRuntimeLosslessFilter,
    "-map", "[aout0]", "-map", "[aout1]", "-vn",
    "-c:a", "flac", "-ar", "48000", "-ac", "1",
    "-avoid_negative_ts", "make_zero", $ffmpegRuntimeMultiLosslessOut
) "Falha na normalização MKA/FLAC multifaixa."
$ffmpegRuntimeLosslessStreams = @(& $ffprobeRuntimeExe -v error -select_streams a -show_entries stream=codec_name -of "csv=p=0" $ffmpegRuntimeMultiLosslessOut)
if ($ffmpegRuntimeLosslessStreams.Count -ne 2 -or $ffmpegRuntimeLosslessStreams.Where({ $_ -ne "flac" }).Count -ne 0) {
    throw "A normalização MKA não preservou duas faixas FLAC: $($ffmpegRuntimeLosslessStreams -join ',')"
}

Write-Output ("WAV_DURATION={0:F6}" -f $ffmpegRuntimeWavDuration)
Write-Output "MKV_ATTACHMENT=$ffmpegRuntimeAttachment"
Write-Output "JOIN_AUDIO_TRACKS=$($ffmpegRuntimeAudioStreams.Count)"
Write-Output "LOSSLESS_MKA_TRACKS=$($ffmpegRuntimeLosslessStreams.Count)"
Write-Output "ARTIFACTS=$ffmpegRuntimeOutput"
