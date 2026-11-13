$ErrorActionPreference = 'Stop'
$basePython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
$labEnvironment = Join-Path $PSScriptRoot '.lab-venv'
if (-not (Test-Path -LiteralPath $basePython)) { throw 'Prepare server/.venv first.' }
if (-not (Test-Path -LiteralPath (Join-Path $labEnvironment 'Scripts\python.exe'))) {
    & uv venv $labEnvironment --python $basePython
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$sharedPackages = Join-Path $PSScriptRoot '.venv\Lib\site-packages'
$sharedFile = Join-Path $labEnvironment 'Lib\site-packages\momo_shared.pth'
if (Test-Path -LiteralPath $sharedFile) { Remove-Item -LiteralPath $sharedFile }
try {
    & uv pip install --python (Join-Path $labEnvironment 'Scripts\python.exe') -r (Join-Path $PSScriptRoot 'audio-lab-requirements.txt')
    $labSetupExit = $LASTEXITCODE
} finally {
    [System.IO.File]::WriteAllText($sharedFile, "$sharedPackages`n", [System.Text.UTF8Encoding]::new($false))
}
if ($labSetupExit -ne 0) { exit $labSetupExit }
Write-Host 'Audio Lab environment ready. Run .\topic-lab.ps1'
