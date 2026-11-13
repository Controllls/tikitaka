# 배포 구성과 운영 경계

로컬 시연은 앱과 별개로 `server/backend.ps1 start/status/stop`을 사용합니다. [백엔드 독립 실행 안내](../server/README.md)의 구성은 PC 내부에서 사용하는 개발 서버입니다. 아래 Compose는 인터넷 서비스 배포를 위한 별도 구성입니다.

실행한 환경은 Windows 로컬 SQLite + 별도 worker입니다. Docker가 설치되어 있지 않아 아래 PostgreSQL/Redis/Celery Compose 구성과 S3는 이 PC에서 통합 실행하지 않았습니다. 배포 준비 코드이며 공개 운영 완료 보고서가 아닙니다.

## Compose

`infra/.env.example`을 `infra/.env`로 복사하고 `POSTGRES_PASSWORD`, 32자 이상의 무작위 `MOMO_JWT_SECRET`, 모델이 준비된 절대 경로 `MOMO_MODELS_DIR`를 채웁니다. DB 비밀번호는 URL에 안전한 무작위 문자로 만들거나 URL 인코딩해야 합니다. `.env`와 실제 키는 체크인하지 않습니다.

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml build
docker compose --env-file infra/.env -f infra/compose.yaml up -d
```

앱 계정을 미리 만들 필요가 없습니다. 앱이 `/v1/auth/installation`으로 설치별 UUID와 무작위 인증키를 등록하고 토큰을 자동 관리합니다. 기존 `/v1/auth/login`과 `create-user` CLI는 과거 계정 관리 호환용입니다. API가 Alembic migration 후 시작하면 worker/dispatcher/maintenance가 시작됩니다. `0002_installations`는 기존 계정·작업을 보존하며 설치 인증 테이블을 추가합니다. GPU worker에는 NVIDIA Container Toolkit과 CUDA 지원 드라이버가 필요합니다. 초기 GPU당 작업 1개, Celery solo pool입니다.

`0003_highlight_artifacts`는 기존 작업을 보존하고 자동 하이라이트 파일 목록을 추가합니다. 같은 worker가 영상 분석과 하이라이트 분석을 직렬 처리합니다. 모델 볼륨에 `highlights/yamnet.tflite`, `yamnet_labels.txt`, `topic.gguf`, `model-lock.json`과 llama.cpp 런타임이 추가로 필요합니다. [Windows 모델 준비](../server/README.md#자동-하이라이트). Linux 배포에는 b12110의 Linux용 `llama-server`와 의존 라이브러리를 `highlights/runtime/` 또는 PATH에 준비해야 합니다. Windows EXE를 Linux 컨테이너에서 실행할 수 없습니다. 모델·런타임은 작업 중 자동 다운로드하지 않습니다.

주제 모델은 기본 CPU(`MOMO_HIGHLIGHT_TOPIC_DEVICE=cpu`)로 실행해 기존 음성 모델과 GPU 메모리 경합을 줄입니다. GPU 런타임을 준비하고 메모리 여유를 검증한 환경에서는 `auto`로 변경할 수 있습니다. 하이라이트 입력은 기본 2시간(`MOMO_HIGHLIGHT_MAX_SECONDS=7200`)·80MB 이하이며 긴 입력도 작업 제한 30분 안에 처리할 수 있는 하드웨어가 필요합니다.

API는 모델을 로드하지 않습니다. `/health/live`는 프로세스 생존, `/health/ready`는 DB·브로커·파일 저장·준비된 worker를 확인합니다. 모델 로드/해시/provider 검증 실패 시 분석 접수는 503입니다. fixture 결과를 production에 반환하지 않습니다.

사용자 목소리 매칭에는 추가 ECAPA 모델이 필요합니다. `uv sync --frozen --extra inference` 후 서버 디렉터리에서 `python -m momo.prepare_voice_model`을 실행합니다. 전체 `prepare_models`에도 이 단계가 포함됩니다. worker의 준비 상태는 기존 모델 잠금과 ECAPA 모델 ID를 함께 사용하므로 업데이트 시 API·worker를 같이 재시작합니다. 상세 내용은 [사용자 관리와 목소리 매칭](people-voice-matching.md)을 참고하세요.

기본 이미지 태그와 digest는 `infra/images.lock.json`, Python 패키지는 `server/uv.lock`에 고정되어 있습니다. Dockerfile의 배포판 FFmpeg 패키지는 apt 저장소에서 설치하므로 byte-identical 컨테이너 빌드를 보장하지 않습니다.

## 네트워크와 저장소

외부 매핑은 `127.0.0.1:8765` 하나입니다. 운영 시 인증서를 설치한 reverse proxy의 HTTPS로 연결하고 `infra/nginx-location.conf`를 해당 TLS server 블록에 포함합니다. PostgreSQL·Redis는 내부 네트워크에서만 사용합니다. TLS 인증서·도메인·공개 배포는 생성하지 않았습니다.

기본 개발/단일 노드 저장소는 private named volume입니다. S3 구성은 다음 환경변수를 모든 서비스에 동일하게 주고, workload identity 또는 secret mount로 자격을 제공합니다.

```text
MOMO_STORAGE_BACKEND=s3
MOMO_S3_BUCKET=<private bucket>
MOMO_S3_ENDPOINT_URL=<optional S3-compatible endpoint>
```

필요한 권한은 지정 bucket의 `sources/`, `results/`, `tracks/`, `highlights/`, `_health/` prefix 읽기/쓰기/삭제/목록입니다. public ACL/공개 URL/클라이언트용 S3 키를 만들지 않습니다. S3 캐시와 `_work` 임시 PCM도 private 공유 저장소에서 관리합니다. 실제 S3 호환성과 IAM 정책은 배포 환경에서 검증해야 합니다.

## 상태와 보관기간

- PostgreSQL job + outbox를 같은 트랜잭션으로 기록합니다. Redis 전달은 중복 가능하고 유효 lease를 얻은 worker만 게시합니다.
- 설치별 소유자 running 1개/queued 3개. 작업 deadline 30분, 대기 24시간. 취소/삭제는 lease 검사와 watchdog으로 중단하며 supervisor가 worker를 재시작합니다. 자동 등록은 IP당 분당 10회로 제한합니다. 공개 서비스의 총 사용량 제한과 앱/차량 검증은 별도 운영 설계가 필요합니다.
- 원본은 terminal 후 24시간, result/tracks 및 하이라이트 M4A·SRT·전사 JSON은 30일. PCM은 작업 종료 시 조기 삭제합니다. 활성 lease의 파일은 청소에서 제외합니다.
- maintenance는 60초마다 만료·lease 회수·유실 outbox 재전달을 처리합니다. 1시간 이상 된 미참조 객체/중단 업로드/작업 임시 파일을 정리합니다.
- 삭제 요청은 즉시 API 접근을 막습니다. 실행 중 파일은 안전한 종료/lease 회수 후 삭제합니다. API 삭제를 물리 매체 즉시 소거라고 표현하지 않습니다.
- 이 개발 구성에는 외부 백업이 설정되지 않았습니다. 운영 백업을 추가하면 snapshot/object version 보관기간과 삭제 절차를 별도로 정해야 합니다. 원본 오디오 TTL보다 장기 백업을 자동 가정하지 않습니다.

로그에는 작업 ID, 단계 시간, 상태를 남깁니다. 토큰·비밀번호·원본 PCM·전체 전사문을 로그에 쓰지 않습니다. 동시 사용자 부하, PostgreSQL 트랜잭션 경합, Redis 재접속, S3 장애, TLS 및 백업 정책은 운영 전 검증 항목입니다.
