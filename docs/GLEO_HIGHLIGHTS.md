# Gleo 연동과 하이라이트

`feature/gleo-highlights`에서 개발한 기능을 `main`에 병합했습니다. 확인일 2026-10-31.

Gleo의 앱 호출 규약을 구현하고, 저장된 녹음에서 웃음이 감지된 구간을 실제 M4A 클립으로 저장합니다. **Gleo가 녹음 내용을 분석하는 구현은 아닙니다.** 분석은 기기에 포함한 YAMNet이 수행합니다. 전사, 대화 문맥 평가, 재미있는 주제에 따른 제목 생성은 아직 없습니다.

## 직접 실행

Debug와 release의 앱 이름은 **이동 기록**, 패키지는 `com.example.hd_project`입니다. 기존 앱과 같은 서명으로 설치하면 녹음을 유지하며 업데이트합니다. 이전의 별도 Gleo 테스트 앱용 패키지·이름·스키마 오버라이드는 제거했습니다.

2026-10-31 현재 에뮬레이터의 기존 앱을 1.1(`versionCode 2`)로 업데이트했습니다. 기존 녹음 17개를 보존하고 별도 테스트 앱의 녹음 5개와 하이라이트 파일 2개를 옮긴 뒤 SHA-256으로 내용 일치를 확인했습니다. 총 녹음 22개와 기존 미완성 `.pending` 파일을 보존한 후 별도 테스트 앱을 제거했습니다. 이 데이터 이관은 해당 에뮬레이터에서 수행한 작업이며 앱의 자동 이관 기능은 아닙니다.

```powershell
.\gradlew.bat assembleDebug
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.example.hd_project/.MainActivity
```

1. 정차 상태에서 사용자가 녹음 시작을 선택하고, 대화를 녹음한 뒤 종료합니다.
2. 하단 **하이라이트 → 녹음 선택 → 웃음 구간 찾기**를 누릅니다.
3. 화면을 나가도 포그라운드 서비스가 분석을 이어갑니다. 알림이 허용되어 있으면 진행 알림과 취소 버튼을 사용할 수 있습니다.
4. 완료된 클립을 눌러 재생합니다. 후보가 없으면 없다고 표시합니다.

출력은 `noBackupFilesDir/highlights/<generation>/clip_N.m4a`와 `index.json`입니다. 원본의 파일명·구간·모델 점수와 생성 시각을 저장합니다. 원본 녹음 목록의 개수·총 시간에 하이라이트를 중복 집계하지 않습니다. 원본을 삭제해도 이미 저장한 클립은 유지됩니다. 이 프로토타입에는 클립별 삭제·외부 내보내기 UI가 아직 없습니다.

## Gleo 명령 계약

| 함수 | 입력 | 처리 |
|---|---|---|
| `FIND_RECORDING_HIGHLIGHTS` | 선택 `recordingId`; 생략하면 가장 최근 저장된 녹음 | 분석 요청 접수 후 즉시 응답. 작업은 백그라운드에서 계속 진행 |
| `SHOW_RECORDING_HIGHLIGHTS` | 없음 | 가장 최근 분석 결과를 화면에 표시하고 클립 번호·제목·길이 반환 |
| `PLAY_RECORDING_HIGHLIGHT` | 필수 숫자 `highlightNumber`, 1–3의 정수 | 표시 중인 목록의 해당 클립을 재생. 재생 시작 확인 후 응답 |

스키마는 debug/release 공통으로 `app/src/main/assets/schema.json`을 사용합니다. 모든 기능은 `component: activity`입니다. Manifest에는 문서 예시의 짧은 action과 실제 Gleo 4.2.0.15가 전송하는 `${applicationId}.<functionName>` 형식의 action을 모두 DEFAULT category로 등록했습니다. 앱에서는 자기 패키지의 접두사만 제거하고, Callback의 `functionName`은 스키마의 짧은 함수 이름을 유지합니다.

공식 문서의 설명은 개별 Intent extra, 코드 예시는 `ACTION_PARAM` JSON을 사용하므로 두 전달 형식을 지원합니다. 잘못된 JSON, 숫자가 아닌 번호, 소수·범위 초과 번호, 충돌하는 파라미터는 거부합니다. 외부 파일 경로는 입력받지 않으며 `recordingId`는 앱이 저장한 녹음 목록에서만 조회합니다.

Callback은 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` broadcast에 `packageName`, `response`를 담습니다. `response`에는 `functionName`, `status`, 영어 `message`, `mode`가 포함됩니다. 목록 `data`는 JSON 객체 배열을 직렬화한 **문자열**입니다. 분석 접수 응답은 작업 완료를 의미하지 않습니다. 재생에는 `mode: silent`를 사용해 응답 음성과 클립이 겹치지 않게 합니다.

### 개발용 호출

다음은 Gleo가 전달하는 Intent를 ADB로 재현하는 테스트입니다. 음성 인식이나 Gleo 자연어 처리를 검증하는 명령이 아닙니다.

```powershell
& $adb shell am start --user current -a com.example.hd_project.FIND_RECORDING_HIGHLIGHTS -p com.example.hd_project
& $adb shell am start --user current -a com.example.hd_project.SHOW_RECORDING_HIGHLIGHTS -p com.example.hd_project
& $adb shell am start --user current -a com.example.hd_project.PLAY_RECORDING_HIGHLIGHT -p com.example.hd_project --ei highlightNumber 1
& $adb logcat -d -s GleoBridge:I '*:S'
```

Gleo 음성 예시: “이동 기록에서 웃긴 부분 찾아줘”, “하이라이트 보여줘”, “첫 번째 하이라이트 틀어줘”. 공식 문서는 App Schema 검토·승인 절차를 안내합니다. 아래 에뮬레이터의 관찰 결과는 배포 승인 여부를 의미하지 않습니다.

### 실제 음성 명령에서 확인한 호출 이름 불일치

2026-10-31 에뮬레이터 로그에서 Gleo가 “이동 기록에서 웃긴 부분 찾아줘”를 정확히 전사하고, 이 앱의 `FIND_RECORDING_HIGHLIGHTS`를 선택한 것을 확인했습니다. 따라서 이 요청의 실패는 음성 인식 실패나 스키마 미발견이 아니었습니다.

Gleo는 `com.example.hd_project.FIND_RECORDING_HIGHLIGHTS`를 보냈지만, 당시 Manifest와 앱의 요청 처리기는 짧은 이름만 받았습니다. 이 때문에 `No Activity found to handle Intent`가 발생했습니다. 두 형식의 Intent를 모두 처리하도록 수정하고 다음을 확인했습니다.

- 설치된 APK에서 세 기능의 두 action 형식이 모두 Activity에 연결됩니다.
- 실제 관찰한 패키지 접두사와 `ACTION_PARAM` JSON을 처리하며, 다른 패키지·잘못된 파라미터는 거부합니다. 관련 기기 테스트 4개를 통과했습니다.
- 패키지 접두사가 붙은 분석 Intent를 ADB로 보내 공개 테스트 녹음의 기존 결과 표시와 `FIND_RECORDING_HIGHLIGHTS -> success` 응답을 확인했습니다.
- 단위 테스트 25개 통과, Lint 오류 0개·경고 17개이며, 업데이트 전 녹음·하이라이트 파일 28개의 SHA-256이 업데이트 후에도 일치합니다.

이 수정 후 검증은 ADB로 앱 호출을 재현한 결과입니다. 수정된 앱에서 **음성 명령 → 화면 실행 → Callback 수신**이 모두 이어지는지는 실제 마이크로 재시험해야 합니다. 녹음 내용의 STT는 구현하지 않았으며, Gleo가 명령을 전사하는 처리와 앱의 웃음 감지는 별개입니다.

## 후보 선정 방식과 한계

AAC를 스트리밍 디코딩하고 채널 평균 및 연속 선형 리샘플링으로 16 kHz 모노를 만듭니다. YAMNet에는 15,600개 샘플(975 ms)을 487.5 ms 간격으로 입력합니다. 모델·라벨과 출처/라이선스는 앱 assets에 포함되며 런타임 다운로드나 녹음 업로드는 없습니다.

웃음 관련 6개 클래스의 최대 점수가 0.25 이상이고 음악 점수보다 크며 음악 점수가 0.5 미만인 구간을 후보로 봅니다. 가까운 양성 구간이 두 개 이상이어야 클립을 만듭니다. 앞 12초·뒤 6초를 붙이고, 겹치는 후보에서는 점수가 높은 것을 고른 뒤 최대 3개를 시간순으로 저장합니다. 긴 웃음은 나눠 최대 약 43초의 클립으로 제한합니다. 수치는 초기 실험값이며 차량 데이터로 보정하지 않았습니다.

클립은 MediaExtractor/MediaMuxer로 AAC 패킷을 복사해 저장합니다. 경계가 AAC 프레임에 맞춰지므로 요청 구간과 약 한 프레임 차이가 날 수 있고 문장이 중간에 잘릴 수 있습니다. 전사 기반 문장 경계 정렬은 후속 작업입니다.

약한 웃음·다른 소리와 섞인 웃음을 놓칠 수 있고, 라디오나 동영상의 웃음을 동승자의 웃음과 구분하지 못합니다. 웃음 없이 재미있는 대화도 찾지 못합니다. 소수의 테스트 음원으로 재미 판별 정확도나 실차 성능을 주장하지 않습니다. 현재 선형 리샘플러에는 전용 안티앨리어싱 필터가 없으며 실제 차량 소음 조건에서 추가 검증해야 합니다.

## 검증

최종 코드 검증(2026-10-31): Debug 앱·기기 테스트 APK 빌드, 단위 테스트 25개, Lint 오류 0개·경고 17개. 기존 설치를 유지하고 ADB instrumentation으로 패키지 1개·Gleo 호출 규약 5개·화면 레이아웃 2개, 총 8개를 실행해 통과했습니다. 화면 폭 841·899·900·1682dp의 홈·녹음 목록 레이아웃과 실제 차량·앱·내비 3분할 표시를 확인했습니다. [최종 시퀀스 다이어그램](sequence/시퀀스다이어그램.html)에 흐름과 검증 범위를 정리했습니다. 아래 모델·음원·Action 시연은 앞서 수행한 기록입니다.

기기 테스트는 별도의 테스트용 AVD에서 실행합니다. 현재 debug/release가 같은 패키지를 사용하므로, 테스트 도구의 앱 제거 과정이 기존 녹음에 영향을 주는 것을 피하기 위해 실제 녹음이 있는 AVD에 `connectedDebugAndroidTest`를 실행하지 않습니다. 이번 통합 검증은 APK를 덮어 설치한 뒤 ADB instrumentation으로 패키지·스키마 검증 2개를 실행했으며, 원본 데이터 보존을 별도로 확인했습니다. 빌드·단위 테스트 25개와 Lint(오류 0개·경고 17개)도 통과했습니다.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest connectedDebugAndroidTest lintDebug --console=plain
```

Pleos Connect v2.1.0 / Android 14 API 34 / x86_64에서 확인합니다.

2026-10-31 실행 결과: Debug 빌드 성공, 단위 테스트 14개·기기 테스트 8개 통과, Lint 오류 0개·경고 15개. 신규 네이티브 런타임의 16 KB 정렬 경고는 LiteRT 1.4.2 적용 후 사라졌습니다. 실제 16 KB 페이지 기기에서 실행한 결과는 아닙니다.

- 단위 테스트: 기존 7개 + 후보 선택/구간 겹침/길이 제한/리샘플링 7개.
- 기기 테스트: 총 8개. 설치 패키지와 schema/Manifest 대응, 두 파라미터 형식, 오류 인자, callback 수신, 실제 모델의 웃음·무음 처리, AAC 클립의 재생 가능한 파일 정보와 원본 보존을 확인합니다.
- 실제 Activity action으로 분석 접수 → 저장 → 목록 → 재생을 실행하고, 각 callback의 7초 이내 도착과 재분석 시 중복 생성 방지를 확인합니다.
- 공개 테스트 음원은 `androidTest/assets`에만 포함됩니다. 앱 APK에는 들어가지 않습니다. 출처와 사용 조건은 해당 폴더의 `NOTICE.txt`에 있습니다. 실제 이용자의 녹음을 테스트에 사용하지 않았습니다.

초기 수동 시연에서는 `1-73123-A-26` 웃음 샘플 앞뒤에 15초씩 무음을 붙인 35초 파일을 당시의 별도 Debug 앱에 넣었습니다. 약 3초~23초의 클립을 실제 생성했고, 앱 Intent로 재생을 시작한 뒤 `success` callback을 확인했습니다. [결과 화면](images/gleo-highlights.png)은 이 테스트 음원의 결과이며 실제 Gleo 음성 승인 시험은 아닙니다.

분석은 사용자 요청으로만 시작합니다. 취소/실패 시 미완성 출력은 제거하고 원본은 유지합니다. 프로세스가 종료되면 자동 재시작하지 않으며 다음 화면에서 분석 중단을 표시합니다. 다음 분석 시작 때 미완성 출력 폴더를 정리합니다. Android 15 이상의 서비스 시간 제한 콜백도 처리하지만 해당 OS/실차에서는 아직 검증하지 않았습니다.

## 녹음 화면의 Gleo 사용하기

하단의 **Gleo 사용하기**를 누르면 녹음 중인 경우 먼저 녹음을 종료하고 파일을 저장합니다. `RecordingService`가 `MediaRecorder.stop()`과 `release()`를 마친 뒤 최종 상태를 발행하면, 화면에서 300 ms 뒤 Gleo 듣기를 요청합니다. 저장 오류가 나면 오류를 표시하고 호출을 취소합니다. 저장 중 중복 입력은 막고, 화면을 떠나면 아직 보내지 않은 호출은 취소합니다. 녹음은 자동으로 재시작하지 않습니다.

현재 Pleos Connect v2.1.0의 `ai.umos.ambientai`가 받는 `android.intent.action.VOICE_ASSIST` 패키지 지정 브로드캐스트를 사용합니다. 이 경로는 설치된 Gleo 4.2.0.15에서 확인한 호환 동작이며, 공개 Gleo Interface의 App Schema/Callback 계약에 포함된 호출 API로 확인된 것은 아닙니다. 다른 이미지나 실차에서는 별도 검증이 필요합니다. 앱 화면은 브로드캐스트 전송을 **호출 요청**으로 표시하며, Gleo 응답 성공을 단정하지 않습니다.

2026-10-31 에뮬레이터 확인 결과:

- 일반 앱 UID에서 버튼을 눌러 Gleo의 `ListeningPassiveState` 진입 확인.
- 약 6초 녹음 중 버튼을 눌러 AAC/M4A 파일 저장 확인(44.1 kHz, mono, 재생 길이 약 6.22초). 기존 샘플 파일 보존.
- 녹음 서비스 종료 후 Gleo 로그의 `isUsingPrimaryMic: false`와 듣기 상태 진입 확인.
- 녹음 중 호출어만 말하면 `onWakeUpDetected()` 다음 `isUsingPrimaryMic: true`로 인해 Gleo가 실행을 무시하는 현상 확인.

이 검증은 **앱에서 Gleo를 여는 방향**입니다. **Gleo 음성 명령으로 앱을 여는 방향**과 하이라이트 Action 해석은 별도 시험입니다.

앱 실행 음성 시험은 아직 완료하지 못했습니다. 에뮬레이터 gRPC `injectAudio`로 합성 음성을 넣는 중 에뮬레이터가 종료되어 같은 AVD를 다시 실행했고, PC 마이크 입력과 설치 앱·저장 파일을 확인했습니다. 이 환경에서 해당 오디오 주입 시험은 반복하지 않습니다. 실제 PC 마이크로 “글레오, 이동 기록 앱 열어줘”라고 말한 뒤 Gleo 인식 결과와 앱 Activity 실행을 함께 확인해야 합니다.

## 공식 참고 문서

- [Gleo App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Gleo Callback Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [Intent와 Callback 코드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)
- [스키마 전달·승인](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/deliver-schema)
- [YAMNet 모델](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet)
- [LiteRT Android](https://developers.google.com/edge/litert/android)
