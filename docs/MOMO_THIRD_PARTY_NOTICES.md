# 출처와 고지

코드 이식에 사용한 v13 Nemotron/JS reference의 라이선스 사본은 [third_party/momo-v13/LICENSE](../third_party/momo-v13/LICENSE)입니다. Copyright (c) 2026 Ahmad Waqar, MIT License 고지를 유지합니다.

모델 가중치는 Android APK나 소스에 포함하지 않습니다. 모델 파일의 정확한 다운로드 URL, byte 수, SHA256, revision은 [model-lock.json](../contracts/model-lock.json)을 확인합니다.

| 구성 | 고정 출처 | 모델/코드에 표기된 라이선스 |
|---|---|---|
| Nemotron-3-Diarization ONNX | [고정 revision 모델 카드](https://huggingface.co/onnx-community/Nemotron-3-Diarization-ONNX/blob/353b6f8ad2cac3580e982d7fbdf0a010786b0406/README.md) | Open Model, Data, and Weights License 1.1 (`openmdw-1.1`) |
| Whisper small CT2 | [Systran/faster-whisper-small](https://huggingface.co/Systran/faster-whisper-small/tree/536b0662742c02347bc0e980a01041f333bce120) | MIT |
| YAMNet | [TensorFlow Models 고정 commit](https://github.com/tensorflow/models/tree/19f287775ae950e22a9b5fb82727e72cff2e3664/research/audioset/yamnet) | Apache-2.0 |
| Silero VAD 5.1.2 | 제공 패키지의 `vendor/vad/silero-v5.1.2.onnx` | MIT |
| AndroidX/Media3 | Gradle에 고정한 Google AndroidX artifact | Apache-2.0 |
| ONNX Runtime | `onnxruntime-gpu==1.22.0` | MIT |
| faster-whisper/CTranslate2 | `1.1.1` / `4.6.0` | MIT |
| SpeechBrain ECAPA-TDNN | [고정 revision 모델](https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/tree/0f99f2d0ebe89ac095bcc5903c4dd8f72b367286), [가중치 잠금](../contracts/voice-model-lock.json) | Apache-2.0 |
| SpeechBrain / PyTorch / torchaudio | `1.0.3` / `2.5.1+cpu` / `2.5.1+cpu` | Apache-2.0 / BSD-style / BSD-2-Clause |

Nemotron 가중치를 Apache-2.0이라고 표기하지 않습니다. 사용자가 추가하는 사진·캐릭터·차량·배경·녹음의 권리는 모델 라이선스와 별도입니다. CUDA/cuDNN은 NVIDIA 배포 조건, FFmpeg는 설치한 빌드의 구성/라이선스를 따릅니다. Windows 성능 시험에 사용한 FFmpeg 8.1 full build를 앱에 번들하지 않습니다.

샘플 WAV는 코드로 생성한 톤입니다. 실제 모델 실행 검증에는 이 PC의 Windows SAPI로 생성한 한국어 합성음을 사용했으며 동의 없는 개인 대화를 fixture에 넣지 않았습니다.
음성 임베딩 smoke test는 위 SpeechBrain 모델 저장소의 공개 `example1.wav`, `example2.flac`를 빌드 임시 폴더에 다운로드합니다. 해당 파일을 APK나 소스 저장소에 번들하지 않습니다.

## 기기 내 목소리 확인 추가

- sherpa-onnx **2.0.1**의 공식 Android JNI와 Kotlin 바인딩: Apache-2.0. [공식 릴리스](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1). arm64-v8a/x86_64 네이티브 라이브러리만 포함한다.
- ModelScope/3D-Speaker **CAM++ VoxCeleb** ONNX: Apache-2.0. [원 모델 카드](https://www.modelscope.cn/models/iic/speech_campplus_sv_en_voxceleb_16k), [공식 변환 모델 배포](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models). 모델 SHA-256은 `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b`이다. 입력 16kHz, 출력 512차원이며 기존 ECAPA 192차원 모델과 구분한다.
- 함께 제공되는 ONNX Runtime은 MIT 라이선스다. 릴리스 아카이브 SHA-256은 `39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330`이다.
- 라이선스·모델 카드·출처는 APK의 `assets/voice/`에 포함했다. 모델의 한국어·실차 소음 인식률이 검증되었다는 뜻이 아니다.
- 휴대폰 공유용 사본 제작에는 서버에 설치된 FFmpeg와 기존 Asta Sans 폰트를 사용한다. FFmpeg를 새로 APK에 번들하지 않는다.
