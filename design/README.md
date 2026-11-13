# 디자인 소스 관리

- `pleos-official/2026-11-11/`: Pleos 공식 문서 62개와 문서에 연결된 공식 도메인 소스 200개. 원본 FIG, 이미지 등이며 출처·다운로드 시각·크기·SHA-256은 `manifest.json`에 기록한다. 약 248MB이며 APK에 포함하지 않는다.
- `tools/sync-pleos-reference.py`: 공식 자료 수집을 재현하는 도구. Python, httpx, beautifulsoup4가 필요하다. 공식 도메인 외의 링크는 다운로드 대상으로 간주하지 않는다.
- 앱 런타임에서 사용하는 실제 그림은 `app/src/main/res/` 또는 `app/src/main/assets/`에 보관하고 사용 위치와 출처를 기록한다. 참고용 화면 전체 이미지를 앱 화면으로 사용하지 않는다.
- `figma/2026-11-12/`: [사용자 Figma](https://www.figma.com/design/Y5hqYUqZsj6gQu1QyixBYv/Untitled?node-id=144-422)의 디자인 시스템·기능명세·M.S/I.A·UI·제출 자료 5개 페이지 메타데이터, 전체 기능명세, 26개 디자인 컨텍스트와 연결 에셋 213개. M-01 녹음 동의 가로·세로 시안을 포함한다. 전체 편집 가능한 `.fig` 원본을 내보낸 것은 아니다.
- `app/src/main/assets/tiki/`: 런타임에 필요한 그림을 SHA-256 기준으로 중복 제거한 51개 이미지, 화면별 매핑 `images.json`, Asta Sans/Jua 폰트와 OFL 라이선스. SVG는 원본을 보관하고 Chromium으로 투명 PNG로 렌더링했다. 화면 전체 이미지를 UI로 사용하지 않는다.
- `app/src/main/assets/tiki/video/`: `server/assets/designer-defaults`에서 가져온 디자이너 제공 캐릭터 8종·차량 레이어·도시 배경. UI용 3D 그림과 영상용 2D 스프라이트를 구분한다.
- 재현: `tools/download-figma-assets.py`, `tools/prepare-tikitaka-assets.py`. 임시 Figma URL은 런타임에서 사용하지 않는다. 원본 URL은 만료될 수 있으므로 내려받은 파일과 체크섬을 유지한다.

공식 자료와 앱 전용 Figma는 별도 근거이다. 구현 범위와 미검증 항목은 [Figma 적용 기록](../docs/FIGMA_IMPLEMENTATION_2026-11-12.md)을 확인한다.
