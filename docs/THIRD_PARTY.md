# 출처와 라이선스

현재 앱에 포함하는 모델·네이티브 라이브러리·폰트의 원문 고지는 APK 에셋과 함께 보관합니다.

| 구성 | 고지 위치 |
| --- | --- |
| YAMNet / LiteRT, Qwen3, llama.cpp | [모델·런타임 NOTICE](../app/src/main/assets/third_party/NOTICE.txt), 같은 폴더의 Apache-2.0·MIT 원문 |
| 기기 음성 분석 모델 / ONNX Runtime / sherpa-onnx | [음성 모델 고지와 모델 카드](../app/src/main/assets/voice/) |
| Asta Sans / Jua | [폰트와 OFL 원문](../app/src/main/assets/tiki/fonts/) |
| 이식한 MOMO v13 코드 | [MIT 라이선스](../third_party/momo-v13/LICENSE) |
| 기기 테스트용 음원 | [테스트 에셋 출처](../app/src/androidTest/assets/NOTICE.txt) |

MOMO v13 원본의 기록된 커밋은 `3d3137e07cb83eecfdea07e271bbbae8cf352ce8`입니다. 원본의 움직임 계산과 에셋 배치 방식은 Android의 `momo/render/MotionEngine.kt`, `SceneRenderer.kt` 등에 이식했습니다. 원본 MIT 저작권 고지는 유지합니다.

사용자가 제공한 캐릭터·배경·차량 이미지의 권리는 위 라이브러리 라이선스와 별개입니다. 공개 저장소라는 이유만으로 모든 에셋의 재배포 권한을 부여하지 않습니다.

현재 앱이 사용하지 않는 PC 서버, 원본 JS/TS 참고 복사본, 과거 모델 배포 명세와 실험 문서는 저장소 밖으로 보관했습니다. 앱 런타임 에셋·폰트·라이선스 원문은 제거하지 않았습니다.
