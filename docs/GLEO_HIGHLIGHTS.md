# Gleo 연동과 자동 하이라이트

2026-11-06부터 새 녹음 종료 후 하이라이트를 서버에서 자동 생성합니다. 앱의 수동 **웃음 구간 찾기** 버튼과 기기 내 `HighlightService`는 제거했습니다. 하이라이트 목록·진행 상태 표시·재생은 유지합니다. 분석 구현은 `server/momo/highlights/`이며 Gleo는 저장된 결과를 보여주거나 재생하는 역할입니다.

## 실행 흐름

1. [백엔드](../server/README.md)를 실행합니다. 이 PC에는 음성·주제 모델을 준비했습니다.
2. 앱에서 녹음을 시작하고 종료합니다. 정상 저장된 M4A마다 자동 작업을 등록합니다.
3. 앱은 설치별로 자동 인증하고 원본을 업로드합니다. 서버 연결 실패 시 원본과 요청을 보관하고 재시도합니다.
4. 서버가 **YAMNet 웃음 기준 0.1 → Whisper 한국어 STT → Qwen3.5-4B 주제 분할 → 웃음 포함 주제 최대 3개 추출**을 수행합니다.
5. 앱이 완료 파일을 자동 다운로드합니다. **하이라이트** 화면에서 해당 녹음의 결과를 확인하고 클립을 재생합니다.

기존 보관 녹음을 소급 업로드하지 않습니다. 별도 가져오기 도구로 넣은 파일도 새 녹음 종료 이벤트가 없으므로 자동 요청 대상이 아닙니다. 앱 화면을 닫아도 WorkManager가 이어서 처리하며, 강제 종료·절전·네트워크 상황에 따라 실행이 지연될 수 있습니다. 서버 작업과 업로드에 동일한 idempotency key를 사용해 중복 작업을 방지합니다.

기본 입력 한도는 80MB·2시간, 서버 작업 제한 시간은 30분입니다. 2시간 녹음의 실제 성능은 검증하지 않았습니다. 웃음이나 유효한 전사가 없으면 클립 0개로 정상 완료하고 화면에 안내합니다. 분석 실패 시 원본은 유지합니다.

## 저장소와 구현 위치

| 위치 | 내용 |
|---|---|
| 앱 `noBackupFilesDir/recordings/` | 기존 녹음 원본 |
| 앱 Room `highlight_requests` | 원본 ID·설치 ID·서버 작업 ID·중복 방지 키·진행 상태 |
| 앱 `noBackupFilesDir/highlights/server-<jobId>/` | 다운로드한 `clip_N.m4a`와 `index.json` |
| 서버 `data/objects/highlights/<jobId>/<lease>/` | M4A·SRT·전체 전사 JSON |
| 서버 `data/objects/results/<jobId>/<lease>.json` | 클립 제목·시간·크기·해시 결과 |

앱은 크기와 SHA-256을 확인하고 모든 클립이 저장된 뒤 index를 기록합니다. 원본을 삭제해도 이미 다운로드한 클립은 남습니다. 서버 업로드 원본은 작업 종료 후 24시간, 결과·클립·자막·전사는 30일 뒤 정리합니다. 앱 클립은 서버 보관기간과 별도로 유지되며 앱 데이터 삭제/제거 시 사라집니다. 클립별 삭제·외부 내보내기 UI는 아직 없습니다.

주요 코드: `RecordingService.finishRecording` → `AutomaticHighlights`/`AutomaticHighlightWorker` → `/v1/highlights` → `momo.highlights.pipeline` → `HighlightStore.saveServer`. Room 2→3 및 Alembic 0003 마이그레이션은 기존 데이터에 테이블을 추가합니다.

## 구간 선정 방식

서버 FFmpeg가 16 kHz 모노 PCM으로 디코딩합니다. YAMNet 입력은 15,600 샘플, 간격은 7,800 샘플입니다. 웃음 관련 6개 클래스의 최대 점수 **0.1 이상**, 음악 점수보다 크고 음악 점수 0.5 미만인 창을 후보로 묶습니다. Whisper는 녹음 전체를 한국어로 전사하고 단어 시각을 반환합니다.

Qwen은 전사를 주제별로 나누며 발화 ID만 선택합니다. 실제 시각은 STT에서 가져오고 웃음이 들어 있는 주제를 점수순으로 최대 3개 고른 뒤 시간순으로 저장합니다. 고정 앞 12초·뒤 6초 방식은 자동 처리 경로에서 사용하지 않습니다. 주제 추론은 기본 CPU, Whisper는 서버 설정에 따른 CUDA/CPU를 사용합니다. FFmpeg로 M4A를 인코딩하고 주제 구간의 SRT도 서버에 저장합니다.

웃음 점수가 재미를 보장하지 않으며 라디오·영상 속 웃음도 후보가 될 수 있습니다. STT와 주제 경계는 모델 추정이며 차량 소음 조건의 품질을 별도로 확인해야 합니다. 화자 구분이 포함된 MOMO 영상 분석은 별도 작업입니다.

## Gleo 명령 계약

| 함수 | 입력 | 처리 |
|---|---|---|
| `SHOW_RECORDING_HIGHLIGHTS` | 없음 | 최근 저장 결과와 클립 번호·제목·길이를 표시 |
| `PLAY_RECORDING_HIGHLIGHT` | 필수 정수 `highlightNumber`, 1–3 | 현재 목록의 클립 재생 |

`assets/schema.json`은 위 두 기능만 노출합니다. 과거 등록된 `FIND_RECORDING_HIGHLIGHTS` Intent는 호환을 위해 받지만 현재 상태·저장 결과만 표시하며 업로드나 분석을 시작하지 않습니다. 선택 `recordingId`는 내부 녹음 ID만 허용합니다.

짧은 action과 `${applicationId}.<functionName>`을 모두 처리하고 자기 패키지 접두사만 제거합니다. 개별 Intent extra와 `ACTION_PARAM` JSON을 지원합니다. 잘못된 JSON, 소수·범위 초과 번호, 충돌하는 파라미터를 거부합니다.

Callback은 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` broadcast의 `packageName`, `response`입니다. `response`에 `functionName`, `status`, 영어 `message`, `mode`를 담고 목록 `data`는 JSON 객체 배열을 직렬화한 문자열입니다. 재생 응답은 `mode: silent`입니다.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb shell am start --user current -a com.example.hd_project.SHOW_RECORDING_HIGHLIGHTS -p com.example.hd_project
& $adb shell am start --user current -a com.example.hd_project.PLAY_RECORDING_HIGHLIGHT -p com.example.hd_project --ei highlightNumber 1
```

이 명령은 앱 Intent 검증이며 Gleo 자연어·음성 서비스 전체 시험은 아닙니다. 음성 예시는 “하이라이트 보여줘”, “첫 번째 하이라이트 틀어줘”입니다. 2026-10-31에는 Gleo가 패키지 접두사가 붙은 찾기 action을 보냈고 앱은 짧은 이름만 받던 문제를 확인·수정했습니다. 해당 관찰은 과거 수동 분석 시점의 기록이며 새 스키마의 실제 음성 호출·배포 승인 검증을 대신하지 않습니다.

## 검증 방법

서버: `server/.venv/Scripts/python.exe -m pytest -q`를 서버 폴더에서 실행합니다. 자동 업로드 계약·중복 방지·소유권·빈 결과·보관기간·0.1 기준·주제 경계를 검증합니다. 앱: debug/기기 테스트 APK·단위 테스트·Lint를 빌드하고 `AutomaticHighlightsTest`, `GleoActionFlowTest`, `GleoIntegrationTest`를 ADB instrumentation으로 실행합니다. 실제 서버 테스트는 합성 한국어 음성과 공개 웃음 샘플을 사용하며 사용자 녹음을 시험 입력으로 전송하지 않습니다.

기존 자료가 있는 에뮬레이터는 APK를 `adb install -r`로 덮어 설치합니다. 앱 제거를 포함할 수 있는 `connectedDebugAndroidTest`는 별도 테스트 AVD에서만 실행합니다. 기존 스크린샷과 단위 테스트 14개/25개 등 이전 수치는 2026-10-31 기기 내 분석 구현의 기록입니다.

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
