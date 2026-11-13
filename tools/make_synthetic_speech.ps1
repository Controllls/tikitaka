# Synthetic input for local smoke tests; never a real conversation quality benchmark.
param([string]$OutputPath = (Join-Path $PSScriptRoot '..\server\data\synthetic-korean.wav'))
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputPath) { throw 'The input already exists. Choose another OutputPath to keep the recorded validation reproducible.' }
Add-Type -AssemblyName System.Speech
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $voice = $speaker.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'ko-KR' } | Select-Object -First 1
    if (-not $voice) { throw 'Install a Windows Korean speech voice before generating the test input.' }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
    $speaker.SelectVoice($voice.VoiceInfo.Name)
    $speaker.SetOutputToWaveFile([IO.Path]::GetFullPath($OutputPath))
    $speaker.Speak('안녕하세요. 오늘은 함께 차를 타고 바다로 갑니다. 창밖으로 나무와 푸른 하늘이 보여요. 잠시 쉬었다가 맛있는 점심을 먹으려고 합니다. 오늘 나눈 대화를 캐릭터와 함께 영상으로 남겨 볼게요. 이 음성은 실제 사람의 녹음이 아니라 프로그램으로 만든 테스트 음성입니다.')
} finally { $speaker.Dispose() }
Write-Output "Synthetic Korean speech saved to $OutputPath"
