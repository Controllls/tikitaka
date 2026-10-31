# Gleo 하이라이트 개발 브랜치

`feature/gleo-highlights` / 기준 커밋 `240df7b` / 확인일 2026-10-31.

Gleo의 앱 호출 규약을 구현하고, 저장된 녹음에서 웃음이 감지된 구간을 실제 M4A 클립으로 저장합니다. **Gleo가 녹음 내용을 분석하는 구현은 아닙니다.** 분석은 기기에 포함한 YAMNet이 수행합니다. 전사, 대화 문맥 평가, 재미있는 주제에 따른 제목 생성은 아직 없습니다.

## 직접 실행

이 브랜치의 Debug 앱 이름은 **이동 기록 · Gleo**입니다. 패키지가 `com.example.hd_project.gleo`이므로 기존 앱과 나란히 설치되며 녹음 파일도 분리됩니다. 기존 앱의 녹음이 새 앱으로 자동 복사되지는 않습니다.

```powershell
.\gradlew.bat assembleDebug
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.example.hd_project.gleo/com.example.hd_project.MainActivity
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

메인 스키마는 `app/src/main/assets/schema.json`, Debug 패키지용 스키마는 `app/src/debug/assets/schema.json`입니다. 모두 `component: activity`를 사용합니다. Manifest에 동일한 action과 DEFAULT category를 등록했습니다.

공식 문서의 설명은 개별 Intent extra, 코드 예시는 `ACTION_PARAM` JSON을 사용하므로 두 전달 형식을 지원합니다. 잘못된 JSON, 숫자가 아닌 번호, 소수·범위 초과 번호, 충돌하는 파라미터는 거부합니다. 외부 파일 경로는 입력받지 않으며 `recordingId`는 앱이 저장한 녹음 목록에서만 조회합니다.

Callback은 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` broadcast에 `packageName`, `response`를 담습니다. `response`에는 `functionName`, `status`, 영어 `message`, `mode`가 포함됩니다. 목록 `data`는 JSON 객체 배열을 직렬화한 **문자열**입니다. 분석 접수 응답은 작업 완료를 의미하지 않습니다. 재생에는 `mode: silent`를 사용해 응답 음성과 클립이 겹치지 않게 합니다.

### 승인 전 개발용 호출

다음은 Gleo가 전달하는 Intent를 ADB로 재현하는 테스트입니다. 음성 인식이나 Gleo 자연어 처리를 검증하는 명령이 아닙니다.

```powershell
& $adb shell am start -a FIND_RECORDING_HIGHLIGHTS -p com.example.hd_project.gleo
& $adb shell am start -a SHOW_RECORDING_HIGHLIGHTS -p com.example.hd_project.gleo
& $adb shell am start -a PLAY_RECORDING_HIGHLIGHT -p com.example.hd_project.gleo --ei highlightNumber 1
& $adb logcat -d -s GleoBridge:I '*:S'
```

Gleo 음성 예시: “이동 기록에서 웃긴 부분 찾아줘”, “하이라이트 보여줘”, “첫 번째 하이라이트 틀어줘”. 실제 자연어 호출을 사용하려면 Pleos 측 App Schema 검토·승인과 해당 환경의 Gleo 연동이 필요합니다. 이번 검증은 앱 측 Intent/Callback까지이며, **승인이나 실제 Gleo 음성 호출 성공을 주장하지 않습니다.**

## 후보 선정 방식과 한계

AAC를 스트리밍 디코딩하고 채널 평균 및 연속 선형 리샘플링으로 16 kHz 모노를 만듭니다. YAMNet에는 15,600개 샘플(975 ms)을 487.5 ms 간격으로 입력합니다. 모델·라벨과 출처/라이선스는 앱 assets에 포함되며 런타임 다운로드나 녹음 업로드는 없습니다.

웃음 관련 6개 클래스의 최대 점수가 0.25 이상이고 음악 점수보다 크며 음악 점수가 0.5 미만인 구간을 후보로 봅니다. 가까운 양성 구간이 두 개 이상이어야 클립을 만듭니다. 앞 12초·뒤 6초를 붙이고, 겹치는 후보에서는 점수가 높은 것을 고른 뒤 최대 3개를 시간순으로 저장합니다. 긴 웃음은 나눠 최대 약 43초의 클립으로 제한합니다. 수치는 초기 실험값이며 차량 데이터로 보정하지 않았습니다.

클립은 MediaExtractor/MediaMuxer로 AAC 패킷을 복사해 저장합니다. 경계가 AAC 프레임에 맞춰지므로 요청 구간과 약 한 프레임 차이가 날 수 있고 문장이 중간에 잘릴 수 있습니다. 전사 기반 문장 경계 정렬은 후속 작업입니다.

약한 웃음·다른 소리와 섞인 웃음을 놓칠 수 있고, 라디오나 동영상의 웃음을 동승자의 웃음과 구분하지 못합니다. 웃음 없이 재미있는 대화도 찾지 못합니다. 소수의 테스트 음원으로 재미 판별 정확도나 실차 성능을 주장하지 않습니다. 현재 선형 리샘플러에는 전용 안티앨리어싱 필터가 없으며 실제 차량 소음 조건에서 추가 검증해야 합니다.

## 검증

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest connectedDebugAndroidTest lintDebug --console=plain
```

Pleos Connect v2.1.0 / Android 14 API 34 / x86_64에서 확인합니다.

2026-10-31 실행 결과: Debug 빌드 성공, 단위 테스트 14개·기기 테스트 8개 통과, Lint 오류 0개·경고 15개. 신규 네이티브 런타임의 16 KB 정렬 경고는 LiteRT 1.4.2 적용 후 사라졌습니다. 실제 16 KB 페이지 기기에서 실행한 결과는 아닙니다.

- 단위 테스트: 기존 7개 + 후보 선택/구간 겹침/길이 제한/리샘플링 7개.
- 기기 테스트: 총 8개. 설치 패키지와 schema/Manifest 대응, 두 파라미터 형식, 오류 인자, callback 수신, 실제 모델의 웃음·무음 처리, AAC 클립의 재생 가능한 파일 정보와 원본 보존을 확인합니다.
- 실제 Activity action으로 분석 접수 → 저장 → 목록 → 재생을 실행하고, 각 callback의 7초 이내 도착과 재분석 시 중복 생성 방지를 확인합니다.
- 공개 테스트 음원은 `androidTest/assets`에만 포함됩니다. 앱 APK에는 들어가지 않습니다. 출처와 사용 조건은 해당 폴더의 `NOTICE.txt`에 있습니다. 실제 이용자의 녹음을 테스트에 사용하지 않았습니다.

별도 수동 시연에서는 `1-73123-A-26` 웃음 샘플 앞뒤에 15초씩 무음을 붙인 35초 파일을 Debug 앱에만 넣었습니다. 약 3초~23초의 클립을 실제 생성했고, 앱 Intent로 재생을 시작한 뒤 `success` callback을 확인했습니다. [결과 화면](images/gleo-highlights.png)은 이 테스트 음원의 결과이며 실제 Gleo 음성 승인 시험은 아닙니다. 테스트 샘플과 클립은 에뮬레이터의 별도 Debug 앱에 남겨 화면에서 확인할 수 있게 했습니다.

분석은 사용자 요청으로만 시작합니다. 취소/실패 시 미완성 출력은 제거하고 원본은 유지합니다. 프로세스가 종료되면 자동 재시작하지 않으며 다음 화면에서 분석 중단을 표시합니다. 다음 분석 시작 때 미완성 출력 폴더를 정리합니다. Android 15 이상의 서비스 시간 제한 콜백도 처리하지만 해당 OS/실차에서는 아직 검증하지 않았습니다.

## 공식 참고 문서

- [Gleo App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Gleo Callback Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [Intent와 Callback 코드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)
- [스키마 전달·승인](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/deliver-schema)
- [YAMNet 모델](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet)
- [LiteRT Android](https://developers.google.com/edge/litert/android)
