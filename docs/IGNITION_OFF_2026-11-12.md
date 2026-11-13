# 시동 OFF 녹음 저장 연결

2026-11-12. 대상은 티키타카 Android 앱의 녹음 서비스와 로컬 Pleos Virtual Console이다. 화면 규격·SDK 버전은 유지한다. 앱 카테고리·서비스 지역·실차 전원 API와 승인 권한은 미확정이다.

## 원인과 적용 범위

기존 8088 연결은 에뮬레이터의 Vehicle SDK/Car 서비스 연결을 확인한 것이었다. `RecordingService`가 시동 OFF를 받아 저장하는 동작은 빠져 있었다. 이는 Figma 기능명세 FN-08의 미구현 항목이었다.

로컬 Gradle 캐시의 Vehicle 2.3.2 sources.jar를 확인했다. `IgnitionState` 열거형은 있지만 이에 대응하는 공개 읽기/구독 API는 확인되지 않았다. `HvacPowerState`는 공조 전원으로 차량 시동과 구분한다. SDK에 확인되지 않은 API나 승인되지 않은 새 Vehicle 권한을 추가하지 않았다.

이번 연결은 **debug 빌드의 ranchu/goldfish 에뮬레이터 전용**이다. `app/src/debug/.../RecordingPowerSignals.kt`가 에뮬레이터 내부 `http://127.0.0.1:8088/vehicle/power-system`을 읽는다. PC에서 여는 8088은 이 콘솔로의 ADB 포워딩이다. release 구현에는 콘솔 HTTP 연결이 없다. 실제 차량의 지원 API·권한·종료 유예 시간 검증은 남아 있다.

## 실제 동작

1. 동의 후 시작한 녹음 서비스가 전원 상태 확인을 시작한다. 녹음·일시정지 중 1초 간격으로 읽으며 화면이 사라져도 유지한다.
2. `bridge_ready`와 `emulator_active`가 참인 응답의 `actual.0.state`와 `timestamp_ms`만 사용한다. `desired` 요청값을 실제 상태로 취급하지 않는다.
3. 해당 녹음 시작 이후 발생한 새 `Off` 이벤트가 오면 기존 직접 종료와 같은 저장 경로를 한 번 실행한다. 이전 녹음의 OFF·중복·역순 이벤트, 미지원 상태, 연결 실패는 녹음을 종료하지 않는다.
4. AAC 구간 마감 → M4A 결합·검증 → 파일 확정 → 저널 저장을 진행한다. 저널에 `stopTrigger=ignition_off`를 남긴다. OFF 수신 자체로 저장 성공을 표시하지 않는다.
5. 정상 저장된 유음 녹음은 기존 세션 선택대로 자동 제작을 예약한다. `녹음만 보관`, 무음, 실패·부분 복구 정책은 유지한다. 기존 WorkManager와 복구 저널로 제작 대기를 보존한다.
6. 종료 후 전원 상태 확인을 해제한다. `On`으로 다시 바뀌어도 녹음을 자동 시작하지 않는다.

1초 폴링은 시연 구현 선택이며 Pleos 공식 반응 시간이나 실차 종료 보장 시간이 아니다. 폴링 사이에 발생했다 사라진 OFF를 놓칠 수 있다. 실제 전원 차단·앱 강제 종료에서는 기존 저널 기반 복구가 적용되며 정상 저장을 보장하지 않는다.

## 시연 조작

PC 프로젝트 루트의 PowerShell에서:

```powershell
.\tools\set-virtual-ignition.ps1 On
# 앱에서 동의 후 녹음 시작
.\tools\set-virtual-ignition.ps1 Off
```

신규 녹음을 시작하기 전부터 남아 있던 OFF는 자동 종료 계기로 사용하지 않는다. 시연에서는 On → 녹음 시작 → Off 순서로 확인한다. 에뮬레이터 프로세스 종료·화면 전원 버튼과 이 전원 상태 신호는 서로 다르다.

## 검증 기록

- 변경 기능: 화면과 독립된 시동 OFF 정상 종료, 일시정지 상태 저장, 종료 계기 저널, debug/release 분리.
- 적용 규칙: STATE-01·04, PERF-01, API-01. Figma FN-08의 정상 종료·저장 검증 순서를 따른다. 주행 제한은 기존 CarUxRestrictions 경로를 유지한다.
- 버전: Vehicle 2.3.2, minSdk 28, compileSdk/targetSdk 37, AGP 9.4.1, Gradle 9.6.0. SDK·권한 변경 없음.
- 빌드: debug APK와 계측 APK 생성, release Kotlin 컴파일 성공.
- 단위 검사: 새 전원 이벤트 검사 5개 포함 총 49개 통과. 이전 OFF, 중복·역순, 연결 실패·미정, 활성 상태, 첫 확인 전 발생한 새 OFF를 검사했다.
- Lint: 오류 0개·경고 71개.
- 설치: 사용자 승인 후 기존 녹음의 파일 마감 완료를 기다려 debug APK를 `install -r`로 업데이트했다. 기존 108,897,715바이트 M4A의 SHA-256이 설치·테스트 전후 동일하고 원래 저널이 복원된 것을 확인했다. 기존 녹음은 앱에서 `silent`로 판정했으며 원본은 보관되어 있다.
- 기기 검증: Pleos API 34 에뮬레이터 user 10에서 **계측 2개 통과**. 실제 8088 On/Off를 통한 녹음 중·일시정지 중 종료, 화면 비활성 중 저장, 결과 M4A 검증, 녹음만 보관 유지, 중복 저장·자동 재시작 방지를 확인했다. 테스트 생성 녹음만 정리하고 테스트 전 전원 상태·저널을 복원했다. 설치 후 앱을 다시 실행했다.
- 화면 유형: UI 배치 변경 없음. 이번 작업에서 12개 폼 팩터 검사 미실행.
- 성능 측정: 테스트의 OFF 요청부터 저장 완료 관측까지 녹음 중 222ms(저장 음원 2,995ms), 일시정지 중 933ms(저장 음원 2,205ms). 에뮬레이터의 짧은 시험 녹음 측정이며 실차·장시간 녹음의 저장 시간 보장값이 아니다.
- 미검증: 실차 시동 신호 구독·승인 권한·전원 차단 유예 시간, 실차 마이크와 장시간 저장 시간, 모든 폼 팩터.

로그: `build/reports/console-connection/power-build.txt`, `power-instrumentation-build.txt`, `power-instrumentation.txt`, `power-validation.json`. 테스트: `RecordingPowerOffGateTest`, `RecordingPowerSignalsTest`.
