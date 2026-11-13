$ErrorActionPreference = 'Stop'
$labPython = Join-Path $PSScriptRoot '.lab-venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $labPython)) { throw 'Run setup-audio-lab.ps1 first.' }
$modelDirectory = Get-Item -LiteralPath (Join-Path $PSScriptRoot 'data\models')
$resolvedModels = if ($modelDirectory.Target) { [string]$modelDirectory.Target } else { $modelDirectory.FullName }
$dependencyRoot = Join-Path (Split-Path -Parent $resolvedModels) 'lab-torch-cu124'
$cacheDirectory = Join-Path (Split-Path -Parent $resolvedModels) 'uv-cache'
& uv pip install --python $labPython --target $dependencyRoot --cache-dir $cacheDirectory --no-deps --index-url https://download.pytorch.org/whl/cu124 'torch==2.5.1+cu124' 'torchaudio==2.5.1+cu124'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$quotedRoot = $dependencyRoot | ConvertTo-Json -Compress
$pathFile = Join-Path $PSScriptRoot '.lab-venv\Lib\site-packages\00_momo_separation.pth'
[System.IO.File]::WriteAllText($pathFile, "import sys; sys.path.insert(0, $quotedRoot)`n", [System.Text.UTF8Encoding]::new($false))
& $labPython -c 'import torch; print(torch.__version__, torch.cuda.is_available()); assert torch.cuda.is_available()'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host 'Video Lab separation CUDA runtime ready. App backend dependencies are unchanged.'
