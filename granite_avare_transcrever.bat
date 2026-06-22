@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SERVER=http://100.110.211.23:8100/transcribe"
set "OUTDIR=%CD%\out"

if not exist "%OUTDIR%" mkdir "%OUTDIR%"

echo Granite Avare - transcricao em lote
echo Servidor: %SERVER%
echo Pasta: %CD%
echo Saida: %OUTDIR%
echo.

set /a COUNT=0

for %%F in (*.wav *.mp3 *.m4a *.ogg *.opus *.flac *.aac *.wma) do (
    if exist "%%~fF" (
        set /a COUNT+=1
        set "EXT=%%~xF"
        set "MIME=application/octet-stream"

        if /I "!EXT!"==".wav" set "MIME=audio/wav"
        if /I "!EXT!"==".mp3" set "MIME=audio/mpeg"
        if /I "!EXT!"==".m4a" set "MIME=audio/mp4"
        if /I "!EXT!"==".ogg" set "MIME=audio/ogg"
        if /I "!EXT!"==".opus" set "MIME=audio/opus"
        if /I "!EXT!"==".flac" set "MIME=audio/flac"
        if /I "!EXT!"==".aac" set "MIME=audio/aac"
        if /I "!EXT!"==".wma" set "MIME=audio/x-ms-wma"

        set "RESP=%TEMP%\granite_avare_!RANDOM!_!COUNT!.json"
        set "CODE=!RESP!.code"
        set "RESP_FILE=!RESP!"
        set "OUT_FILE=%OUTDIR%\%%~nF.txt"

        echo [!COUNT!] Enviando: %%~nxF

        curl.exe -sS -X POST "!SERVER!" -H "accept: application/json" -F "files=@%%~fF;type=!MIME!" -o "!RESP!" -w "%%{http_code}" > "!CODE!"

        set "HTTP=000"
        if exist "!CODE!" set /p HTTP=<"!CODE!"

        if not "!HTTP!"=="200" (
            echo ERRO HTTP !HTTP!: %%~nxF
            > "!OUT_FILE!" echo ERRO HTTP !HTTP!
            >> "!OUT_FILE!" echo Arquivo: %%~nxF
            >> "!OUT_FILE!" echo Servidor: !SERVER!
            >> "!OUT_FILE!" echo.
            if exist "!RESP!" type "!RESP!" >> "!OUT_FILE!"
        ) else (
            powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$raw=[IO.File]::ReadAllText($env:RESP_FILE); function Get-GraniteText($x){ if($null -eq $x){ return @() }; if($x -is [string]){ if($x.Trim()){ return @($x) } else { return @() } }; if(($x -is [System.Collections.IEnumerable]) -and -not ($x -is [string]) -and -not ($x -is [pscustomobject])){ $r=@(); foreach($i in $x){ $r += Get-GraniteText $i }; return $r }; $props=$x.PSObject.Properties; foreach($k in 'text','transcription','transcript','result','output'){ $p=$props[$k]; if($p -and $null -ne $p.Value -and (''+$p.Value).Trim()){ return @(''+$p.Value) } }; foreach($k in 'results','files','items','data','transcriptions'){ $p=$props[$k]; if($p){ $n=Get-GraniteText $p.Value; if($n.Count){ return $n } } }; $seg=$props['segments']; if($seg){ $n=Get-GraniteText $seg.Value; if($n.Count){ return @($n -join '') } }; $all=@(); foreach($p in $props){ $all += Get-GraniteText $p.Value }; return $all }; try{ $json=$raw | ConvertFrom-Json -ErrorAction Stop; $texts=Get-GraniteText $json; if($texts.Count){ $txt=$texts -join [Environment]::NewLine } else { $txt=$raw } } catch { $txt=$raw }; $enc=New-Object System.Text.UTF8Encoding $false; [IO.File]::WriteAllText($env:OUT_FILE,$txt,$enc)"
            echo OK: out\%%~nF.txt
        )

        if exist "!RESP!" del /q "!RESP!" >nul 2>nul
        if exist "!CODE!" del /q "!CODE!" >nul 2>nul
        echo.
    )
)

if "%COUNT%"=="0" (
    echo Nenhum audio encontrado nesta pasta.
    echo Extensoes procuradas: wav, mp3, m4a, ogg, opus, flac, aac, wma
)

echo Concluido.
pause
