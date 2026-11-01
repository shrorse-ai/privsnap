param([string]$Sdk = $env:ANDROID_HOME, [string]$Java = $env:JAVA_HOME)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $Java -or $Java -match '1\.8' -or -not (Test-Path -LiteralPath (Join-Path $Java 'bin\javac.exe'))) {
    if (Test-Path -LiteralPath 'C:\Java\jdk-17.0.11\bin\javac.exe') { $Java = 'C:\Java\jdk-17.0.11' }
    else { $Java = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source) }
}
$platform = Get-ChildItem -LiteralPath (Join-Path $Sdk 'platforms') -Directory | Where-Object { $_.Name -match '^android-\d+$' } | Sort-Object { [int]($_.Name -replace 'android-','') } -Descending | Select-Object -First 1
$tools = Get-ChildItem -LiteralPath (Join-Path $Sdk 'build-tools') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$androidJar = Join-Path $platform.FullName 'android.jar'
$artifactDir = Join-Path $projectRoot 'artifacts'
$env:JAVA_HOME = $Java

function Run-Tool([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $exe (exit $LASTEXITCODE)" }
}

$gradleText = [IO.File]::ReadAllText("$projectRoot\app\build.gradle")
$verCode = [regex]::Match($gradleText, 'versionCode\s+(\d+)').Groups[1].Value
$verName = [regex]::Match($gradleText, "versionName\s+'([^']+)'").Groups[1].Value

$build = Join-Path $projectRoot 'app\build\sdk-release'
foreach ($folder in @($build, "$build\classes", "$build\generated", "$build\dex", $artifactDir)) {
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
}
Get-ChildItem -LiteralPath "$build\classes" -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

$package = 'io.privsnap.app'
$manifest = [IO.File]::ReadAllText("$projectRoot\app\src\main\AndroidManifest.xml").Replace('${applicationId}', $package)
$manifest = $manifest.Replace('<manifest ', '<manifest package="io.privsnap.app" ')
[IO.File]::WriteAllText("$build\AndroidManifest.xml", $manifest, [Text.UTF8Encoding]::new($false))

Run-Tool "$($tools.FullName)\aapt2.exe" @('compile','--dir',"$projectRoot\app\src\main\res",'-o',"$build\resources.zip")
Run-Tool "$($tools.FullName)\aapt2.exe" @(
    'link','-o',"$build\resources.apk",
    '-I',$androidJar,
    '--manifest',"$build\AndroidManifest.xml",
    '-A',"$projectRoot\app\src\main\assets",
    '--java',"$build\generated",
    '--custom-package','io.privsnap.app',
    '--rename-manifest-package',$package,
    '--min-sdk-version','26',
    '--target-sdk-version','35',
    '--version-code',$verCode,
    '--version-name',$verName,
    "$build\resources.zip"
)

[IO.File]::WriteAllText("$build\generated\io\privsnap\app\BuildConfig.java", "package io.privsnap.app; public final class BuildConfig { public static final boolean DEBUG = false; }", [Text.UTF8Encoding]::new($false))

$sources = @(Get-ChildItem -LiteralPath "$projectRoot\app\src\main\java" -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }) + @(Get-ChildItem -LiteralPath "$build\generated" -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$classes = "$build\classes"
Run-Tool "$Java\bin\javac.exe" (@('-encoding','UTF-8','-source','17','-target','17','-classpath',$androidJar,'-d',$classes) + $sources)
Run-Tool "$Java\bin\jar.exe" @('--create','--file',"$build\classes.jar",'-C',$classes,'.')
Run-Tool "$($tools.FullName)\d8.bat" @('--min-api','26','--lib',$androidJar,'--output',"$build\dex","$build\classes.jar")
Copy-Item -LiteralPath "$build\resources.apk" -Destination "$build\combined.apk" -Force
Run-Tool "$Java\bin\jar.exe" @('--update','--file',"$build\combined.apk",'-C',"$build\dex",'classes.dex')

$output = Join-Path $artifactDir "privsnap-$verName.apk"
Run-Tool "$($tools.FullName)\zipalign.exe" @('-f','-p','4',"$build\combined.apk",$output)

$privateDir = Join-Path $artifactDir 'private'
New-Item -ItemType Directory -Force -Path $privateDir | Out-Null
$key = Join-Path $privateDir 'privsnap-release.jks'
if (-not (Test-Path -LiteralPath $key)) {
    Run-Tool "$Java\bin\keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','privsnap2026','-keypass','privsnap2026','-alias','privsnap','-dname','CN=PrivSnap Offline Privacy,O=PrivSnap','-keyalg','RSA','-keysize','2048','-validity','10000')
}
Run-Tool "$($tools.FullName)\apksigner.bat" @('sign','--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--ks',$key,'--ks-pass','pass:privsnap2026','--key-pass','pass:privsnap2026','--ks-key-alias','privsnap',$output)

$perms = & "$($tools.FullName)\aapt2.exe" dump permissions $output
if ($perms -match 'uses-permission') {
    throw "SECURITY VIOLATION: APK declared permissions: $perms"
}

$size = (Get-Item -LiteralPath $output).Length
$kb = [math]::Round($size / 1024, 1)
Write-Output "SUCCESS: $output ($size bytes / $kb KB) | Verified 0 Permissions"
if ($size -gt 200KB) { throw "APK exceeds 200 KB budget ($kb KB)" }
