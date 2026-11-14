param(
    [ValidatePattern('^[a-zA-Z0-9._:-]+$')]
    [string]$Serial = 'emulator-5554'
)

# Forward emulator playback to the current Windows output without opening another window.
# Run again after restarting the emulator. Existing connections are reused.
$ErrorActionPreference = 'Stop'
$existing = Get-CimInstance Win32_Process -Filter "Name = 'scrcpy.exe'" |
    Where-Object {
        $_.CommandLine -match ([regex]::Escape("--serial=$Serial") + '(\s|$)') -and
        $_.CommandLine -match '--audio-source=playback'
    } | Select-Object -First 1
if ($existing) {
    Write-Output "Audio forwarding is already running for $Serial (PID $($existing.ProcessId))."
    return
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw "ADB was not found: $adbPath" }
$deviceState = & $adbPath -s $Serial get-state 2>$null
if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') { throw "Start emulator $Serial first." }

$toolRoot = Join-Path $repoRoot 'build\tools'
$version = '5.0.1'
$package = "scrcpy-win64-v$version"
$bridgeExe = Join-Path $toolRoot "$package\scrcpy.exe"
if (-not (Test-Path -LiteralPath $bridgeExe)) {
    New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
    $archive = Join-Path $toolRoot "$package.zip"
    Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/Genymobile/scrcpy/releases/download/v$version/$package.zip" -OutFile $archive
    $expected = 'B12A2C4EE8BE317422451FC7DCF8EE20A71B5EA7EF9AD73DDD825A25316227A5'
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) {
        throw 'The audio tool download failed its checksum check.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot -Force
}

$logRoot = Join-Path $repoRoot 'build\emulator-audio'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$previousAdb = $env:ADB
try {
    $env:ADB = $adbPath
    $bridge = Start-Process -FilePath $bridgeExe -ArgumentList "--serial=$Serial",
        '--no-window', '--no-video', '--no-control', '--audio-source=playback',
        '--audio-dup', '--audio-codec=raw', '--audio-buffer=80', '--require-audio' `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logRoot "$Serial.log") `
        -RedirectStandardError (Join-Path $logRoot "$Serial-error.log")
    if ($bridge.WaitForExit(1500)) { throw "Audio forwarding failed. See $logRoot." }
    Write-Output "Audio forwarding started for $Serial (PID $($bridge.Id))."
} finally {
    $env:ADB = $previousAdb
}
