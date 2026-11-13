[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputFile,
    [string]$Subtitles,
    [string]$Title,
    [string]$Device = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $InputFile).Path
if (-not $Title) { $Title = [System.IO.Path]::GetFileNameWithoutExtension($source) }
$adbExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbExe)) { $adbExe = (Get-Command adb -ErrorAction Stop).Source }

function Get-AudioInfo([string]$Path) {
    # Only ASCII codec/duration fields are needed. Windows PowerShell 5.1 can corrupt
    # UTF-8 filenames and tags when decoding full ffprobe JSON with its console code page.
    $probeLines = @(& ffprobe -v error -select_streams a:0 -show_entries 'stream=codec_name,duration:format=duration' -of json $Path)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the media file.' }
    $info = ConvertFrom-Json -InputObject ($probeLines -join "`n")
    if (-not $info.streams -or -not $info.streams[0].codec_name) { throw 'The selected file has no audio track.' }
    return $info
}

$info = Get-AudioInfo $source
$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant()
$package = 'com.example.hd_project'
$androidUser = (& $adbExe -s $Device shell am get-current-user).Trim()
if ($LASTEXITCODE -ne 0 -or $androidUser -notmatch '^\d+$') { throw 'Could not determine the active Android user.' }
& $adbExe -s $Device shell run-as $package --user $androidUser mkdir -p no_backup/recordings
if ($LASTEXITCODE -ne 0) { throw 'Install the debug APK before importing. Existing app data must be kept.' }
$projectDir = Split-Path -Parent $PSScriptRoot
$localDir = Join-Path $projectDir ('build\reports\app-import\source-' + $sourceHash)
New-Item -ItemType Directory -Path $localDir -Force | Out-Null
$audioPath = $source
if ($info.streams[0].codec_name -ne 'aac' -or [System.IO.Path]::GetExtension($source) -ne '.m4a') {
    # Keep the original untouched. AAC video soundtracks can be copied without re-encoding.
    $audioPath = Join-Path $localDir 'audio.m4a'
    $codecArguments = @('-c:a', 'aac', '-b:a', '128k')
    if ($info.streams[0].codec_name -eq 'aac') { $codecArguments = @('-c:a', 'copy') }
    & ffmpeg -hide_banner -loglevel error -nostdin -y -i $source -map 0:a:0 -vn -map_metadata -1 @codecArguments -movflags +faststart $audioPath
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract or convert the audio track.' }
    $info = Get-AudioInfo $audioPath
}
$duration = [long][Math]::Round([double]::Parse([string]$info.format.duration, [System.Globalization.CultureInfo]::InvariantCulture) * 1000)
if ($duration -le 0) { throw 'The audio file is empty.' }
$recordId = 'import_' + $sourceHash.Substring(0, 12) + '_' + $duration.ToString()
$metadata = @{ title = $Title; originalFileName = [System.IO.Path]::GetFileName($source); sourceSha256 = $sourceHash;
    audioSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $audioPath).Hash.ToLowerInvariant() }
$metadataPath = Join-Path $localDir ($recordId + '.json')
[System.IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
if (-not $Subtitles) {
    $sibling = [System.IO.Path]::ChangeExtension($source, '.srt')
    if (Test-Path -LiteralPath $sibling) { $Subtitles = $sibling }
}
$sources = @(@{ Path = $metadataPath; Extension = '.json' })
if ($Subtitles) { $sources += @{ Path = (Resolve-Path -LiteralPath $Subtitles).Path; Extension = '.srt' } }
$sources += @{ Path = $audioPath; Extension = '.m4a' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant() -ne $sourceHash) { throw 'The source changed during preparation.' }
foreach ($entry in $sources) {
    $remote = '/data/local/tmp/' + $recordId + $entry.Extension
    $destination = 'no_backup/recordings/' + $recordId + $entry.Extension
    $pending = $destination + '.importing'
    & $adbExe -s $Device push $entry.Path $remote
    if ($LASTEXITCODE -ne 0) { throw 'Could not transfer recording data.' }
    try {
        & $adbExe -s $Device shell run-as $package --user $androidUser cp $remote $pending
        if ($LASTEXITCODE -ne 0) { throw 'Could not save the recording inside the app.' }
        $copiedHash = (& $adbExe -s $Device shell run-as $package --user $androidUser sha256sum $pending)
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the imported file.' }
        $expectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $entry.Path).Hash.ToLowerInvariant()
        if (-not $copiedHash.StartsWith($expectedHash)) { throw 'The imported file does not match the local source.' }
        & $adbExe -s $Device shell run-as $package --user $androidUser mv $pending $destination
        if ($LASTEXITCODE -ne 0) { throw 'Could not finalize the imported file.' }
    } finally {
        & $adbExe -s $Device shell rm -f $remote
        & $adbExe -s $Device shell run-as $package --user $androidUser rm -f $pending
    }
}
& $adbExe -s $Device shell sync
if ($LASTEXITCODE -ne 0) { throw 'Could not flush imported recording data.' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant() -ne $sourceHash) { throw 'The source changed during import.' }
Write-Output ('Imported recording: ' + $recordId + '.m4a')
Write-Output 'Open the app recording library and choose the animation button on this recording.'
