# Verifica o contrato estável entre o daemon Gradle, o CI e o bytecode do app.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Require-Match {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label ausente: $Path"
    }
    $text = Get-Content -LiteralPath $Path -Raw
    if ($text -notmatch $Pattern) {
        throw "$Label não corresponde ao contrato"
    }
}

Require-Match `
    -Path (Join-Path $root "gradle\gradle-daemon-jvm.properties") `
    -Pattern '(?m)^toolchainVersion=21\s*$' `
    -Label "JDK do daemon Gradle"

Require-Match `
    -Path (Join-Path $root ".github\workflows\validation.yml") `
    -Pattern 'java-version:\s*[''"]21[''"]' `
    -Label "JDK do CI"

Require-Match `
    -Path (Join-Path $root "gradle\wrapper\gradle-wrapper.properties") `
    -Pattern 'distributionUrl=.*gradle-9\.7\.0-bin\.zip' `
    -Label "versão do Gradle wrapper"

Require-Match `
    -Path (Join-Path $root "app\build.gradle") `
    -Pattern 'jvmToolchain\(17\)' `
    -Label "toolchain Java do app"

Require-Match `
    -Path (Join-Path $root "app\build.gradle") `
    -Pattern 'sourceCompatibility\s+JavaVersion\.VERSION_17\s+targetCompatibility\s+JavaVersion\.VERSION_17' `
    -Label "bytecode Java do app"

Require-Match `
    -Path (Join-Path $root "README.txt") `
    -Pattern 'JDK 21.*Gradle' `
    -Label "documentação do JDK de execução"

$gradleWrapper = Join-Path $root "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper ausente: $gradleWrapper"
}

$gradleVersionOutput = (& $gradleWrapper --version 2>&1 | Out-String).Trim()
$gradleVersionExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
if ($gradleVersionExitCode -ne 0) {
    throw "Gradle wrapper não conseguiu informar a versão: $gradleVersionOutput"
}
if ($gradleVersionOutput -notmatch '(?im)^Gradle\s+9\.7\.0\s*$') {
    throw "Gradle wrapper não está em 9.7.0"
}
if ($gradleVersionOutput -notmatch '(?im)^Launcher JVM:\s+21(?:\.|\s)') {
    throw "launcher Gradle não está usando JDK 21"
}
if ($gradleVersionOutput -notmatch '(?im)^Daemon JVM:\s+.*Java 21') {
    throw "daemon Gradle não confirmou Java 21"
}

if (-not $Quiet) {
    Write-Output "OK: Gradle 9.7.0 com launcher/daemon Java 21 e app em toolchain/bytecode Java 17"
}
exit 0
