@echo off
setlocal EnableExtensions EnableDelayedExpansion

if /I "%~1"=="__convert_worker" goto convert_worker
if /I "%~1"=="__transcribe_worker" goto transcribe_worker
if /I "%~1"=="__worker" goto transcribe_worker

set "SERVER=http://100.110.211.23:8100/transcribe"
set "MAX_CONVERT_PARALLEL=6"
set "MAX_TRANSCRIBE_PARALLEL=8"
set "WAVDIR=%CD%\wav_16000"
set "OUTDIR=%CD%\out"
set "RAWDIR=%CD%\out_raw"
set "JOBDIR=%TEMP%\granite_avare_jobs_%RANDOM%_%RANDOM%"
set "MANIFEST=%JOBDIR%\manifest.txt"

if not exist "%WAVDIR%" mkdir "%WAVDIR%"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"
if not exist "%RAWDIR%" mkdir "%RAWDIR%"
mkdir "%JOBDIR%" >nul 2>nul

echo Granite Avare - converter e transcrever em paralelo
echo Servidor: %SERVER%
echo Pasta: %CD%
echo WAV temporario: %WAVDIR%
echo Saida TXT: %OUTDIR%
echo Respostas cruas: %RAWDIR%
echo Conversoes simultaneas: %MAX_CONVERT_PARALLEL%
echo Requisicoes simultaneas: %MAX_TRANSCRIBE_PARALLEL%
echo.

set /a COUNT=0
set /a CONVERTED=0
set /a CONVERT_JOBID=0

echo === Convertendo tudo primeiro, em paralelo ===
for %%F in (*.wav *.mp3 *.m4a *.ogg *.opus *.flac *.aac *.wma *.mp4 *.mov *.mkv *.avi *.webm) do (
    if exist "%%~fF" (
        set /a COUNT+=1
        set /a CONVERT_JOBID+=1
        call :wait_for_convert_slot

        set "WAVFILE=%WAVDIR%\%%~nF.wav"
        set "OUT_FILE=%OUTDIR%\%%~nF.txt"
        set "DONE_FILE=%JOBDIR%\convert_!CONVERT_JOBID!.done"
        set "RUN_FILE=%JOBDIR%\convert_!CONVERT_JOBID!.run"

        break > "!RUN_FILE!"
        echo [!CONVERT_JOBID!] Convertendo: %%~nxF
        start "" /b cmd /c call "%~f0" __convert_worker "%%~fF" "!WAVFILE!" "!OUT_FILE!" "!DONE_FILE!"
    )
)

if "%COUNT%"=="0" (
    echo Nenhum arquivo encontrado nesta pasta.
    echo Extensoes procuradas: wav, mp3, m4a, ogg, opus, flac, aac, wma, mp4, mov, mkv, avi, webm
    goto finish
)

call :wait_convert_all

echo.
echo === Resultado das conversoes ===
type "%JOBDIR%\convert_*.done" 2>nul

if exist "%MANIFEST%" del /q "%MANIFEST%" >nul 2>nul
for %%P in ("%JOBDIR%\convert_*.map") do (
    if exist "%%~fP" type "%%~fP" >> "%MANIFEST%"
)

if exist "%MANIFEST%" (
    for /f "usebackq tokens=1,* delims=^|" %%W in ("%MANIFEST%") do (
        if exist "%%~fW" set /a CONVERTED+=1
    )
)

if "%CONVERTED%"=="0" (
    echo Nenhum arquivo foi convertido com sucesso.
    goto finish
)

echo.
echo === Enviando transcricoes em paralelo ===
set /a JOBID=0

for /f "usebackq tokens=1,* delims=^|" %%W in ("%MANIFEST%") do (
    if exist "%%~fW" (
        call :wait_for_transcribe_slot
        set /a JOBID+=1
        set "DONE_FILE=%JOBDIR%\trans_!JOBID!.done"
        set "RUN_FILE=%JOBDIR%\trans_!JOBID!.run"
        break > "!RUN_FILE!"
        echo [!JOBID!] Enviando: %%~nxW  ^(original: %%X^)
        start "" /b cmd /c call "%~f0" __transcribe_worker "%%~fW" "%OUTDIR%\%%~nW.txt" "%SERVER%" "!DONE_FILE!" "%RAWDIR%\%%~nW.json"
    )
)

call :wait_transcribe_all
echo.
echo === Resultado das transcricoes ===
type "%JOBDIR%\trans_*.done" 2>nul
call :write_html_report

:finish
if exist "%JOBDIR%" rmdir /s /q "%JOBDIR%" >nul 2>nul
echo.
echo Concluido.
pause
exit /b

:wait_for_convert_slot
set "RUNNING=0"
for /f %%A in ('dir /b "%JOBDIR%\convert_*.run" 2^>nul ^| find /c /v ""') do set "RUNNING=%%A"
if %RUNNING% GEQ %MAX_CONVERT_PARALLEL% (
    timeout /t 1 /nobreak >nul
    goto wait_for_convert_slot
)
exit /b

:wait_convert_all
set "RUNNING=0"
for /f %%A in ('dir /b "%JOBDIR%\convert_*.run" 2^>nul ^| find /c /v ""') do set "RUNNING=%%A"
if %RUNNING% GTR 0 (
    timeout /t 1 /nobreak >nul
    goto wait_convert_all
)
exit /b

:wait_for_transcribe_slot
set "RUNNING=0"
for /f %%A in ('dir /b "%JOBDIR%\trans_*.run" 2^>nul ^| find /c /v ""') do set "RUNNING=%%A"
if %RUNNING% GEQ %MAX_TRANSCRIBE_PARALLEL% (
    timeout /t 1 /nobreak >nul
    goto wait_for_transcribe_slot
)
exit /b

:wait_transcribe_all
set "RUNNING=0"
for /f %%A in ('dir /b "%JOBDIR%\trans_*.run" 2^>nul ^| find /c /v ""') do set "RUNNING=%%A"
if %RUNNING% GTR 0 (
    timeout /t 1 /nobreak >nul
    goto wait_transcribe_all
)
exit /b

:write_html_report
if not exist "%MANIFEST%" exit /b
set "HTML_FILE=%OUTDIR%\transcricoes.html"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$manifest=$env:MANIFEST; $out=$env:OUTDIR; $htmlFile=Join-Path $out 'transcricoes.html'; $rows=@(); if(Test-Path $manifest){ foreach($line in [IO.File]::ReadLines($manifest)){ if([string]::IsNullOrWhiteSpace($line)){ continue }; $parts=$line.Split([char]124,2); $wav=$parts[0]; $orig=if($parts.Count -gt 1){ $parts[1] } else { [IO.Path]::GetFileName($wav) }; $txt=Join-Path $out ([IO.Path]::GetFileNameWithoutExtension($wav)+'.txt'); $text=if(Test-Path $txt){ [IO.File]::ReadAllText($txt) } else { '(sem transcricao)' }; $rows += [pscustomobject]@{'Arquivo original'=$orig;'Transcricao'=$text} } }; $head='<meta charset=\"utf-8\"><style>body{font-family:Arial,sans-serif;background:#101417;color:#e8f4f2;margin:24px}h1{font-size:24px}table{border-collapse:collapse;width:100%%}th,td{border:1px solid #334047;padding:10px;vertical-align:top}th{background:#182127;text-align:left}td:first-child{width:32%%;font-family:Consolas,monospace;color:#9ee7ff;word-break:break-all}td{white-space:pre-wrap}</style>'; $html=$rows | ConvertTo-Html -Title 'Transcricoes' -Head $head -PreContent '<h1>Transcricoes</h1>'; $enc=New-Object System.Text.UTF8Encoding $false; [IO.File]::WriteAllText($htmlFile,($html -join [Environment]::NewLine),$enc)"
if exist "%HTML_FILE%" echo HTML gerado: %HTML_FILE%
exit /b

:convert_worker
setlocal EnableExtensions
set "INPUT_FILE=%~2"
set "WAVFILE=%~3"
set "OUT_FILE=%~4"
set "DONE_FILE=%~5"
set "RUN_FILE=%DONE_FILE:.done=.run%"
set "FFLOG=%DONE_FILE%.ffmpeg.log"

ffmpeg.exe -hide_banner -y -i "%INPUT_FILE%" -vn -ac 1 -ar 16000 -c:a pcm_s16le "%WAVFILE%" > "%FFLOG%" 2>&1

if exist "%WAVFILE%" (
    > "%DONE_FILE%" echo OK convertido: %~nx2
    > "%DONE_FILE%.map" echo %WAVFILE%^|%~nx2
) else (
    > "%OUT_FILE%" echo ERRO: falha ao converter %~nx2
    >> "%OUT_FILE%" echo.
    if exist "%FFLOG%" type "%FFLOG%" >> "%OUT_FILE%"
    > "%DONE_FILE%" echo ERRO conversao: %~nx2
)

if exist "%RUN_FILE%" del /q "%RUN_FILE%" >nul 2>nul
endlocal
exit /b

:transcribe_worker
setlocal EnableExtensions
set "WAVFILE=%~2"
set "OUT_FILE=%~3"
set "SERVER=%~4"
set "DONE_FILE=%~5"
set "RAW_FILE=%~6"
set "RUN_FILE=%DONE_FILE:.done=.run%"
set "RESP=%DONE_FILE%.response.json"
set "CODE=%DONE_FILE%.http.code"
set "RESP_FILE=%RESP%"

curl.exe -sS -X POST "%SERVER%" -H "accept: application/json" -F "files=@%WAVFILE%;type=audio/wav" -o "%RESP%" -w "%%{http_code}" > "%CODE%"

set "HTTP=000"
if exist "%CODE%" set /p HTTP=<"%CODE%"

if not "%HTTP%"=="200" (
    if exist "%RESP%" copy /y "%RESP%" "%RAW_FILE%" >nul 2>nul
    > "%OUT_FILE%" echo ERRO HTTP %HTTP%
    >> "%OUT_FILE%" echo Arquivo enviado: %~nx2
    >> "%OUT_FILE%" echo Servidor: %SERVER%
    >> "%OUT_FILE%" echo.
    if exist "%RESP%" type "%RESP%" >> "%OUT_FILE%"
    > "%DONE_FILE%" echo ERRO HTTP %HTTP%: %~nx2
) else (
    if exist "%RESP%" copy /y "%RESP%" "%RAW_FILE%" >nul 2>nul
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$raw=[IO.File]::ReadAllText($env:RESP_FILE); function Get-GraniteText($x){ if($null -eq $x){ return @() }; if($x -is [string]){ if($x.Trim()){ return @($x) } else { return @() } }; if(($x -is [System.Collections.IEnumerable]) -and -not ($x -is [string]) -and -not ($x -is [pscustomobject])){ $r=@(); foreach($i in $x){ $r += Get-GraniteText $i }; return $r }; $props=$x.PSObject.Properties; foreach($k in 'text','transcription','transcript','result','output'){ $p=$props[$k]; if($p -and $null -ne $p.Value -and (''+$p.Value).Trim()){ return @(''+$p.Value) } }; foreach($k in 'results','files','items','data','transcriptions'){ $p=$props[$k]; if($p){ $n=Get-GraniteText $p.Value; if($n.Count){ return $n } } }; $seg=$props['segments']; if($seg){ $n=Get-GraniteText $seg.Value; if($n.Count){ return @($n -join '') } }; $all=@(); foreach($p in $props){ $all += Get-GraniteText $p.Value }; return $all }; try{ $json=$raw | ConvertFrom-Json -ErrorAction Stop; $texts=Get-GraniteText $json; if($texts.Count){ $txt=$texts -join [Environment]::NewLine } else { $txt=$raw } } catch { $txt=$raw }; $enc=New-Object System.Text.UTF8Encoding $false; [IO.File]::WriteAllText($env:OUT_FILE,$txt,$enc)"
    > "%DONE_FILE%" echo OK transcrito: %~nx2
)

if exist "%RESP%" del /q "%RESP%" >nul 2>nul
if exist "%CODE%" del /q "%CODE%" >nul 2>nul
if exist "%RUN_FILE%" del /q "%RUN_FILE%" >nul 2>nul
endlocal
exit /b
