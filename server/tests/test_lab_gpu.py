"""Execution policy, original timestamps, and GPU memory handoff without hardware."""
from contextlib import contextmanager
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import numpy as np
import pytest

from momo.audio_lab import execution, stages, worker
from momo.audio_lab.models import AudioConfig
from momo.config import settings
from momo.highlights import topics
from momo.inference import runtime


def test_cpu_stt_does_not_initialize_cuda(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "lab_device", "cpu")
    monkeypatch.setattr(runtime, "prepare_cuda", lambda *_: pytest.fail("CPU requested"))
    assert execution.stt_execution(tmp_path) == {
        "device": "cpu", "backend": "CTranslate2", "compute_type": "int8"}


@pytest.mark.parametrize("count,types", [(0, {"float16"}), (1, {"float32"})])
def test_unavailable_cuda_never_falls_back(monkeypatch, tmp_path, count, types):
    monkeypatch.setattr(settings, "lab_device", "gpu")
    monkeypatch.setattr(runtime, "prepare_cuda", lambda *_: None)
    monkeypatch.setitem(sys.modules, "ctranslate2", SimpleNamespace(
        __version__="4.8.5", get_cuda_device_count=lambda: count, get_supported_compute_types=lambda _: types))
    with pytest.raises(ValueError, match="-Device cpu"):
        execution.stt_execution(tmp_path)


def test_cuda_libraries_follow_resolved_model_directory(monkeypatch, tmp_path):
    models = tmp_path / "models"
    libraries = tmp_path / "cuda-runtime"
    libraries.mkdir()
    prepared = []
    monkeypatch.setattr(settings, "lab_device", "gpu")
    monkeypatch.setattr(settings, "cuda_library_root", None)
    monkeypatch.setattr(runtime, "prepare_cuda", prepared.append)
    monkeypatch.setitem(sys.modules, "ctranslate2", SimpleNamespace(
        __version__="4.8.5", get_cuda_device_count=lambda: 1, get_supported_compute_types=lambda _: {"float16"}))
    assert execution.stt_execution(models)["device"] == "cuda"
    assert prepared == [libraries]


def test_windows_rejects_old_crashing_runtime_before_loading_model(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "lab_device", "gpu")
    monkeypatch.setattr(execution.sys, "platform", "win32")
    monkeypatch.setattr(runtime, "prepare_cuda", lambda *_: None)
    monkeypatch.setitem(sys.modules, "ctranslate2", SimpleNamespace(
        __version__="4.6.0", get_cuda_device_count=lambda: 1,
        get_supported_compute_types=lambda _: {"float16"}))
    with pytest.raises(ValueError, match="setup-audio-lab.ps1"):
        execution.stt_execution(tmp_path)


@pytest.mark.parametrize("device,precision", [("cuda", "float16"), ("cpu", "int8")])
def test_local_stt_preserves_time_and_unloads_before_return(monkeypatch, tmp_path, device, precision):
    events = []
    chosen = {"device": device, "compute_type": precision, "backend": "CTranslate2"}
    monkeypatch.setattr(stages, "stt_execution", lambda _: chosen)

    class Whisper:
        def __init__(self, path, **kwargs):
            assert kwargs["device"] == device and kwargs["compute_type"] == precision
            self.model = SimpleNamespace(device=device, unload_model=lambda: events.append("unloaded"))

        def transcribe(self, clip, **kwargs):
            events.append("transcribed")
            segment = SimpleNamespace(start=.1, end=.9, text="hello", words=[],
                                      no_speech_prob=0, avg_logprob=0)
            return iter([segment]), None

    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=Whisper))
    result = stages.transcribe(np.zeros(16000 * 6, np.float32), "whisper-small", tmp_path,
                               {"spans": [{"startMs": 4000, "endMs": 5000}]}, None)
    assert result["utterances"][0]["startMs"] == 4100
    assert result["utterances"][0]["endMs"] == 4900
    assert result["execution"] == chosen
    assert events == ["transcribed", "unloaded"]


def test_silence_and_api_stt_skip_gpu_initialization(monkeypatch, tmp_path):
    monkeypatch.setattr(stages, "stt_execution", lambda *_: pytest.fail("No local model needed"))
    pcm = np.zeros(16000, np.float32)
    empty = stages.transcribe(pcm, "whisper-small", tmp_path, {"spans": []}, None)
    assert empty["execution"]["device"] == "not-run"
    monkeypatch.setattr(stages, "audio_api", lambda *_: {"text": "hello"})
    api = stages.transcribe(pcm, "openai:gpt-transcribe", tmp_path,
                            {"spans": [{"startMs": 0, "endMs": 1000}]}, object())
    assert api["execution"]["device"] == "api"


@pytest.mark.parametrize("listing,expected", [
    ("Available devices:\n  Vulkan0: NVIDIA GeForce RTX 3070 (8232 MiB, 7450 MiB free)\n", "Vulkan0"),
    ("Vulkan0: Integrated GPU\nCUDA0: NVIDIA RTX (8192 MiB, 4000 MiB free)\n", "CUDA0"),
])
def test_qwen_selects_an_available_gpu(monkeypatch, listing, expected):
    monkeypatch.setattr(topics.subprocess, "run", lambda *a, **k: SimpleNamespace(stdout=listing, stderr=""))
    result = topics.select_gpu(Path("llama-server.exe"))
    assert result["device_id"] == expected and result["device"] == "gpu"
    assert "MiB" not in result["name"]


def test_qwen_cpu_only_runtime_is_rejected_in_gpu_mode(monkeypatch):
    monkeypatch.setattr(topics.subprocess, "run", lambda *a, **k: SimpleNamespace(
        stdout="Available devices:\n", stderr=""))
    with pytest.raises(ValueError, match="GPU"):
        topics.select_gpu(Path("llama-server.exe"))


@pytest.mark.parametrize("device", ["gpu", "cpu"])
def test_topic_worker_uses_selected_device_and_reports_actual_runtime(monkeypatch, tmp_path, device):
    monkeypatch.setattr(settings, "lab_device", device)
    observed = {"device": device, "backend": "Vulkan" if device == "gpu" else "llama.cpp"}

    @contextmanager
    def local(*args, **kwargs):
        assert kwargs["device"] == device
        kwargs["on_runtime"](observed)
        yield "http://test/model"

    monkeypatch.setattr(worker, "local_model", local)
    monkeypatch.setattr(worker, "http_json", lambda *a, **k: {"choices": [{
        "finish_reason": "stop", "message": {"content": json.dumps({"topics": [
            {"start_id": 1, "title": "story", "reason": "context"}]})}}]})
    _, metadata = worker.partition([{"id": 1, "startMs": 0, "endMs": 1000, "text": "hello"}],
        "qwen-local", AudioConfig(), tmp_path, None, tmp_path)
    assert metadata["execution"] == observed
