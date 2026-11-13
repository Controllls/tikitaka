# HD_project · MOMO 영상 만들기

현재 이동 기록 앱(`com.example.hd_project`)에 MOMO 영상 제작을 통합했습니다. 기존 기본 도형 캐릭터·식탁 장면·키워드 기반 장면 선택·SRT 편집 UI는 제거했습니다. 기존 녹음과 탑승 안내·Gleo 기능은 유지합니다.

새 녹음 종료 후의 **자동 하이라이트 M4A 추출**은 [별도 서버 작업](GLEO_HIGHLIGHTS.md)입니다. 아래 MOMO 영상 만들기는 사용자가 선택한 녹음 전체의 MP4를 만드는 기능입니다. 하이라이트 추출만으로 MP4가 자동 생성되지는 않습니다.

## 앱에서 사용

1. **저장된 기록 → 해당 기록의 영상 만들기**를 누릅니다.
2. **영상 만들기**를 누르고 최초 서버 전송 안내에 동의합니다.
3. 서버가 목소리·웃음·한국어 자막을 분석하면 앱이 제공된 리소스로 MP4를 만듭니다.
4. **영상 재생**, **MP4 저장**으로 결과를 확인합니다. 저장 위치는 Android `Movies/이동기록`이며 Android 9에서는 파일 선택기를 사용합니다.

홈의 **사용자 관리**에서 이름·제공된 캐릭터·등록 목소리를 연결할 수 있습니다. 영상 제작 시 목소리로 인물을 찾아 캐릭터를 적용합니다. [사용자 관리 안내](people-voice-matching.md)를 참고하세요. 배경·좌석·자막 편집 메뉴는 없습니다. 회원가입·로그인 화면 없이 앱 설치별로 자동 인증하며, 토큰 만료도 앱이 처리합니다. 서버 주소는 빌드 설정입니다.

입력은 0.1초~10분, 최대 80,000,000바이트입니다. 현재 선택한 녹음 전체를 영상으로 만듭니다. 기존 PC 하이라이트 도구로 잘라 앱에 넣은 클립도 사용할 수 있습니다. 녹음 중에는 제작할 수 없습니다.

분석은 WorkManager가 이어서 처리합니다. 영상 합성 중 화면을 벗어나면 합성은 취소되며, 다시 열어 제작하면 기기에 저장된 분석 결과를 재사용합니다. 취소 시 이번 미완성 MP4를 제거하고 이전 완성본은 유지합니다.

## 현재 PC에서 서버 실행

PowerShell에서 프로젝트 루트를 기준으로 실행합니다.

```powershell
cd C:\Users\whdrm\AndroidStudioProjects\HD_project
.\server\backend.ps1 start -CudaRoot D:\MOMO_Development\cuda-runtime
.\server\backend.ps1 status
```

서버는 Android 앱과 독립적으로 백그라운드에서 실행됩니다. 이미 실행 중이면 중복 실행하지 않습니다. `.\server\backend.ps1 stop`으로 이 실행기가 띄운 서버만 종료할 수 있습니다. 상태는 `http://127.0.0.1:8765/health/ready`, API 문서는 `http://127.0.0.1:8765/docs`입니다. 최초 모델 로딩 중에는 503이 반환됩니다. 앱은 새 분석 전 상태를 확인하고, 준비 중이거나 연결되지 않으면 안내합니다. 준비 완료 후 다시 영상 만들기를 누릅니다. 이미 내려받은 유효한 분석은 서버 없이 재사용합니다. [독립 실행과 구성](../server/README.md)

Debug 앱의 주소는 `http://10.0.2.2:8765/`입니다. 서버와 앱을 실행한 뒤 바로 영상 만들기나 목소리 등록을 사용합니다. 개발 계정과 토큰 주입은 필요하지 않습니다. 설치 UUID·무작위 인증키·토큰은 앱이 Android Keystore로 암호화해 백업 제외 영역에 저장합니다. 재설치나 앱 데이터 삭제 시 새 설치로 취급합니다. [인증과 기존 데이터 처리](../server/README.md#설치별-인증)

서버 JWT 서명키는 `server/data/local-development.json`에 보관하며 Git과 APK에서 제외합니다. Release의 서버 주소 기본값은 비어 있으며 배포 시 `-PmomoApiUrl=https://your-server/`로 지정하고 서버·서명을 준비해야 합니다. 현재 localhost 서버는 심사 기기에서 접근할 수 있는 공개 배포 서버가 아닙니다.

## 미리 제공할 리소스와 고정 설정

Windows 입력 폴더: **`D:\MOMO_Development\video-input`**.

| 경로 | 내용 |
|---|---|
| `recordings/` | 기존 앱에서 Windows로 꺼낸 완료 녹음 31개 사본. 리소스 동기화와 APK에 포함하지 않음 |
| `resources/characters/character_01`, `character_02` | 제공받은 manifest와 PNG 파츠 |
| `resources/vehicles/` | 차량·안전벨트·선택적 핸들 PNG |
| `resources/backgrounds/` | 도심·고속도로 10초 루프 MP4 |
| `settings/fixed-scene.example.json` | 현재 적용 중인 고정 구성 예제 |
| `settings/fixed-scene.json` | 사용자가 확정한 고정 구성. 이 이름으로 제공하면 다음 리소스 동기화 때 적용 |

현재 배경은 도심이며 캐릭터는 등록 인물의 목소리 매칭 결과에 따릅니다. 처음 듣거나 불확실한 목소리는 캐릭터 1을 사용합니다. 처음 감지된 최대 4명을 운전석·조수석·뒷좌석에 배치하며 실제 좌석을 추정하지 않습니다. 그 밖의 화자도 원본 음성·자막은 유지합니다. 고정 preset의 인물·화자 연결은 이 자동 매칭으로 교체됩니다.

`fixed-scene.json`은 예제의 `people`, `speaker_mapping`, `background`, `vehicle`, `seatbelts`, `steering_wheel`, `layout`을 사용합니다. 경로는 입력 폴더에 상대적인 `resources/...`이고 캐릭터는 같은 캔버스에 정렬된 PNG와 manifest가 필요합니다. 단일 인물 이미지에서 파츠를 자동 추출하지 않습니다.

리소스·설정 파일을 바꾼 뒤 다음 명령으로 APK에 반영합니다. 실행 중인 앱이 Windows 폴더를 직접 감시하지 않습니다.

```powershell
server\.venv\Scripts\python.exe tools\sync-momo-resources.py
.\gradlew.bat :app:assembleDebug
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
```

APK의 `assets/momo/index.json`에 리소스 해시·크기를 기록합니다. 최초 영상 화면에서 무결성을 검사해 앱 내부에 설치합니다. 설정·리소스가 바뀌면 기존 영상의 구성 해시와 비교해 새 제작을 안내합니다. 기존 기본 도형 렌더러로 대체하는 경로는 없습니다.

## 제작 방식과 저장 위치

- 서버: Nemotron 화자 분리, YAMNet 소리 분류, Silero VAD, Whisper small 한국어 전사. 원본 전체의 시간축을 유지하며 20ms 움직임 트랙을 만듭니다.
- Android: 제공 PNG의 몸·머리·눈·입을 합성합니다. 음성 에너지에 따라 입을 움직이고 확인된 화자의 웃음을 반영합니다. 음소 단위 립싱크는 아닙니다.
- 출력: **540×960, 30fps, H.264/AAC MP4**. 원본 음성을 사용하고 배경 영상의 소리는 제거합니다. 배경은 길이에 맞춰 반복합니다.
- 최초 서버 분석이 필요하며, 내려받은 분석 캐시로 다시 제작할 때는 서버가 필요하지 않습니다. 생성형 영상 API나 OpenAI API를 호출하지 않습니다.

| 자료 | 현재 에뮬레이터(user 10) 경로 |
|---|---|
| 녹음 원본 | `/data/user/10/com.example.hd_project/no_backup/recordings/` |
| 새 완성 영상 | `/data/user/10/com.example.hd_project/no_backup/momo-videos/` |
| 분석 캐시 | `/data/user/10/com.example.hd_project/no_backup/momo-projects/` |
| 설치된 리소스 | `/data/user/10/com.example.hd_project/no_backup/momo-assets/` |
| 내보낸 영상 | Android 공유 저장소 `Movies/이동기록/` |

Android 내부 경로는 Windows 탐색기 경로가 아닙니다. Android Studio Device Explorer에서 확인합니다. 기존 `no_backup/animations/`의 완성본은 자동 삭제하지 않으며 새 MOMO 영상으로 표시하지 않습니다. 제거한 기본 렌더러 소스의 사본은 `D:\MOMO_Development\migration-backup\hd-basic-animation-20261105`에 보관했습니다.

Windows 검증 영상은 프로젝트의 `captures/momo/`에 있습니다. `momo-validation-server.mp4`는 합성 한국어 음성을 실제 서버로 분석한 결과입니다. 사용자 녹음을 테스트용으로 업로드하지 않았습니다.

## 다른 PC의 서버 준비

Python 3.11, uv, FFmpeg/ffprobe가 필요합니다. 모델은 APK에 넣지 않습니다.

```powershell
cd server
uv sync --frozen --extra inference
# CUDA wheel을 이 가상환경에 설치할 때는 --extra cuda도 추가합니다.
.venv\Scripts\python.exe -m momo.prepare_models --silero-source '원본패키지\app\dist\vendor\vad\silero-v5.1.2.onnx'
.venv\Scripts\python.exe ..\tools\start_local.py --device cuda
```

CPU 실행은 `--device cpu`를 명시합니다. 현재 PC의 `server/data/models`는 `D:\MOMO_Development\models`로 연결된 junction입니다. 다른 PC는 새로 모델을 준비해야 합니다. 모델 revision/해시는 `contracts/model-lock.json`, Python 의존성은 `server/uv.lock`으로 고정합니다. `--fixture`는 계약 테스트 전용이며 실제 분석이 아닙니다.

## 검증

2026-11-06 앱·백엔드 독립 실행 변경: Debug 앱과 기기 테스트 APK 빌드, Android 단위 테스트 40개, 서버 테스트 47개 통과. Lint 오류 0개·경고 59개. 서버 연결 실패·준비 중·준비 완료·캐시 재사용 기기 테스트 4개와 합성 한국어 음성의 실제 서버 분석부터 MP4 제작·Movies 저장까지의 테스트 1개가 통과했습니다. 아래 2026-11-05 기록은 이전 검증 이력입니다.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
cd server
.venv\Scripts\python.exe -m pytest -q
.venv\Scripts\python.exe -m ruff check momo tests
cd ..
```

기기 테스트 APK를 설치한 뒤 현재 Android user에 맞춰 실행합니다.

```powershell
& $adb -s emulator-5554 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb -s emulator-5554 shell am instrument --user 10 -w -r -e class com.example.hd_project.MomoVideoTest com.example.hd_project.test/androidx.test.runner.AndroidJUnitRunner
server\.venv\Scripts\python.exe tools\verify-momo-videos.py
```

2026-11-05 최종 검증: Debug/Release(미서명) 빌드 통과, Android 단위 테스트 29개, 서버 테스트 33개와 Ruff 통과. Android Lint는 오류 0개·경고 55개입니다. 기기 테스트 7개(MOMO 3개, 기존 오디오 파이프라인 2개, 기존 좁은/넓은 화면 2개)가 통과했습니다.

MOMO 테스트는 제공 리소스로 3초/30초 MP4 생성, 취소 시 미완성 파일 제거, 실제 영상 만들기 버튼을 통한 신규 업로드·서버 추론·MP4 제작을 검사합니다. MP4 저장 버튼으로 MediaStore에 내보낸 파일을 다시 읽어 완성본과 SHA-256이 같은지 확인합니다. 별도로 기존 녹음 31개의 SHA-256이 Windows 사본을 만들 때와 모두 같은 것을 확인했습니다.

출력은 별도의 ffprobe 검사로 H.264/AAC·540×960·30fps와 입력 대비 0.1초 이내 길이 차이를 확인합니다. FFmpeg로 원본과 출력 음성을 PCM으로 디코딩해 상관도와 시간 오프셋도 검사합니다. 세부 값은 `momo-video-validation.json`에 기록합니다. 에뮬레이터에서 영상 화면을 열어 완성 영상과 제작·재생·저장 버튼의 배치를 확인했습니다.

실제 다인승 차량 대화의 화자 분리·웃음·전사 정확도, 장시간 제작 성능, 실차 화면·마이크 환경은 별도 검증이 필요합니다. Docker/PostgreSQL/Redis/Celery/S3 구성은 소스를 포함했으나 이 PC에서 통합 실행하지 않았습니다. [서버 배포 구성](MOMO_DEPLOYMENT.md), [원본 이식 대응](MOMO_SOURCE_PORTS.md), [출처 고지](MOMO_THIRD_PARTY_NOTICES.md)를 참고하세요.
