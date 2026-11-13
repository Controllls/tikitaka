# Pleos 적용 기록 — 2026-11-11

> 이 문서는 Figma 파일을 받기 전 검증 이력이다. 2026-11-12에 파일 접근·원본 저장과 차량 앱 적용을 진행했다. 현재 상태는 [Figma 적용 기록](FIGMA_IMPLEMENTATION_2026-11-12.md)을 따른다. 아래의 링크 미제공·기존 홈 화면 설명과 측정값은 당시 기록으로 보존한다.

## 작업 범위와 전제

- 앱: 이동기록, Android Kotlin / XML Views. 기존 녹음·인물·하이라이트·영상 기능을 보존한다.
- 대상: 규칙서의 6개 화면 유형, 가로·세로 총 12개 앱 창 프리셋. 표의 dp는 앱 창이며 전체 디스플레이 px가 아니다.
- 앱 카테고리: 녹음·영상 제작 기능을 가진 앱. 마켓의 최종 카테고리는 미정이며 임의로 DO(주행 중 사용 최적화)를 선언하지 않는다.
- 서비스 지역: 한국어 공모전 시연을 우선 가정. 출시 지역·지역별 승인 정책은 미확정이다. 차량이 전달한 UX 제한 플래그를 따른다.
- 의존성: minSdk 28, compileSdk / targetSdk 37, Gradle 9.6.0, AGP 9.4.1, Java 17 소스 호환성, Pleos Vehicle 2.3.2. 설치 예제의 API 34를 targetSdk로 변경하지 않는다.
- 기존 `pleos.car.permission.CAR_SEATS` 선언은 발견했으나 마켓 사전 승인 증거는 제공되지 않았다. 승인 완료로 보고하지 않는다.

## 디자인 입력 자료

사용자가 요청한 Figma 페이지: 1 디자인 시스템 / 2 기능 명세 / 3 MSLA / 4 UI / 5 제출 자료.
전달된 URL은 [공식 앱 구조 안내](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)이며 Figma 파일 URL이 아니다. 해당 다섯 페이지의 파일 링크를 요청했다. MSLA의 의미·기능은 원문 확인 전 임의로 정의하지 않는다.

Figma 파일을 받기 전에는 해당 디자인과 일치한다거나 Figma 디자인 소스를 모두 저장했다고 보고하지 않는다. 공식 규칙 저장, 공식 원본 자료 수집, 현재 코드의 규격 위반 수정과 검증은 독립적으로 진행한다.

## 규칙 적용

사용자 규칙서 원문은 `docs/PLEOS_CONNECT_AI_DEVELOPMENT_RULES.md`, 앞으로의 개발 적용 지침은 루트 `AGENTS.md`에 저장했다. 체크리스트는 실제 검증 후에만 갱신한다.

## 공식 자료 재확인

- [주행 제한 개발](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide): CarUxRestrictionsManager 구독, requiresDistractionOptimization과 NO_VIDEO 플래그에 따른 영상 차단. 해당 UX 제한 Figma 명세 자체는 사이트에서 공개하지 않는다고 명시되어 있다.
- [지역별 제한](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy): 플랫폼 버전·차량별 차이가 있으며 DO 미선언 앱은 기본적으로 주행 중 사용 불가. 앱은 전달된 플래그를 적용하며 속도로 자체 지역 정책을 추정하지 않는다.
- [성능·호환성](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability): 안전 영역, 창 변경 시 상태 유지, 화면별 기능 접근성, 반응 시간 및 권한 사전 승인.
- [마켓 등록정보](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-app-registration-info): 앱 창 dp와 마켓 이미지 px를 구분한다. 제출 지침과 크기 표기 차이는 미해결 항목으로 유지한다.

## 변경한 화면과 기능

- 홈·저장된 기록: 현재 뷰에 할당된 너비가 900dp 미만일 때 하단 탐색 버튼을 같은 폭으로 배치하고 아이콘을 위로 이동한다. 900dp는 기존 앱의 콘텐츠 분기점이며 공식 수치가 아니다. 주요 기능은 숨기지 않는다.
- 홈: 주행 제한 상태를 구독하고, 제한 중·상태 미확인·백그라운드에서는 녹음 버튼의 반복 장식 움직임과 탑승 알림 펄스를 정지한다. 녹음 기능 자체를 임의로 종료하지 않는다.
- 영상: `CarUxRestrictionsManager`의 실제 상태와 변경 콜백으로 NO_VIDEO를 적용한다. 화면 버튼뿐 아니라 VideoView.start() 자체를 제한하여 MediaController 등의 경로에서도 차단한다. 상태를 읽지 못한 경우 재생을 허용하지 않는 것은 프로젝트 구현 규칙이다. 주행 제한 해제와 포그라운드 복귀 시 자동 재생하지 않는다.
- 영상: 영상의 재생 위치를 저장·복원하고, 미리보기 크기를 실제 앱 뷰의 사용 가능 너비·높이와 밀도로 계산한다. 백그라운드에서는 영상 로딩과 재생을 중단한다.
- 홈·저장된 기록·하이라이트·인물 관리·영상: 시스템 바·디스플레이 컷아웃·IME 인셋을 공통 처리한다. 전체 디스플레이에서 고정 높이를 빼지 않는다.
- 저장된 기록: 재생 중인 녹음과 위치를 저장하고 복귀 시 일시정지 상태로 복원한다. 목록·하이라이트·영상 ScrollView에 안정적인 ID를 부여한다.
- 저장된 기록·인물 목소리 미리 듣기: 오디오 포커스 획득 후 재생하고, 손실 시 정지/일시정지, 덕킹 시 감쇠, 종료 시 반환한다. 하이라이트는 기존 포커스 처리를 유지한다.
- Vehicle SDK의 새 제어 권한이나 DO 메타데이터를 추가하지 않았다.

## 적용 규칙과 남은 검증

| 규칙 | 적용 및 상태 |
| --- | --- |
| SIZE-01~03 | 루트는 match_parent, 실제 뷰 너비 기반 탐색 배치, 영상 dp→px 밀도 변환. 12개 프리셋·2개 밀도에서 측정 검증. |
| SIZE-04~05 | 기존 가로/세로 카드 배치를 유지하며 좁은 창 탐색을 재배치. 900dp는 앱 구현 분기점으로 명시. |
| SIZE-06 | 아래 범위를 공개하며 실제 전체 폼 팩터 인증 완료로 보고하지 않음. |
| WIN-01~03 | Driving view·시스템 창 배치·방향 정책을 변경하지 않음. Manifest에 방향 고정 없음. 차량별 다중 창 전환은 미검증. |
| WIN-04 | 전달된 systemBars / displayCutout / IME 인셋 합집합을 적용. 실제 GNB·키보드 조합 전부는 미검증. |
| STATE-01~03 | 영상·음성 재생 상태 복원 구현, 홈 재생성 및 인물 편집 회전 복원 테스트 통과. 프로세스 강제 종료 후 모든 화면·입력·작업 복원은 미검증. |
| STATE-04 | 장식 갱신·영상 중지, 기존 화면별 구독·폴링 생명주기 유지. 모든 다운로드·서버 작업의 프로세스 종료 복구는 이번 변경에서 미검증. |
| DRIVE-01~02 | 실제 CarUxRestrictions 구독 확인, 조건 분기 단위 테스트, 실제 MP4/MediaController의 제한·정지·재시작 거부·해제 후 자동재생 금지 테스트 통과. 실제 차량 주행 전환과 모든 지역 정책은 미검증. |
| DRIVE-03 | 주행 제한·미확인·백그라운드 장식 애니메이션 정지 적용. |
| DRIVE-04~05 | 기존 주요 액션 접근 유지, 좁은 탐색 메뉴 재배치. 최종 Figma 터치 영역·간격·대비 대조는 원본 링크 대기. |
| PERF-01 | 아래 실행 시간만 실측. 0.25초 터치 효과, 모든 버튼의 2초 반응, 전체 콘텐츠 10초 로딩, 차량 자원 과점유는 미검증. |
| API-01 | compileSdk 37의 android.car.jar로 실제 API 서명을 확인하고 API 34 에뮬레이터에서 구독 검증. 기존 CAR_SEATS의 마켓 사전 승인 여부는 확인 필요. |

## 사용한 SDK 및 의존성 버전

minSdk 28 / compileSdk 37 / targetSdk 37, Gradle 9.6.0, AGP 9.4.1, Pleos Vehicle 2.3.2. 기존 Material 1.10.0, AppCompat 1.6.1을 재사용했다. 컴파일·테스트 JVM은 Android Studio1의 JBR 25이며 Java 소스 호환성은 17이다.

`android.car`는 컴파일용 optional library로 사용한다. 현재 Pleos 이미지에는 이름이 `android.car`인 공유 라이브러리 등록은 없으나 BOOTCLASSPATH에 `/apex/com.android.car.framework/javalib/android.car-module.jar`가 있고 실제 API 구독이 성공한다. 따라서 공유 라이브러리를 required=true로 강제하지 않는다. 상태 조회 실패 시 영상은 차단된다.

## 테스트한 화면 유형과 방향

| 화면 유형 | Landscape | Portrait | 검증 수준 |
| --- | --- | --- | --- |
| Standard 17 | 1700×1184 dp 통과 | 840×1184 dp 통과 | 160/200dpi, 4개 XML 화면 측정 |
| Standard 14.6 | 1360×948 dp 통과 | 672×948 dp 통과 | 동일 |
| Standard 12.9 | 1274×855 dp 통과 | 628×855 dp 통과 | 동일 |
| Wide 기본형 | 1640×810 dp 통과 | 800×810 dp 통과 | 동일 |
| Wide 확장형 | 1640×1400 dp 통과 | 800×1400 dp 통과 | 동일 |
| Waterfall | 1256×800 dp 통과 | 648×800 dp 통과 | 동일 |

이는 `createConfigurationContext` + Android View 측정/배치/캔버스 캡처로 수행한 **96개 레이아웃 검사**이다. 화면은 홈·저장된 기록·하이라이트·영상의 정적 XML이며 가로 넘침·보이는 글자 폭·세로 잘림을 검사했다. 동적 데이터가 가득 찬 모든 목록이나 인물 관리 화면까지 이 표의 검증에 포함한 것은 아니다. `FormFactorConsole`에서 12개 실제 가상 디스플레이를 만든 검증, 차량 주행 상태 변경 및 터치 좌표 전체 검증은 미실시다.

## 검증 방법과 결과

- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:testDebugUnitTest`: 성공. 단위 테스트 44개 통과.
- Pleos API 34 에뮬레이터 `emulator-5554`, 사용자 10: APK 업데이트 설치 성공, 사용자 데이터 유지.
- `PleosLayoutTest`: 96개 측정 통과. 628dp 좁은 홈의 탐색 버튼을 별도 캡처로 확인했다.
- `PleosRuntimeTest`: 실제 플랫폼 제한 구독/해제, 홈 재생성·백그라운드 전환, 실제 3초 합성 MP4에서 컨트롤러 재생 제한과 해제 후 비자동재생 확인.
- `PeopleTest#homeOpensPeopleAndEditorPersistsAcrossRotation`: 인물 이름·캐릭터 선택의 회전 복원 통과.
- 위 Android 계측 테스트 총 5개 통과. 주행 상태 변경은 영상 뷰의 제한 입력을 전환하는 테스트이며 차량의 실제 주행 신호를 조작한 시험은 아니다.
- 결과 위치: `build/reports/pleos-compliance/`, JVM XML 결과: `app/build/test-results/testDebugUnitTest/`.

## 측정한 성능 수치

- ActivityScenario 홈 실행부터 주요 액션 확인까지: 2,148ms. 현재 PC·에뮬레이터의 1회 값이며 차량 성능 보장이 아니다.
- 별도의 `am start -W` 실행: Cold TotalTime 2,559ms / WaitTime 2,566ms. 재설치/캐시/호스트 부하에 따라 달라진다.
- Debug APK 113,693,120바이트(약 113.7MB). 공식 원본 FIG·문서·참고 이미지와 androidTest 영상은 제품 APK에 들어 있지 않다. 최종 서명된 제출 APK의 용량 검증은 별도 필요하다.

## 디자인 소스 저장

`design/pleos-official/2026-11-11/manifest.json`에 공식 문서 62개와 공식 도메인 연결 소스 200개, 총 소스 파일 247,641,397바이트의 출처·체크섬을 저장했다. 수집기는 해당 사이트의 문서와 공식 도메인 파일만 내려받는다. 앱 전용 Figma 파일이나 외부 폰트 사이트 전체를 저장한 의미가 아니다.

## 미검증 항목 및 원문 확인 필요 사항

1. **Figma 다섯 페이지의 링크가 없어 디자인 시스템·기능 명세·MSLA·UI·제출 자료를 아직 읽지 못했다. 해당 소스 저장과 Figma 기준 기능/디자인 전면 변경은 미완료다.**
2. 실제 차량과 모든 FormFactorConsole 조합, 글자 배율 변경, 전체 동적 콘텐츠, 키보드/시스템 패널 조합, 모든 화면의 프로세스 재생성, 실주행 UX 제한 전환은 미검증이다.
3. 오디오 포커스 코드는 적용했지만 통화·내비게이션·긴급음·Gleo 동시 상황의 전체 정책 조합 시험은 미실시다.
4. 출시 지역·앱 카테고리·마켓 권한 승인, 실제 Console의 840×1212 / 840×121 스크린샷 충돌과 600MB 기준 차이는 확인 필요다.
5. 최종 Figma UI가 확정되기 전이므로 스토어용 스크린샷·아이콘, 서명된 제출 APK와 다섯 번째 제출 자료의 완성 여부를 통과로 표시하지 않는다.
