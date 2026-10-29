param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $Apk).Path
$adb = if ($env:ANDROID_HOME) {
    Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
} else {
    (Get-Command adb -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath $adb)) { throw "adb bulunamadı: $adb" }

$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
& $adb @deviceArgs install -r $resolvedApk
if ($LASTEXITCODE -ne 0) { throw "APK güncellenemedi: $LASTEXITCODE" }

Write-Host 'Güncelleme tamamlandı; uygulama verisi korunarak mevcut kurulumun üzerine yazıldı.'
