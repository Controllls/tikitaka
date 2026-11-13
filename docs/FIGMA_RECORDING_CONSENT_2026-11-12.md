# 녹음 동의 화면 Figma 적용 및 검증

작성일: 2026-11-12. 대상: `com.example.hd_project`의 녹음 시작 전 동의 단계.

## 실제 확인한 디자인

- [M-01 가로 시안, 617:3396](https://www.figma.com/design/Y5hqYUqZsj6gQu1QyixBYv/Untitled?node-id=617-3396)
- [M-01 세로 시안, 987:33746](https://www.figma.com/design/Y5hqYUqZsj6gQu1QyixBYv/Untitled?node-id=987-33746)
- `get_design_context`의 코드·스타일·컴포넌트 정보와 두 시안의 원본 스크린샷을 확인했다. Figma는 작은 모달이 아닌 사이드바가 유지되는 별도 안내 화면이다.
- `design/figma/2026-11-12/consent-*-context.txt`, `screens/consent-*.png`에 저장했다. 전체 아카이브는 컨텍스트 26개, 원본 에셋 213개이다. 이번 두 화면의 에셋은 기존 런타임 이미지와 SHA-256이 같아서 이미지 51개를 그대로 재사용한다.

## 변경한 화면과 기능

`TikiActivity.requestRecording()`의 기본 `MaterialAlertDialog`를 `consent` 경로와 `TikiRecordingConsent` 네이티브 화면으로 교체했다. `TikiArt`, Asta Sans, 원본 로고·탐색·뒤로가기·마이크 이미지 7개를 재사용한다. 마이크 슬롯 112dp, 아이콘 48dp, 버튼 높이 88dp·반경 44dp·간격 24dp를 시안과 대조했다.

녹음 준비 → 동의 안내 → 명시적 확인 → Android 마이크 권한 → 기존 녹음 서비스 순서로 동작한다. 취소·헤더 뒤로가기·시스템 뒤로가기는 녹음 준비로 돌아가며, `이번에는 녹음만 보관` 선택을 유지한다. 안내 화면·선택은 Activity 재생성 후에도 복원한다. 연속 확인, 권한 거부, 다른 메뉴 이동으로 녹음을 잘못 시작하지 않게 처리했다.

프로젝트 구현 선택:

- 실제 저장·전송 동작을 알리도록 안내 첫 줄을 선택에 따라 바꾼다. 자동 제작은 분석 서버 전송, 녹음만 보관은 기기 저장임을 명시한다. 나머지 문구·배치·버튼은 M-01을 따른다.
- 원본 1700×1184 및 840×1184에서 디자인 여백을 사용한다. 더 좁으면 버튼 문구의 실측 폭에 따라 세로 배치하며, 낮은 창에서는 하단 여백을 줄이고 필요한 경우 스크롤한다. 900dp·1000dp 분기는 프로젝트 선택이며 Pleos 공식 분기점이 아니다.
- 이번 작업은 기존 매 녹음 동의 확인 동작을 유지한 디자인 교체다. 기능명세의 **첫 녹음에만 안내**와 지속적인 동의 이력 저장은 구현 완료로 포함하지 않는다. 설정의 녹음과 개인정보 안내는 기존 경로를 유지한다.

## 적용 규칙과 환경

- 규칙: SIZE-01~06, WIN-01/02/04, STATE-01~03, DRIVE-04. 실제 할당된 앱 창과 런타임 밀도를 사용한다. 시스템 권한창은 Android가 제공한다.
- 공식 화면 수치는 개발 규칙서의 12개 프리셋을 정적 검증 입력으로 사용했다. Figma의 앱 전용 치수와 구분한다.
- SDK·의존성 변경 없음: minSdk 28, compileSdk/targetSdk 37, Pleos Vehicle 2.3.2, AGP 9.4.1, AppCompat 1.6.1, Material 1.10.0.
- 실행: Pleos Connect v2.1.0, Android API 34, emulator-5554, 사용자 10. 실제 앱 창 1682×1210dp, 160dpi. 한국어 차량 앱을 대상으로 하며 최종 마켓 카테고리·서비스 지역 승인 범위는 미확정이다.

## 검증 결과

| 검증 | 결과 |
| --- | --- |
| debug 앱·instrumentation APK 빌드 | 통과 |
| Android Lint | 오류 0, 기존 경고 71 |
| 실제 에뮬레이터 instrumentation | 3개 통과, 최종 실행 13.998초 |
| 동의 전·취소·뒤로가기·재생성 | 녹음 미시작, 보관 선택 유지 |
| 명시적 동의·연속 두 번 누르기 | 실제 AAC 녹음 1개 생성·저장, 녹음만 보관에서 제작 작업 미등록 |
| 실제 시스템 마이크 권한 거부·재시도 | 동의 화면 유지·녹음 미시작·취소 후 보관 선택 유지. 검증 후 기존 허용 권한 복원 |
| 12개 프리셋 × 자동 제작/기기 보관 | 정적 렌더 24개, 텍스트·가로 영역 잘림 0, 버튼 간격 24dp 확인 |
| 이미지 원본·런타임 파일 | 7개 모두 비어 있지 않음, SHA-256·SVG 크기·PNG 크기·코드 슬롯 확인 |

테스트는 새로 만든 짧은 녹음만 정리하고 기존 녹음과 세션 저널을 복원한다. 앱은 `adb install -r`로 업데이트했다.

측정값: `am start -W`의 한 차례 cold launch TotalTime은 1974ms였다. 이는 화면 모든 콘텐츠의 로드 시간·터치 피드백·실차 성능 측정과는 다르다.

증거: `build/reports/console-connection/consent-build.txt`, `consent-instrumentation.txt`, `consent-assets.json`, `consent-permission-check.json`, `consent-final-device.png`, `tikitaka-consent-validation/layouts.json`과 PNG. 새 테스트는 `app/src/androidTest/java/com/example/hd_project/TikiConsentTest.kt`에 있다.

## 미검증 범위

12개 조합은 명시한 dp로 네이티브 View를 측정·배치한 정적 테스트다. FormFactorConsole의 실제 12개 가상 창 전환, DPI 변경, 시스템 패널·키보드 조합, 프로세스 강제 종료 복원, 실차 및 최종 지역별 주행 심사는 완료로 보고하지 않는다. 기존 주행 제한과 영상 재생 경로는 이번 변경 대상이 아니다.
