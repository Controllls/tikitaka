# 이동 기록 · Drive Journal

**차량 탑승을 계기로 녹음 여부를 묻고, 함께 나눈 대화를 기기에 저장해 다시 듣는 Pleos Connect 앱입니다.**

현대자동차그룹 차량용 앱 개발 공모전을 준비하는 Android 프로토타입입니다. 사용자 동의 후 녹음·저장·재생하며, 이 브랜치에는 **웃음 기반 하이라이트 추출과 Gleo 앱 기능 호출 인터페이스**를 추가했습니다. 대화 전사·의미 기반 재미 평가와 실제 Gleo 음성 호출 승인은 아직 포함하지 않습니다.

`feature/gleo-highlights`는 커밋 `240df7b`에서 분리했습니다. 기존 작업 폴더의 커밋되지 않은 탑승 알림 수정은 포함하지 않습니다. Debug 앱은 `이동 기록 · Gleo` / `com.example.hd_project.gleo`로 별도 설치되어 기존 앱의 녹음을 보존합니다. [Gleo 구현·시연 방법](docs/GLEO_HIGHLIGHTS.md)을 먼저 확인하세요.

![웃음 테스트 음원에서 실제로 추출한 하이라이트](docs/images/gleo-highlights.png)

위 화면은 공개 웃음 테스트 음원의 앞뒤에 무음을 붙인 35초 파일에서 약 20초의 클립을 실제 저장한 결과입니다. 사용자 녹음이나 실제 Gleo 음성 호출 시연 화면은 아닙니다. 이 브랜치 검증 결과는 **단위 테스트 14개·기기 테스트 8개 통과, Lint 오류 0개·경고 15개**입니다.

동작 설명·화면·검증 결과는 **2026-10-31의 앱 코드 `1c37144`**를 기준으로 합니다.

[실행 방법](#실행-방법) · [APK 안내](#apk-안내) · [검증 결과](#검증-결과) · [현재 한계와 개발 계획](#현재-한계와-개발-계획)

![Pleos Connect 에뮬레이터에서 실행한 이동 기록 홈 화면](docs/images/home.png)

2026-10-31에 촬영한 실제 에뮬레이터 실행 화면입니다. 오른쪽의 이동 기록 화면이 이 프로젝트의 앱 영역이며, 왼쪽 차량 화면과 상·하단 시스템 영역은 Pleos Connect가 제공합니다.

## 기획 배경과 차량 특화

가족이나 친구와 함께 이동하다 나눈 이야기를 나중에 다시 듣고 싶은 상황에 주목했습니다. 차에 탈 때 기록 여부를 먼저 확인하고, 사용자가 선택한 대화를 하나의 녹음으로 남기는 것이 현재 구현의 중심입니다.

- **차량 신호와 연결:** Pleos Vehicle SDK의 앞좌석 왼쪽 점유 상태를 읽어 녹음 시작 여부를 안내합니다. 점유 상태는 안내의 단서로만 사용합니다.
- **차량 화면에 맞춘 조작:** 큰 시작·종료 버튼, 경과 시간, 탑승 상태, 최근 기록을 표시합니다. 앱 창 너비가 900dp 이상이면 두 열로, 그보다 좁으면 세로로 배치합니다.
- **함께 이동한 경험의 보관:** 저장된 대화를 날짜·시간과 함께 다시 듣습니다. 향후 재미있었던 대화 구간을 찾는 리플레이 경험으로 확장하려 합니다.

주요 사용 대상은 가족·친구와 차량을 함께 이용하는 사람입니다. 사용자 수와 재이용률은 아직 측정하지 않았습니다.

## 구현된 기능

| 기능 | 현재 동작 |
|---|---|
| 탑승 안내 | 앱 화면이 열려 있을 때 좌석 점유 상태가 1.2초 유지되면 녹음 여부를 질문합니다. 같은 탑승 상태에서는 질문을 반복하지 않습니다. |
| 동의·권한 확인 | 시작 선택과 마이크 권한 허용 후 녹음합니다. 건너뛰기·취소·권한 거절 시 녹음하지 않습니다. |
| 녹음 유지·종료 | 마이크 포그라운드 서비스로 시작한 녹음을 앱 밖에서도 유지하며, 앱 버튼이나 시스템 알림에서 종료합니다. |
| 녹음 상태 표시 | 경과 시간과 시작·녹음·저장·오류 상태를 표시합니다. 마이크 버튼의 파형은 실제 입력 크기에 반응합니다. |
| 기록 보관·재생 | 앱 전용 공간에 M4A 파일을 저장하고 목록에서 재생·일시정지·삭제합니다. |
| 기록 대시보드 | 실제 저장 파일을 바탕으로 기록 수, 총 시간, 가장 최근 기록을 표시합니다. |
| 웃음 기반 하이라이트 | YAMNet을 기기에서 실행해 웃음 주변 구간을 최대 3개 추출합니다. AAC 패킷을 새 M4A로 저장하며 원본은 유지합니다. |
| Gleo 인터페이스 | 분석 요청·목록 표시·번호로 재생하는 3개 action과 callback을 구현했습니다. 실제 음성 대화 연동은 스키마 승인 후 검증해야 합니다. |
| 예외 처리 | 저장 공간 부족, 짧은 녹음, 저장 실패를 처리합니다. Android API 29 이상에서는 입력 무음 처리 통지를 확인해 녹음을 종료하는 경로가 있습니다. |

<details>
<summary>녹음 중 화면 보기</summary>

![녹음 경과 시간과 종료 버튼을 표시하는 화면](docs/images/recording.png)

주변 대화 수집을 피하기 위해 PC 마이크 입력을 끈 상태에서 촬영한 시험 화면입니다. 실제 차량의 음질이나 음성 입력 성능을 증명하는 자료는 아닙니다.

</details>

## 실행 방법

### 개발·테스트 환경

| 항목 | 프로젝트 설정·확인 환경 |
|---|---|
| 개발 언어·UI | Kotlin, Android XML Views, Material Components |
| Gradle / Android Gradle Plugin | 9.6.0 / 9.4.1 |
| 빌드 JVM | JDK 25 — `gradle/gradle-daemon-jvm.properties`에 지정 |
| Java 소스·타깃 호환성 | Java 11 |
| Android SDK | `compileSdk 37`, `targetSdk 37`, `minSdk 28` |
| Pleos Vehicle SDK | `ai.pleos.playground:Vehicle:2.3.2` |
| 확인한 에뮬레이터 | `Pleos_Connect_v2.1.0`, Android 14 / API 34, x86_64 |
| 앱 패키지 | release `com.example.hd_project`, debug `com.example.hd_project.gleo` |
| 오디오 분류 | YAMNet + LiteRT Interpreter 1.4.2, 기기 내 처리 |

`minSdk`는 설치 하한 설정입니다. 모든 Android 기기나 차량에서 검증했다는 뜻은 아닙니다. 차량 기능 시험에는 Pleos Connect 환경이 필요합니다.

### 1. 프로젝트 열기와 빌드

```powershell
git clone https://github.com/Controllls/HD_project.git
cd HD_project
.\gradlew.bat assembleDebug
```

빌드 전에 다음 환경을 준비합니다.

1. 프로젝트의 Android Gradle Plugin 버전을 지원하는 Android Studio와 Android SDK Platform 37을 설치합니다.
2. Android Studio에서 저장소 루트를 열고 Gradle Sync를 실행합니다. 로컬 SDK 경로는 각 PC의 `local.properties`로 설정하며 Git에는 포함하지 않습니다.
3. JDK 25를 사용할 수 있게 설정합니다. Gradle·SDK·의존성을 처음 내려받을 때는 네트워크 연결이 필요합니다.
4. Pleos 개발 환경 안내에 따라 에뮬레이터를 설치합니다. Vehicle SDK 저장소는 `settings.gradle.kts`에 선언되어 있습니다.

macOS·Linux의 빌드 명령은 `./gradlew assembleDebug`입니다. 실제 실행 검증은 위 표의 Windows/Pleos 에뮬레이터 환경에서 진행했습니다.

### 2. 설치와 실행

Android Studio에서 실행 구성 `app`, 실행 대상 `Pleos_Connect_v2.1.0`을 선택하고 Run을 실행합니다. 또는 Windows의 기본 Android SDK 설치 경로에서 다음 명령을 사용할 수 있습니다.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb devices
& $adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5554 shell am start -n com.example.hd_project.gleo/com.example.hd_project.MainActivity
```

SDK 설치 위치와 기기 ID는 본인 환경에 맞게 바꿉니다.

### 3. 마이크와 좌석 설정

- **음성 입력:** 에뮬레이터 확장 설정의 Microphone에서 `Virtual microphone uses host audio input`을 켭니다. Windows에서도 Android Emulator의 마이크 접근을 허용해야 합니다. 앱에서는 마이크 권한을 허용합니다.
- **알림:** 알림 권한을 허용하면 녹음 상태와 종료 버튼을 알림에서 확인할 수 있습니다.
- **탑승 안내:** 앱을 연 상태에서 Virtual Console의 Seat / Occupancy → Driver를 OFF로 바꾸고 2초 기다린 뒤 ON으로 바꿉니다. 실제 차량 센서가 아닌 에뮬레이터의 시험 입력입니다.

Virtual Console 연결이 필요한 경우:

```powershell
& $adb -s emulator-5554 forward tcp:18088 tcp:8088
```

브라우저에서 `http://127.0.0.1:18088/`을 열고 콘솔 페이지를 유지한 채 조작합니다. 센서 연결이 실패해도 앱의 마이크 버튼으로 녹음을 시작할 수 있습니다.

### 4. 기본 시연 흐름

1. 앱을 열고 좌석 입력을 변경하거나 가운데 마이크 버튼을 누릅니다.
2. 녹음 안내를 읽고 함께 탄 사람에게 녹음 사실과 동의를 확인한 뒤 **녹음 시작**을 선택합니다.
3. 마이크 권한을 허용하고 녹음 상태·경과 시간을 확인합니다.
4. 홈 화면으로 이동했다가 앱으로 돌아와 녹음이 유지되는지 확인합니다.
5. 앱의 종료 버튼 또는 알림의 **녹음 종료**를 누릅니다.
6. **저장된 기록**에서 파일을 재생·일시정지·삭제합니다.

## APK 안내

**제출용 release APK는 아직 등록하지 않았습니다. 현재는 소스를 빌드해 debug APK로 실행할 수 있습니다.**

| 구분 | 위치·상태 |
|---|---|
| 개발용 debug APK | `assembleDebug` 실행 후 로컬 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. Git에는 포함하지 않습니다. |
| 공모전 제출용 release APK | 준비 중입니다. 현재 프로젝트에는 release 서명 설정이 없습니다. |
| 제출 시 파일 경로 | 공식 파일명 형식에 맞춰 `apk/팀명_이동기록_release.apk`로 제공할 예정이며, 현재 존재하는 다운로드 경로는 아닙니다. |

공식 안내는 소스와 실행 가능한 release APK를 함께 제공하도록 요구합니다. 저장소의 `apk/` 폴더에 넣고, 업로드 용량을 초과하면 GitHub Releases를 이용합니다. 등록 후 이 절에 실제 다운로드 링크를 추가해야 합니다. [공식 지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)

현재 `.gitignore`는 APK를 제외하므로 제출 파일을 등록할 때 해당 파일의 추적 여부도 확인해야 합니다. 개발용 debug APK를 제출용 release APK로 표시하지 않습니다.

## 녹음 기능의 기존 검증 결과

최근 코드 검증 기준: **2026-10-31**, 앱 코드 커밋 `1c37144`.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

| 검사 | 결과 |
|---|---|
| Debug APK 빌드 | 통과 |
| 단위 테스트 | 7개 통과 — 탑승 질문 상태 5개, 좌석 값 호환 처리 1개, 기본 샘플 1개 |
| Android Lint | 오류 0개, 경고 13개 |
| 기존 에뮬레이터 시연 기록 | 동의·건너뛰기·권한 거절, 다음 탑승 안내, 홈 이동 후 녹음 유지, 버튼·알림 종료, 저장·재생·삭제 확인 |

마지막 행은 [실행·검증 기록](예제_실행방법.txt)에 남긴 에뮬레이터 시험 결과입니다. 호스트 마이크를 끈 시험이므로 실제 음성 품질·장시간 무중단 녹음·실차의 통화/Gleo 충돌 검증을 포함하지 않습니다. `androidTest`의 기본 샘플은 위 단위 테스트 실행 결과에 포함하지 않았습니다.

### 좌석 점유 값 호환 처리

현재 조합인 **Vehicle SDK 2.3.2 + Pleos Connect v2.1.0**에서는 Virtual Console의 Driver OFF가 SDK Boolean `true`, ON이 `false`로 관찰되어 `PleosSeatOccupancyCompat`에서 반전합니다. SDK나 차량 펌웨어가 달라지면 반드시 다시 확인해야 합니다.

SDK Boolean만으로 복구할 수 없는 미확인 원시 값도 있습니다. 이 값은 질문을 표시하는 단서로만 사용하며 녹음 동의를 대신하지 않습니다.

## 데이터와 권한

- 저장 형식: **AAC / M4A, 모노, 44.1 kHz, 96 kbps**.
- 저장 위치: 앱 전용 `noBackupFilesDir/recordings`. 녹음 파일은 Android 백업·기기 이전 대상에서 제외되며 앱을 삭제하면 함께 삭제됩니다.
- 현재 앱에는 녹음 업로드, 대화 전사, 외부 공유 기능이 없습니다. 파일 내보내기도 아직 제공하지 않습니다.

| 권한 | 사용 목적 |
|---|---|
| `pleos.car.permission.CAR_SEATS` | 지원 좌석 확인 및 점유 상태 조회·구독 |
| `RECORD_AUDIO` | 사용자 동의 후 마이크 입력 녹음 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | 시작한 녹음을 앱 밖에서도 유지 |
| `POST_NOTIFICATIONS` | 녹음 알림과 종료 동작 표시 |

## 현재 한계와 개발 계획

### 현재 한계

- 앱이 닫혀 있을 때의 탑승 감지, 시동 연동 실행, 강제 종료 후 자동 실행은 구현하지 않았습니다.
- 좌석에서 내리는 것만으로 녹음을 종료하지 않습니다. 사용자 버튼·알림 동작 또는 오류 처리로 종료합니다.
- 통화·Gleo 사용으로 입력이 제한된 후 자동으로 녹음을 재개하는 기능은 없습니다. 실제 Pleos 차량의 마이크 접근·입력 경로·충돌 동작은 검증이 필요합니다.
- 전원 차단·강제 종료로 남은 `.pending` 녹음 파일의 복구는 구현하지 않았습니다.
- 주행 상태에 따른 화면·조작 제한은 구현하지 않았습니다. 현재 시연 절차는 정차 상황을 기준으로 합니다.

### 후속 개발 방향 — 미구현

| 방향 | 확인·개발할 내용 |
|---|---|
| AI 대화 리플레이 | 현재 웃음 감지 후보 추출을 구현했습니다. 다음 단계는 시간 정보가 있는 전사와 문맥을 이용한 후보 평가입니다. 웃음 없는 농담·흥미로운 주제는 아직 찾지 못합니다. |
| Gleo 실환경 검증 | 앱 인터페이스는 구현했습니다. 스키마 등록·승인과 실제 자연어 음성 호출 시험이 필요합니다. |
| 실차 검증 | 차량 마이크, 내비 화면 전환, 통화·Gleo 전후 입력 상태, 목표 녹음 시간 동안의 안정성을 확인합니다. |
| 제출·배포 준비 | release APK 서명·실행 확인, 시연 영상, 라이브러리·리소스 사용 조건 점검을 진행합니다. |

Gleo가 들은 전체 대화나 음성 원본을 앱이 전달받는 기능을 전제로 하지 않습니다. 현재 녹음은 Android `MediaRecorder`로 구현되어 있으며 Pleos STT·TTS·LLM SDK는 사용하지 않습니다.

## 코드 구조

소스 디렉터리: [`app/src/main/java/com/example/hd_project`](app/src/main/java/com/example/hd_project)

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 녹음 동의·권한, 홈 화면 상태와 기록 통계 |
| `VehicleSeatMonitor.kt` | 좌석 지원 확인, 현재 값·이벤트 구독, 상태 안정화 |
| `PleosSeatOccupancyCompat.kt` / `BoardingPromptGate.kt` | 현재 SDK 값 보정, 탑승 질문 중복 방지 |
| `RecordingService.kt` / `RecordingState.kt` | 마이크 서비스·알림·종료 처리, 녹음 상태 공유 |
| `RecordingFiles.kt` / `RecordingsActivity.kt` | 파일 저장·목록·재생·삭제 |
| `RecordingButtonView.kt` | 마이크 버튼과 입력 크기 시각화 |

화면 XML은 [`res/layout`](app/src/main/res/layout), 넓은 화면 구성은 [`res/layout-w900dp`](app/src/main/res/layout-w900dp), 단위 테스트는 [`src/test`](app/src/test)에 있습니다.

## 참고 자료

- [상세 실행 방법과 기존 검증 기록](예제_실행방법.txt)
- [화면 구성과 디자인 적용 기록](디자인_구성안.txt)
- [공모전 지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission) · [심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards) — 2026-10-31 확인
- [Pleos Connect 개발 문서](https://document.pleos.ai/en/docs/connect/guide/connect) · [Gleo Interface](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/core-concepts)
- [Android 마이크 포그라운드 서비스](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone) · [오디오 입력 공유](https://developer.android.com/media/platform/sharing-audio-input)
- 주요 외부 의존성: [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Material Components for Android](https://github.com/material-components/material-components-android), [JUnit 4](https://github.com/junit-team/junit4), Pleos Vehicle SDK. 각 의존성의 라이선스·SDK 이용 조건을 따릅니다.
