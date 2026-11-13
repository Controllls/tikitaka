param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('Off', 'StandBy', 'Ready', 'On', 'Driving')]
    [string]$State,
    [ValidateRange(1, 65535)]
    [int]$Port = 8088
)
$ErrorActionPreference = 'Stop'
$consoleBase = "http://127.0.0.1:$Port"
$power = Invoke-RestMethod -Uri "$consoleBase/vehicle/power-system" -TimeoutSec 5
if (-not $power.bridge_ready -or -not $power.emulator_active) {
    throw 'Virtual Console power bridge is unavailable. Check the running Pleos emulator and ADB forward.'
}
Invoke-RestMethod -Method Put -Uri "$consoleBase/vehicle/power-system/simulator?area_id=0&state=$State" -TimeoutSec 5 | Out-Null
$power = Invoke-RestMethod -Uri "$consoleBase/vehicle/power-system" -TimeoutSec 5
[PSCustomObject]@{
    Console = $consoleBase
    RequestedState = $State
    ActualState = $power.actual.'0'.state
    TimestampMs = $power.actual.'0'.timestamp_ms
    BridgeReady = $power.bridge_ready
}
