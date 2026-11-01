[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

Write-Output "=== 1. Building PrivSnap APK & Verifying Zero Permissions ==="
& "$PSScriptRoot\build-android.ps1"

$Java = $env:JAVA_HOME
if (-not $Java -or -not (Test-Path -LiteralPath (Join-Path $Java 'bin\javac.exe'))) {
    if (Test-Path -LiteralPath 'C:\Java\jdk-17.0.11\bin\javac.exe') { $Java = 'C:\Java\jdk-17.0.11' }
    else { $Java = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source) }
}
$Sdk = $env:ANDROID_HOME
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$platform = Get-ChildItem -LiteralPath (Join-Path $Sdk 'platforms') -Directory | Where-Object { $_.Name -match '^android-\d+$' } | Sort-Object { [int]($_.Name -replace 'android-','') } -Descending | Select-Object -First 1
$androidJar = Join-Path $platform.FullName 'android.jar'

Write-Output "=== 2. Running PrivSnap Binary EXIF/GPS & Scrubber Unit Tests ==="
$testBuild = Join-Path $projectRoot 'app\build\test-classes'
New-Item -ItemType Directory -Force -Path $testBuild | Out-Null
$mainClasses = Join-Path $projectRoot 'app\build\sdk-release\classes'
& "$Java\bin\javac.exe" -encoding UTF-8 -source 17 -target 17 -cp "$mainClasses;$androidJar" -d $testBuild "$projectRoot\app\src\test\java\io\privsnap\app\PrivSnapTests.java"
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed" }
& "$Java\bin\java.exe" -cp "$testBuild;$mainClasses;$androidJar" io.privsnap.app.PrivSnapTests
if ($LASTEXITCODE -ne 0) { throw "Unit tests failed" }
