# Pleos Connect AI 개발 규칙서

버전: 1.0  
기준일: 2026-11-11  
적용 대상: Pleos Connect 차량용 Android 앱을 구현하거나 수정하는 AI 개발 에이전트

앱 화면 규격과 반응형 UI 구현을 중심으로, 주행 제한·상태 복원·성능·개발 환경·제출 규격을 정의한다. SDK별 전체 API 명세나 앱 카테고리별 심사 기준은 해당 공식 문서를 추가로 확인한다.

## 1 적용 방법과 규칙 구분

이 파일을 프로젝트의 `docs/PLEOS_CONNECT_AI_DEVELOPMENT_RULES.md`에 두고 개발 요청에 함께 첨부한다. 프로젝트가 `AGENTS.md` 또는 `CLAUDE.md`를 사용한다면 다음 문장을 추가할 수 있다.

> Pleos Connect 앱을 구현하거나 변경하기 전에 docs/PLEOS_CONNECT_AI_DEVELOPMENT_RULES.md를 읽고 적용하라. 변경한 기능에 해당하는 검증 결과와 미검증 항목을 보고하라.

- **공식 요구**: 연결된 Pleos 공식 문서에서 요구하는 동작이다.
- **공식 수치**: 원문에 기재된 규격이나 기준값이다. 단위와 적용 대상을 함께 유지한다.
- **구현 규칙**: 이 규칙서에서 정한 개발·검증 방법이다. 공식 플랫폼 제한으로 인용하지 않는다.
- **확인 필요**: 공식 문서 사이의 차이나 대상 환경에 따라 달라지는 항목이다. 추측으로 확정하지 않는다.

개발 시작 시 대상 화면 유형, 앱 카테고리, 서비스 지역, SDK 버전을 기록한다. 값이 정해지지 않았다면 가정을 명시하고, 미정인 정책에 의존하지 않는 작업부터 진행한다. SDK와 정책을 변경하는 작업에서는 기준일 이후 공식 문서 변경 여부를 확인한다.

## 2 앱 화면 규격

### 2.1 기준 크기

**공식 수치. 모든 값은 앱 창의 가로 × 세로이며 단위는 dp이다.** 전체 디스플레이 크기나 마켓 스크린샷의 px 규격과 혼동하지 않는다.

| 화면 유형 | 공식 표의 식별명 | Landscape 앱 창 | Portrait 앱 창 |
| --- | --- | ---: | ---: |
| Standard 17인치 기본형 | Connect-S-17 Default | 1700 × 1184 dp | 840 × 1184 dp |
| Standard 14.6인치 | Connect-S-14.6 | 1360 × 948 dp | 672 × 948 dp |
| Standard 12.9인치 | Connect-L-12.9 | 1274 × 855 dp | 628 × 855 dp |
| Wide 기본형 | Connect-W-Standard | 1640 × 810 dp | 800 × 810 dp |
| Wide 확장형 | Connect-W-Extended | 1640 × 1400 dp | 800 × 1400 dp |
| Waterfall | Connect-WF | 1256 × 800 dp | 648 × 800 dp |

12.9인치는 그림에서 `Connect-L-12.9`로 표기되며, 본문에서는 기존 Connect-L을 Connect-S로 재분류했다고 설명한다. 같은 12.9인치 규격의 명칭 차이로 관리한다.

근거: [Display Layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout#화면-유형)의 화면 유형 표. 수치는 본문에 삽입된 이미지의 `Landscape (dp)`와 `Portrait (dp)` 열을 기준으로 한다.

### 2.2 크기를 코드에 적용하는 규칙

아래는 **구현 규칙**이다.

- **SIZE-01**: 위 표는 설계·검증 프리셋이다. 루트 화면을 `1700 × 1184` 같은 상수 크기로 고정하지 않는다.
- **SIZE-02**: 실행 중 앱에 실제로 할당된 창 크기로 레이아웃을 결정한다. 전체 디스플레이 크기를 앱의 사용 가능 크기로 가정하지 않는다.
- **SIZE-03**: dp 값을 px로 그대로 취급하지 않는다. px가 필요한 코드에서는 런타임 밀도를 사용한다. 웹 기반 UI는 CSS px와 Android dp의 대응을 실제 WebView 환경에서 검증한다.
- **SIZE-04**: 가로형·세로형을 별도로 설계하고 창 크기가 바뀌면 배치도 다시 계산한다. 방향 이름이나 인치 수만으로 모든 레이아웃을 결정하지 않는다.
- **SIZE-05**: 분기점과 열 수는 콘텐츠가 잘리지 않는 조건으로 정한다. 임의의 분기점을 Pleos 공식 수치라고 설명하지 않는다.
- **SIZE-06**: 17인치 하나만 확인하고 전체 화면 유형 지원을 완료했다고 보고하지 않는다. 실제 지원 범위와 제외한 유형을 명시한다.

## 3 창 배치와 시스템 영역

**공식 요구 및 동작**

- **WIN-01**: Driving view는 플랫폼이 관리하는 주행 정보 영역이다. 앱이 임의로 덮거나 종료하지 않는다.
- **WIN-02**: 일반적으로 가로·세로 두 배치를 지원한다. 가로 전용 게임처럼 한 방향만 가능한 경우에는 해당 앱의 예외 적용 범위를 확인한다.
- **WIN-03**: 앱 영역에는 최대 두 앱이 표시된다. Standard는 두 앱의 1:1 또는 한 앱의 2:0 배치를, Wide는 2:1 또는 1:2 배치를 설명한다.
- **WIN-04**: 상태 표시줄, GNB 패널, 키보드는 앱보다 위에 표시될 수 있다. 시스템 영역과 입력 UI를 고려해 중요한 콘텐츠와 조작부를 배치한다.

근거: [Display Layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout), [Window Policy](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window), [앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model).

**구현 규칙**

- 앱 창에 이미 반영된 시스템 영역을 다시 고정값으로 빼지 않는다. 실제 창 경계와 전달되는 인셋을 확인해 중복 여백을 방지한다.
- `AndroidManifest.xml`의 `screenOrientation`과 실제 에뮬레이터 실행 결과를 함께 확인한다. 가로 고정 설정을 모든 앱에 일괄 적용하지 않는다.
- 창 크기 변경 때 기존 화면을 통째로 확대·축소해 맞추는 방식 대신 콘텐츠를 재배치한다.

**확인 필요**: 일반 앱 구조 문서는 기존 앱 방향에 따른 신규 실행을 설명하고, Window Policy는 Wide의 신규 앱이 Primary에서 Landscape로 실행된다고 구분한다. 대상 폼 팩터의 세부 정책과 실제 동작을 확인하고 공통 가정 하나로 처리하지 않는다.

## 4 상태 복원과 화면 호환성

**공식 요구**

- **STATE-01**: 화면에서 사라졌다는 이유로 앱이 종료됐다고 가정하지 않는다. 포그라운드 복귀 시 사용 상태와 화면 구성을 복원한다.
- **STATE-02**: 크기·방향 전환으로 사용 중인 작업과 기능 접근성이 사라지지 않게 한다. 좁은 화면에서도 같은 작업을 완료할 수 있어야 한다.
- **STATE-03**: 이미지 왜곡, 터치 위치 불일치, UI 잘림과 호환성 모드 실행을 방지한다. 가능한 앱 영역을 사용하며, 미세한 비율 차이에 대한 한 방향 레터박스는 원문에서 예외 가능성을 둔다.
- **STATE-04**: 백그라운드에서 불필요한 화면 갱신·애니메이션·폴링을 정리한다. 지속할 오디오·통신·다운로드는 중단과 재개 조건을 정의한다.

근거: [앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), [앱 기능 및 성능 안정성](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability).

**구현 규칙**: 목록 선택, 입력 내용, 탐색 위치처럼 복원이 필요한 상태를 먼저 식별한다. Landscape에서는 정보를 더 넓게 배치할 수 있지만 Portrait에서 기존 기능을 제거하는 근거로 사용하지 않는다.

## 5 주행 제한과 조작

**공식 요구**

- **DRIVE-01**: `CarUxRestrictions`를 기준으로 제한 상태를 반영한다. 적용 범위는 서비스 지역과 앱 카테고리까지 확인한다.
- **DRIVE-02**: 주행 제한 상태에서 영상 재생을 시작하거나 재개하지 않는다. 오디오는 별도 오디오 포커스 정책에 따른다.
- **DRIVE-03**: 운전자의 시선을 오래 붙잡는 장식, 반복 애니메이션과 불필요한 화면 전환을 줄인다.
- **DRIVE-04**: 주요 기능을 쉽게 찾게 배치하고 조작 단계를 줄인다. 검색·로그인은 최근 항목, 프리셋과 음성 입력을 우선 검토한다.
- **DRIVE-05**: 터치 영역, 버튼 간격과 대비는 해당 디자인 가이드를 따른다. 일반 모바일 관행을 Pleos의 공식 최소 수치로 대체하지 않는다.

근거: [주행 안전을 고려한 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline). 이 페이지에 연결된 지역별 정책, 앱 카테고리, 주행 규제 개발 가이드와 오디오 포커스 가이드를 구현 시 함께 확인한다.

**구현 규칙**: 제한 상태가 실행 중 변경되는 경우와 백그라운드에서 복귀하는 경우를 검증한다. 화면의 버튼 표시뿐 아니라 제한 기능의 실제 실행 경로도 함께 점검한다.

## 6 개발 환경과 성능

### 6.1 개발 환경

**공식 수치**

| 항목 | 문서 기준 |
| --- | --- |
| 제품 앱 minSdk | Android API 28 이상 |
| Gradle | 8.0 이상 |
| 개발 언어 | Java 또는 Kotlin |
| 설치 가이드의 시스템 이미지 | Android API 34 |
| 설치 가이드의 AVD 설정 | RAM 4 GB, Cold boot |

근거: [Pleos Connect Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator).

**구현 규칙**: 시스템 이미지 API 34를 앱의 `targetSdk` 또는 `compileSdk` 확정값으로 추론하지 않는다. Gradle, Android Gradle Plugin, JDK, SDK 의존성은 프로젝트의 호환 조합을 별도로 확인한다. 설치 예시의 에뮬레이터 버전을 영구적인 최신 버전으로 고정하지 않는다.

### 6.2 반응 시간과 권한

**공식 수치 및 요구**

| 항목 | 기준 |
| --- | --- |
| 터치 피드백 | 입력 후 효과 애니메이션 시작까지 0.25초 이내 |
| 앱 실행 | 10초 이내 |
| 콘텐츠 로드 | 10초 이내 |
| 버튼 반응 | 화면·기능·상태 변화가 2초 이내 |
| 지연 안내 | 2초 이내 완료하지 못하면 진행 중임을 UI로 표시 |

- **PERF-01**: 심각한 끊김과 앱의 자원 과점유로 차량 시스템 성능을 저하시키지 않는다.
- **API-01**: Vehicle SDK의 필요한 퍼미션을 확인하고, Pleos App Market과 협의·승인된 퍼미션만 선언한다.

근거: [앱 기능 및 성능 안정성](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability).

**구현 규칙**: 측정하지 않은 성능을 통과로 기록하지 않는다. SDK 함수명·인자·콜백·권한은 실제 적용 버전의 API 문서나 의존성에서 확인한다. 존재 여부를 확인하지 않은 API를 생성해 사용하지 않는다.

## 7 마켓 제출 규격

**공식 수치**. 아래 스크린샷은 스토어 등록 이미지이며 2장의 앱 창 dp 규격과 적용 대상이 다르다.

| 항목 | 등록정보 가이드 기준 |
| --- | --- |
| 앱 이름 | 영문 기준 30자 이내 |
| 앱 아이콘 | 512 × 512 px, PNG 또는 JPEG, 최대 1 MB |
| 스크린샷 수 | 2장 이상 8장 이하 |
| 스크린샷 용량과 형식 | 장당 최대 8 MB, PNG 또는 JPEG |
| 스크린샷 해상도 | 2560 × 1380 / 1700 × 1212 / 840 × 1212 px |
| 패키지 | APK, 다운로드되는 압축 파일 크기 600 MB 미만 |

근거: [Pleos App Market 등록정보](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-app-registration-info).

**확인 필요**: [앱 심사 제출](https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide)에는 마지막 스크린샷 크기가 `840 × 121 px`, APK는 최대 `600 MB`로 기재되어 등록정보 가이드와 차이가 있다. 제출 시 Console의 실제 검증 조건 또는 운영 측 안내로 확인한다. 이 문서에서는 이미지 생성에 `840 × 1212 px`를 임시 기준으로 사용하고 APK는 `600 MB 미만`으로 준비하되, 이를 공식 정정으로 표현하지 않는다.

## 8 검증 체크리스트

다음은 **구현 규칙으로 정한 완료 기준**이다. 체크박스는 실제 검증 후 표시한다.

### 8.1 화면 유형별 검증

| 화면 유형 | Landscape 확인 | Portrait 확인 |
| --- | --- | --- |
| Standard 17 | 미검증 | 미검증 |
| Standard 14.6 | 미검증 | 미검증 |
| Standard 12.9 | 미검증 | 미검증 |
| Wide 기본형 | 미검증 | 미검증 |
| Wide 확장형 | 미검증 | 미검증 |
| Waterfall | 미검증 | 미검증 |

지원 대상의 각 조합에서 다음 항목을 확인한다. 제외하는 조합은 사유를 남긴다.

- [ ] 텍스트·이미지·버튼에 잘림과 겹침이 없다.
- [ ] 터치 영역이 화면상의 조작부와 일치한다.
- [ ] 키보드와 시스템 패널 표시 후 필요한 조작을 계속할 수 있다.
- [ ] 창 크기 전환 뒤에도 작업 상태와 기능 접근성이 유지된다.
- [ ] 백그라운드 복귀와 앱 재생성 뒤 필요한 데이터가 복원된다.
- [ ] 제한 상태 진입·해제와 복귀 시 기능 동작이 해당 정책과 일치한다.
- [ ] 실제 성능 측정값과 실행 환경을 기록했다.
- [ ] 사용하는 Vehicle SDK API와 승인된 권한이 일치한다.

공식 테스트 도구 절차: [폼 팩터 테스트 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide). FormFactorConsole에서 프리셋·방향과 실제 dp·DPI·px 값을 확인한다. 가상 디스플레이는 별도 에뮬레이터 창에서 사용하며 제거 시 해당 앱이 강제 종료되므로 테스트 데이터를 먼저 저장한다.

### 8.2 AI 개발 완료 보고 형식

아래 항목을 개발 완료 응답이나 프로젝트 검증 기록에 작성한다.

- 변경한 화면과 기능:
- 적용한 규칙 ID:
- 사용한 SDK 및 의존성 버전:
- 테스트한 화면 유형과 방향:
- 검증 방법과 결과:
- 측정한 성능 수치:
- 미검증 항목 및 원문 확인이 필요한 사항:

지원 범위를 검증하지 못했으면 해당 범위는 미검증으로 남긴다. 코드 구현 완료와 차량 환경 검증 완료를 구분해 보고한다.

## 9 AI에게 전달할 개발 요청 예시

> 첨부한 PLEOS_CONNECT_AI_DEVELOPMENT_RULES.md를 읽고 Pleos Connect 앱을 개발해줘. 2장의 앱 창 크기와 dp 단위를 적용하고, 지원 대상별 가로·세로 레이아웃을 구성해줘. 실제 할당된 창 크기로 배치하며 주행 제한과 상태 복원도 반영해줘. 공식 요구와 구현 선택을 구분하고, 완료 시 8장의 형식으로 검증 결과와 미검증 항목을 알려줘.
