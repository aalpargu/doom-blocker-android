param(
    [string]$Apk,
    [string]$Serial,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$packageName = 'dev.personal.doomblocker'
$activityName = 'dev.personal.doomblocker.ui.MainActivity'

$adb = if ($env:ANDROID_HOME) {
    Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
} else {
    (Get-Command adb -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath $adb)) { throw "adb bulunamadı: $adb" }

$deviceLines = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'ADB cihaz listesi okunamadı.' }
$connected = @(
    $deviceLines |
        Select-Object -Skip 1 |
        ForEach-Object { ($_ -split '\s+')[0..1] -join ' ' } |
        Where-Object { $_ -match ' device$' } |
        ForEach-Object { ($_ -split ' ')[0] }
)

if ($Serial) {
    if ($Serial -notin $connected) { throw "Seçilen cihaz bağlı ve yetkili değil: $Serial" }
    $selectedSerial = $Serial
} elseif ($connected.Count -eq 1) {
    $selectedSerial = $connected[0]
} elseif ($connected.Count -eq 0) {
    throw 'Bağlı ve yetkili ADB cihazı bulunamadı. USB hata ayıklamayı açıp RSA onayını verin.'
} else {
    throw "Birden fazla cihaz var. -Serial ile birini seçin: $($connected -join ', ')"
}

$deviceArgs = @('-s', $selectedSerial)
if (-not $SkipInstall) {
    $candidate = if ($Apk) {
        (Resolve-Path -LiteralPath $Apk).Path
    } else {
        Join-Path $projectRoot 'app\build\outputs\apk\production\release\app-production-release.apk'
    }
    if (-not (Test-Path -LiteralPath $candidate)) { throw "APK bulunamadı: $candidate" }
    & $adb @deviceArgs install -r $candidate
    if ($LASTEXITCODE -ne 0) { throw "APK kurulamadı: $LASTEXITCODE" }
}

$launchOutput = & $adb @deviceArgs shell am start -W -n "$packageName/$activityName"
if ($LASTEXITCODE -ne 0 -or ($launchOutput -join "`n") -match 'Error:') {
    throw "Uygulama açılamadı:`n$($launchOutput -join "`n")"
}

$packageDump = & $adb @deviceArgs shell dumpsys package $packageName
$versionName = ($packageDump | Select-String -Pattern 'versionName=' | Select-Object -First 1).Line.Trim()
$versionCode = ($packageDump | Select-String -Pattern 'versionCode=' | Select-Object -First 1).Line.Trim()
$accessibility = (& $adb @deviceArgs shell settings get secure enabled_accessibility_services) -join ''
$serviceEnabled = $accessibility -match [regex]::Escape($packageName) -and
    $accessibility -match [regex]::Escape('BlockerAccessibilityService')
$model = ((& $adb @deviceArgs shell getprop ro.product.model) -join '').Trim()
$sdk = ((& $adb @deviceArgs shell getprop ro.build.version.sdk) -join '').Trim()

Write-Host "Cihaz: $model · API $sdk · $selectedSerial"
Write-Host "Paket: $versionName · $versionCode"
Write-Host "Erişilebilirlik servisi: $(if ($serviceEnabled) { 'açık' } else { 'kapalı — uygulamadaki kurulum kartından açın' })"
Write-Host 'Uygulama başarıyla kuruldu/açıldı. Şimdi Ayarlar > Koruma testi ile gerçek Home eylemini doğrulayın.'
