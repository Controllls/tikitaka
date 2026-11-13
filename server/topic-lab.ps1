param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 8770,
    [switch]$Restart,
    [ValidateSet('gpu', 'cpu')]
    [string]$Device = 'gpu',
    [string]$CudaRoot
)
$ErrorActionPreference = 'Stop'
$labPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.lab-venv\Scripts\python.exe')) {
    $labPython = Join-Path $PSScriptRoot '.lab-venv\Scripts\python.exe'
}
if (-not (Test-Path -LiteralPath $labPython)) {
    throw 'Prepare the backend environment first: cd server; uv sync --frozen'
}
if ($Restart) {
    $labListeners = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    foreach ($labListener in $labListeners) {
        $labExisting = Get-CimInstance Win32_Process -Filter "ProcessId = $($labListener.OwningProcess)"
        if ($labExisting.CommandLine -notmatch 'momo\.topic_lab:create_app' -or $labExisting.CommandLine -notmatch "--port $Port(\s|$)") {
            throw "Port $Port belongs to another program. It was not stopped."
        }
        Stop-Process -Id $labExisting.ProcessId
    }
}
Write-Host "MOMO Video Lab: http://127.0.0.1:$Port"
Write-Host "Local STT / Qwen device: $Device"
Write-Host 'Keep this terminal open. Press Ctrl+C to stop.'
Push-Location $PSScriptRoot
$labPreviousDevice = $env:MOMO_LAB_DEVICE
$labPreviousCuda = $env:MOMO_CUDA_LIBRARY_ROOT
try {
    $env:MOMO_LAB_DEVICE = $Device
    if ($CudaRoot) { $env:MOMO_CUDA_LIBRARY_ROOT = $CudaRoot }
    & $labPython -m uvicorn momo.topic_lab:create_app --factory --host 127.0.0.1 --port $Port --no-access-log
    $labExitCode = $LASTEXITCODE
} finally {
    $env:MOMO_LAB_DEVICE = $labPreviousDevice
    $env:MOMO_CUDA_LIBRARY_ROOT = $labPreviousCuda
    Pop-Location
}
exit $labExitCode
