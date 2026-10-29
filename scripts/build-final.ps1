param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\dist'),
    [switch]$SkipLint
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradle = Join-Path $projectRoot 'gradlew.bat'

if (-not $env:JAVA_HOME) { throw 'JAVA_HOME bir JDK 17 klasörünü göstermeli.' }
if (-not $env:ANDROID_HOME) { throw 'ANDROID_HOME Android SDK klasörünü göstermeli.' }

$tasks = @(
    ':core:test',
    ':app:testProductionReleaseUnitTest',
    ':app:testLabDebugUnitTest',
    ':app:assembleProductionRelease',
    ':app:assembleLabDebug'
)
if (-not $SkipLint) { $tasks += @(':app:lintProductionRelease', ':app:lintLabDebug') }

& $gradle --no-daemon --max-workers=1 @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle doğrulaması başarısız: $LASTEXITCODE" }

$buildFile = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
$match = [regex]::Match($buildFile, 'versionName\s*=\s*"([^"]+)"')
if (-not $match.Success) { throw 'versionName okunamadı.' }
$version = $match.Groups[1].Value

$productionSource = Join-Path $projectRoot 'app\build\outputs\apk\production\release\app-production-release.apk'
$labSource = Join-Path $projectRoot 'app\build\outputs\apk\lab\debug\app-lab-debug.apk'
if (-not (Test-Path -LiteralPath $productionSource)) { throw "Üretim APK'sı bulunamadı: $productionSource" }
if (-not (Test-Path -LiteralPath $labSource)) { throw "Lab APK'sı bulunamadı: $labSource" }

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$productionTarget = Join-Path $resolvedOutput "doom-blocker-$version-production-release.apk"
$labTarget = Join-Path $resolvedOutput "doom-blocker-$version-lab-debug.apk"
Copy-Item -LiteralPath $productionSource -Destination $productionTarget -Force
Copy-Item -LiteralPath $labSource -Destination $labTarget -Force

$hashes = Get-FileHash -Algorithm SHA256 -LiteralPath $productionTarget, $labTarget
$hashLines = $hashes | ForEach-Object { "$($_.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($_.Path))" }
Set-Content -LiteralPath (Join-Path $resolvedOutput 'SHA256SUMS.txt') -Value $hashLines -Encoding utf8NoBOM

Write-Host "Hazır: $productionTarget"
Write-Host "Hızlı test çeşidi: $labTarget"
