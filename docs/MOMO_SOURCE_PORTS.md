# MOMO 원본과 HD_project 대응

입력은 사용자 제공 `MOMO_Android_Server_개발명세서.txt`와 `MOMO-Drive-Developer-v13-2026-11-05`입니다. 원본 제공 디렉터리는 변경하지 않았습니다. 원본에 기록된 source commit은 `3d3137e07cb83eecfdea07e271bbbae8cf352ce8`입니다. 참고한 JS/TS와 MIT 고지는 `third_party/momo-v13`에 보존합니다.

| v13 기능 | HD_project |
|---|---|
| audio-features / Nemotron featureExtractor·fft·mel | `server/momo/inference/features.py` |
| sound-worker YAMNet / Silero VAD | `server/momo/inference/acoustic.py` |
| diarizer / modelConfig / tensor / speakerCache | `server/momo/inference/diarization.py` |
| laughter vocalOnly/recoverLaughter | `server/momo/inference/laughter.py` |
| result 조립 | `server/momo/inference/fusion.py` |
| 웹 Whisper | `server/momo/inference/asr.py`: CT2 Whisper small 한국어 전사 |
| audio-events motion | `app/.../momo/render/MotionEngine.kt` |
| asset-view 차량·창문·좌석 마스크 | `app/.../momo/render/SceneRenderer.kt` |
| 브라우저 영상 저장 | Media3 Transformer + CanvasOverlay, 기기에서 H.264/AAC 생성 |
| 웹 캐시·Worker | Room/WorkManager + API/outbox/worker |
| 웹 캐릭터·좌석·자막·배경 설정 UI | 제외. 제공된 리소스와 고정 배경·배치 사용. 별도 앱 사용자 관리에서 인물별 캐릭터 선택·등록 목소리 연결 추가 |

추가 개발: `server/momo/inference/voice.py`의 ECAPA 임베딩 추출과 `app/.../momo/people`의 인물 저장·매칭은 v13 화자 캐시와 별개입니다. 세션 화자 ID를 영구 인물 ID로 사용하지 않습니다.

Android 코드는 기존 `app/src/main/java/com/example/hd_project/momo` 아래에 있으며 기존 녹음 목록에 연결했습니다. 서버와 모델 준비·운영 스크립트도 이 저장소 안에 있습니다. 별도 앱 프로젝트를 실행할 필요가 없습니다.

`companion/server.py`의 pyannote, archive/legacy, 브라우저 WASM 실행은 활성 경로에 포함하지 않았습니다. Whisper 엔진이 달라 원본 웹과 전사 문장이 완전히 동일하다고 주장하지 않습니다. 원본 JS 수치 reference는 `tests/fixtures`와 Android 단위 테스트에서 사용합니다. 실제 대화의 DER/CER 품질 평가는 별도입니다.

선택한 원본 오디오의 전체 시간축을 유지합니다. 자동 하이라이트 편집, LLM 대본 생성, 생성형 영상 API, 음성 복제, 음소 립싱크는 구현하지 않았습니다. 기존 기본 도형 캐릭터 코드는 제거하고 사용자 제공 PNG manifest 파츠만 사용합니다.
