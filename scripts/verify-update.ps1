param(
    [Parameter(Mandatory = $true)]
    [string]$CandidateApk,
    [string]$InstalledApk
)

$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME) { throw 'ANDROID_HOME Android SDK klasörünü göstermeli.' }
$buildToolsRoot = Join-Path $env:ANDROID_HOME 'build-tools'
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android Build Tools bulunamadı.' }
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$candidate = (Resolve-Path -LiteralPath $CandidateApk).Path

& $apksigner verify --verbose --print-certs $candidate
if ($LASTEXITCODE -ne 0) { throw 'Aday APK imza doğrulamasından geçemedi.' }
& $aapt dump badging $candidate | Select-Object -First 1

if ($InstalledApk) {
    $installed = (Resolve-Path -LiteralPath $InstalledApk).Path
    $candidateCert = (& $apksigner verify --print-certs $candidate | Select-String 'Signer #1 certificate SHA-256 digest').Line
    $installedCert = (& $apksigner verify --print-certs $installed | Select-String 'Signer #1 certificate SHA-256 digest').Line
    if (-not $candidateCert -or -not $installedCert -or $candidateCert -ne $installedCert) {
        throw 'APK sertifikaları eşleşmiyor; bu dosya mevcut kurulumu güncelleyemez.'
    }
    Write-Host 'İmzalar eşleşiyor; aday APK mevcut uygulamanın üzerine kurulabilir.'
}
