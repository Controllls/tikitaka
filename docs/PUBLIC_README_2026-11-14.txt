티키타카 공개 README 정리 및 저장소 공개

대상: https://github.com/Controllls/tikitaka
사용자 요청: 공모전 레퍼런스처럼 README 정리, Figma 자료 활용, Public 전환.

변경
- 앱 소개 → 기능 → 사용 흐름 → 디자인 → 처리 구조 → 실행법 → 검증 현황 순서로 재작성.
- 기존 README 전체를 docs/README_HISTORY_2026-11-14.md에 보존하고 상대 링크 조정.
- 보존된 Figma 원본 PNG 5장을 편집 없이 복사하고 노드·출처·SHA256 기록.
- 기존 사용자 앱 변경을 별도 제출용 clone에 복사해 문서와 소스의 버전 차이를 줄임.
- 원래 작업 폴더의 사용자 변경·Git 인덱스·녹음·영상·분석 자료는 유지.
- 서명키·비밀번호·신청서 초안·개인 데이터 저장 폴더는 공개 대상에서 제외.

Figma 확인 범위
파일: Y5hqYUqZsj6gQu1QyixBYv
현재 연결 계정으로 create_session/get_metadata 호출 시 편집 권한 없음 응답.
따라서 최신 Figma를 읽었다고 보고하지 않으며 2026-11-12 보존본을 사용했다.
README 이미지가 디자인 시안이고 실기 화면이 아니라는 설명을 표시했다.
홈·캐릭터·보관함 이미지를 직접 확인했으며 출처는 docs/images/readme/sources.json.

검증
- README 상대 문서·이미지 링크 23개: 누락 0.
- GitHub GFM 렌더 API: 제목 정상, 이미지 5개, 표 7개, 접기 영역 1개.
- 렌더 결과를 로컬 브라우저에서 확인: 제목·홈 이미지·기능 표 정상 표시.
- 현재 공개 대상 텍스트 및 로컬 Git 이력 526개 텍스트 blob에서
  선택된 private-key/GitHub-token/API-key 패턴 검출 없음.
  이 검사는 모든 비밀정보·개인정보·라이선스를 포괄하는 보증이 아님.
- Git diff --check 통과. 비밀키 파일·build·server/data가 staged 목록에 없음을 확인.
- 최초 업로드 eefd4730bf6d3f7ddbb947b76e568564edc856f9.
- GitHub API에서 Public 전환 완료, 비로그인 조회로 visibility=public 확인.
- 비로그인 raw 다운로드로 README 및 이미지 5개 접근 성공, 로컬 SHA256과 모두 일치.

이번 작업에서 앱 기능은 수정하지 않았다. 기존 변경의 공개 소스 스냅샷을 업로드했다.
앱 검증 수치는 해당 날짜별 기존 기록을 인용했으며 이번에 앱 테스트를 재실행하지 않았다.
최신 Figma 대조, 공개용 시연 영상, APK Release 업로드, 접수 메일 발송은 미완료.
