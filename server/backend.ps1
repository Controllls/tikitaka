param(
    [ValidateSet('start', 'run', 'status', 'stop')]
    [string]$Action = 'status',
    [ValidateSet('cuda', 'cpu')]
    [string]$Device = 'cuda',
    [int]$Port = 8765,
    [string]$CudaRoot = '',
    [string]$Models = ''
)
$ErrorActionPreference = 'Stop'
$backendPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $backendPython)) {
    throw 'Prepare the backend first: cd server; uv sync --frozen --extra inference'
}
$backendArguments = @((Join-Path $PSScriptRoot 'manage.py'), $Action, '--port', "$Port", '--device', $Device)
if ($CudaRoot) { $backendArguments += @('--cuda-root', $CudaRoot) }
if ($Models) { $backendArguments += @('--models', $Models) }
& $backendPython @backendArguments
exit $LASTEXITCODE
