[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputFile,
    [string]$OutputDir,
    [ValidateRange(0.00001, 1.0)][double]$Threshold = 0.1,
    [ValidateRange(1, 20)][int]$MaxClips = 3,
    [ValidateSet('auto', 'cuda', 'cpu')][string]$Device = 'auto'
)

$ErrorActionPreference = 'Stop'
$resolvedInput = (Resolve-Path -LiteralPath $InputFile).Path
$resolvedOutput = if ($OutputDir) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDir) } else { $null }
$projectDir = $PSScriptRoot
$environmentDir = Join-Path $projectDir 'build\tools\highlight-venv'
$pythonExe = Join-Path $environmentDir 'Scripts\python.exe'
$requirements = Join-Path $projectDir 'tools\highlight_pipeline\requirements.txt'
$cudaRequirements = Join-Path $projectDir 'tools\highlight_pipeline\requirements-cuda.txt'
$hasUv = $null -ne (Get-Command uv -ErrorAction SilentlyContinue)

Push-Location -LiteralPath $projectDir
try {
    if (-not (Test-Path -LiteralPath $pythonExe)) {
        if ($hasUv) { & uv venv --python 3.13 $environmentDir }
        else { & py -3.13 -m venv $environmentDir }
        if ($LASTEXITCODE -ne 0) { throw 'Python 3.13 environment creation failed. Install Python 3.13 or uv.' }
    }
    $useCuda = $Device -eq 'cuda' -or ($Device -eq 'auto' -and $null -ne (Get-Command nvidia-smi -ErrorAction SilentlyContinue))
    $requirementsKey = (Get-FileHash -Algorithm SHA256 -LiteralPath $requirements).Hash + ':' +
        (Get-FileHash -Algorithm SHA256 -LiteralPath $cudaRequirements).Hash + ':' + $useCuda
    $stamp = Join-Path $environmentDir 'requirements-key.txt'
    $oldKey = if (Test-Path -LiteralPath $stamp) { (Get-Content -Raw -LiteralPath $stamp).Trim() } else { '' }
    if ($oldKey -ne $requirementsKey) {
        $installArgs = @('-r', $requirements)
        if ($useCuda) { $installArgs += @('-r', $cudaRequirements) }
        if ($hasUv) { & uv pip install --python $pythonExe @installArgs }
        else { & $pythonExe -m pip install @installArgs }
        if ($LASTEXITCODE -ne 0) { throw 'Model runtime installation failed.' }
        Set-Content -Encoding UTF8 -LiteralPath $stamp -Value $requirementsKey
    }
    $env:PYTHONIOENCODING = 'utf-8'
    $arguments = @('-m', 'tools.highlight_pipeline', '--input', $resolvedInput, '--threshold',
        $Threshold.ToString([System.Globalization.CultureInfo]::InvariantCulture), '--max-clips', $MaxClips.ToString(), '--device', $Device)
    if ($resolvedOutput) { $arguments += @('--output', $resolvedOutput) }
    & $pythonExe @arguments
    $pipelineExitCode = $LASTEXITCODE
}
finally { Pop-Location }
exit $pipelineExitCode
