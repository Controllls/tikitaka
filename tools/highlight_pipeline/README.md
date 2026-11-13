# 음성에서 주제별 하이라이트 MP4 만들기

MP3/M4A 등을 넣으면 **YAMNet 웃음 감지 → 한국어 STT → 로컬 언어 모델의 주제 경계 추정 → M4A 클립 → 자막 MP4**를 실행합니다. 입력 파일마다 모델이 다시 분석하며, 특정 녹음의 시간표를 코드에 넣지 않았습니다.

이 명령은 **Windows PC 단독 MP4 도구**로 유지합니다. 2026-11-06부터 앱이 사용하는 분석 구현은 `server/momo/highlights/`에 있습니다. 새 녹음 종료 시 자동 API 요청으로 같은 0.1 기준·STT·주제 구간 선택을 실행하고 서버와 앱에 M4A를 저장합니다. 앱의 기존 수동 찾기·앞 12초/뒤 6초 분석 경로는 제거했습니다. [자동 처리 안내](../../docs/GLEO_HIGHLIGHTS.md)

## 실행

저장소 루트의 PowerShell에서:

```powershell
.\run-highlights.ps1 -InputFile 'C:\Users\whdrm\Documents\현대_공모전\call.m4a'
```

기준과 출력 폴더를 지정할 수 있습니다.

```powershell
.\run-highlights.ps1 `
  -InputFile 'C:\Users\whdrm\Documents\현대_공모전\car_conversation_6min.mp3' `
  -Threshold 0.1 `
  -MaxClips 3 `
  -OutputDir '.\build\reports\highlight_pipeline\car_test'
```

기본값은 웃음 기준 **0.1**, 최대 **3개**, 한국어, 사용 가능한 GPU 자동 선택입니다. PowerShell 실행 정책으로 스크립트가 차단된 경우 아래처럼 해당 프로세스에서 실행할 수 있습니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-highlights.ps1 -InputFile 'C:\path\audio.m4a'
```

필수 환경:

- Windows x64, `uv` 또는 Python 3.13의 `py` 실행기. 스크립트가 전용 가상환경 `build/tools/highlight-venv`와 고정 버전 Python 패키지를 설치합니다.
- PATH에서 실행 가능한 `ffmpeg`, `ffprobe`. FFmpeg에 `ass`/`showwaves` 필터, `libx264`, AAC 인코더가 있어야 합니다.
- 한글 폰트. Windows에서는 맑은 고딕을 자동으로 찾습니다.
- 최초 설치 시 인터넷과 모델·런타임용 여유 저장 공간. 모델과 Python/GPU 런타임까지 여러 GB를 사용하며, 원본 길이에 비례한 분석 PCM·영상 저장 공간도 필요합니다.

RTX 3070 8 GB의 CUDA STT, Vulkan 주제 추론, NVENC 영상 생성으로 확인했습니다. GPU가 없으면 `-Device cpu`를 사용할 수 있으나 장시간 녹음은 오래 걸릴 수 있습니다. CPU 경로의 전체 처리 성능은 검증하지 않았습니다.

설치 후 Python을 직접 실행하면 추가 옵션을 사용할 수 있습니다.

```powershell
.\build\tools\highlight-venv\Scripts\python.exe -m tools.highlight_pipeline --help
.\build\tools\highlight-venv\Scripts\python.exe -m tools.highlight_pipeline `
  --input 'C:\path\audio.mp3' --threshold 0.1 --max-clips 3 `
  --stt-model large-v3-turbo --language ko --device auto --encoder auto
```

`--font`로 다른 한글 TTF/TTC, `--model-cache`로 모델 저장 위치를 지정할 수 있습니다. `--encoder libx264`는 CPU 영상 인코딩을 사용합니다. Linux/macOS용 Python 경로도 있지만 해당 OS에서는 `llama-server`와 폰트를 직접 설치해야 하며 실행 검증은 Windows에서 진행했습니다.

## 처리 기준

1. **YAMNet**: FFmpeg로 16 kHz 모노를 만들고, 앱의 `yamnet.tflite`에 15,600샘플(0.975초)을 7,800샘플 간격으로 입력합니다. 웃음 계열 6개 클래스 중 최댓값을 사용합니다.
2. **후보 묶기**: 웃음 점수가 기준 이상이고 `웃음 > 음악`, `음악 < 0.5`인 구간을 남깁니다. 간격이 2.5초 이하인 감지는 하나의 사건으로 묶습니다. 이 간격은 후보를 묶기 위한 값이며 클립의 앞뒤 여백이 아닙니다. 한 번의 감지도 분석합니다.
3. **STT**: `faster-whisper`의 `large-v3-turbo`로 전체 음성을 별도로 전사하고 단어별 원본 시간을 보존합니다. 짧은 발화 단위와 ID를 만들어 언어 모델의 입력으로 사용합니다.
4. **주제 경계**: Qwen3.5-4B가 전사 내용을 읽고 이야기를 도입·설명·질문·답변·마무리 단위로 나눕니다. 입력이 15,000토큰 이내이면 전체 대화를 한 번에 분할해 후보들이 같은 경계를 사용합니다. 더 길면 웃음 후보 주변 발화부터 읽고 주제가 문맥 끝에 걸릴 때 읽는 범위를 확장합니다. 모델은 시간을 생성하지 않고 **주제가 시작되는 발화 ID**를 반환합니다. 코드가 다음 주제 직전의 발화까지 포함합니다. 모델 문맥은 24,576토큰, 추론 예산은 4,096토큰으로 제한합니다.
5. **검증·선정**: 발화 ID의 순서·범위, 웃음과 연결된 발화의 포함 여부를 검증합니다. 선택한 주제의 STT 시작/종료 시간을 사용하고 웃음 자체도 빠지지 않게 포함합니다. 같은 경계의 후보는 합치고, 최고 웃음 점수 → 웃음 사건 수 → 평균 점수 순으로 겹치지 않는 최대 3개를 선택합니다. 저장 순서는 원본 시간순입니다.
6. **저장·영상**: 각 주제를 M4A, TXT, SRT로 저장하고 파형·한글 자막·주제 제목·웃음 위치가 있는 MP4로 렌더링합니다. 개별 영상과 챕터가 있는 합본 `videos/highlights.mp4`를 만듭니다. 출력은 H.264/AAC, 1280×720, 30 fps입니다.
7. **완료 확인**: 오디오 길이, 최종 코덱, 챕터 수, 음성/영상 길이 차이를 검사하고 합본 전체를 디코딩합니다. 원본 SHA-256이 처리 전후 같은지 확인합니다.

고정된 `앞 12초·뒤 6초`는 사용하지 않습니다. 자막 가독성을 위한 표시 시간과 줄바꿈 규칙은 음성 클립의 경계를 변경하지 않습니다.

## 결과 파일

기본 출력 위치는 `build/reports/highlight_pipeline/<입력명>_<실행시각>/`입니다.

| 파일 | 내용 |
|---|---|
| `result.json` | 원본 해시, 선택한 시작/끝 시간, 제목·이유·점수, 결과 파일 위치 |
| `transcript.txt`, `transcript.srt` | 전체 전사와 원본 시간 |
| `transcription.json` | 단어 시간, 발화 ID, STT 캐시 |
| `laughter_windows.json`, `laughter_events.json` | 전체 YAMNet 점수와 기준을 통과한 후보 |
| `topic_decisions.json`, `topic_request_*.json` | 주제 모델의 선택과 중간 분할 결과 |
| `clips/clip_01.m4a`, `.txt`, `.srt` | 주제별 음성과 자막. 번호는 원본 시간순 |
| `videos/clip_01.mp4` | 개별 하이라이트 영상 |
| `videos/highlights.mp4` | 합본 영상 |
| `videos/decode-check.log` | 합본 디코딩 검사. 정상일 때 빈 파일 |
| `run.json`, `topic-server.log` | 실행 설정·상태와 로컬 모델 로그 |

같은 입력·설정으로 같은 출력 폴더를 지정하면 완료된 웃음 분석·전사·주제 분석 캐시를 재사용합니다. 파일 해시·모델·프롬프트 등의 키가 맞지 않으면 해당 분석을 다시 수행합니다. 다른 입력이나 실행 옵션을 쓰려면 새 출력 폴더를 지정하세요. 녹음·전사·모델·영상은 기본적으로 Git에서 제외되는 `build/` 아래에 저장됩니다.

웃음 후보나 인식된 대화가 없으면 MP4 없이 `result.json`에 사유를 남깁니다. 주제 경계 추론에 실패하거나 문맥이 모델 한도를 넘으면 오류와 중간 결과를 남깁니다. 임의의 앞뒤 초로 대신 잘라 성공으로 처리하지 않습니다.

## 모델과 코드

| 구성 | 위치 / 출처 |
|---|---|
| YAMNet | `app/src/main/assets/yamnet.tflite`, [공식 모델 설명](https://www.tensorflow.org/hub/tutorials/yamnet), 프로젝트 `third_party/NOTICE.txt` |
| Whisper large-v3-turbo | [변환 모델](https://huggingface.co/mobiuslabsgmbh/faster-whisper-large-v3-turbo), [faster-whisper](https://github.com/SYSTRAN/faster-whisper). 기본 alias `large-v3-turbo`의 변환 가중치를 런타임이 다운로드 |
| Qwen3.5-4B Q4_K_M | [원본 모델](https://huggingface.co/Qwen/Qwen3.5-4B), [GGUF 변환 모델](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF). revision은 `models.py`에 고정 |
| llama.cpp | [공식 프로젝트](https://github.com/ggml-org/llama.cpp), Windows Vulkan 릴리스 `b12110`. 압축 파일의 SHA-256을 검사한 뒤 설치 |
| FFmpeg | [공식 프로젝트](https://ffmpeg.org/). 설치한 배포본의 빌드 옵션·라이선스에 따름 |

YAMNet과 Qwen 모델은 Apache-2.0, Whisper·faster-whisper·llama.cpp는 MIT 계열 라이선스입니다. 재배포 시 각 저장소의 원문 라이선스와 변환 모델 조건도 확인하세요. Windows 한글 폰트는 영상 생성용으로 로컬 출력 폴더에 복사하며 코드 저장소에 포함하지 않습니다.

주요 파일은 `audio.py`(웃음), `speech.py`(STT), `topics.py`(경계·선정), `video.py`(클립·MP4), `pipeline.py`(전체 실행), `models.py`(모델 다운로드)입니다. HTTP 요청은 로컬 `127.0.0.1`의 llama.cpp에만 대화 데이터를 보내며, 외부 연결은 모델·패키지 다운로드에 사용합니다.

## 정확도와 테스트

웃음 점수 0.1은 10% 확률이나 재미 점수가 아닙니다. 작은 소리·겹치는 말·전화 음질에서 STT 오인식이 있고, 언어 모델의 주제 경계도 추정입니다. 결과 제목과 자막은 자동 생성됩니다. PC의 FFmpeg 리샘플러가 Android 앱의 리샘플러와 달라 같은 파일의 일부 점수가 달라질 수 있습니다.

```powershell
.\build\tools\highlight-venv\Scripts\python.exe -m unittest discover -s tools/highlight_pipeline/tests -v
```

단위 테스트는 점수·음악 제외, 후보 묶기, 발화 경계 검증, 중복·겹침 선택, 실제 STT 시간 사용, 캐시 분리를 검사합니다. 실제 파일의 전체 처리와 영상 검증은 실행 시 수행됩니다.

2026-11-05 검증 결과:

- 단위 테스트 14개 통과.
- `call.m4a` 약 43분 32초를 새 가상환경의 실행 스크립트로 처음부터 처리: 웃음 후보 6곳 → 주제 3개 → 개별 MP4와 합본 523.521초 생성. 합본 전체 디코딩, 챕터 3개, 원본 해시 유지 확인.
- CPU 영상 인코딩과 NVENC 영상 인코딩을 모두 실행했고, 합본의 디코딩 검사와 한글 자막·파형 화면을 확인했습니다.
- 같은 출력 폴더로 재실행 시 YAMNet·STT·주제 모델 캐시 재사용 확인.
- 8초 무음 파일은 CPU STT까지 실행한 뒤 `no_laughter_candidates`, 영상 없음으로 정상 종료.
