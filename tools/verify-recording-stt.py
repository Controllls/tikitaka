"""Opt-in Pleos microphone integration audit, using the emulator's local gRPC audio input.

Runs RecordingSttAuditTest against the installed app. Audio is a bundled synthetic
Korean fixture; no user audio is uploaded. Emulator credentials are never printed.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import threading
import time
import wave

import grpc
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory, empty_pb2

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'
ADB = str(SDK / 'platform-tools/adb.exe')
PACKAGE = 'com.example.hd_project'


def adb(*args, timeout=30):
    return subprocess.run([ADB, '-s', 'emulator-5554', *args], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout, check=True).stdout


def app_read(path):
    return adb('exec-out', 'run-as', PACKAGE, '--user', '10', 'cat', path)


def messages():
    # Wire schema from the locally installed SDK/emulator/lib/emulator_controller.proto.
    schema = descriptor_pb2.FileDescriptorProto(name='audit_audio.proto', package='audit', syntax='proto3')
    definitions = {
        'AudioFormat': [('samplingRate', 1, 4, None), ('channels', 2, 5, None), ('format', 3, 5, None), ('mode', 4, 5, None)],
        'AudioPacket': [('format', 1, 11, '.audit.AudioFormat'), ('timestamp', 2, 4, None), ('audio', 3, 12, None)],
        'MicrophoneState': [('realAudioEnabled', 1, 8, None)],
    }
    for name, fields in definitions.items():
        message = schema.message_type.add(name=name)
        for field_name, number, kind, target in fields:
            field = message.field.add(name=field_name, number=number, type=kind, label=1)
            if target:
                field.type_name = target
    pool = descriptor_pool.DescriptorPool()
    pool.Add(schema)
    return {name: message_factory.GetMessageClass(pool.FindMessageTypeByName('audit.'+name)) for name in definitions}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['sequential', 'direct', 'concurrent', 'stt-first', 'file-events'], default='sequential')
    parser.add_argument('--id', required=True)
    parser.add_argument('--source')
    parser.add_argument('--no-inject', action='store_true', help='Audit microphone ownership only; do not inject speech.')
    options = parser.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', options.id):
        parser.error('--id must contain only letters, digits, underscores or hyphens')
    if options.mode == 'file-events' and not options.source:
        parser.error('--source is required for file-events')
    out = ROOT / 'build/reports/recording-stt-audit' / options.id
    out.mkdir(parents=True, exist_ok=False)
    device_root = f'cache/recording-stt-audit/{options.id}'
    source = ROOT / 'app/src/androidTest/assets/momo-synthetic-korean.m4a'
    speech = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(source), '-f', 's16le', '-ar', '16000', '-ac', '1', '-'],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
    discovery = adb('emu', 'avd', 'discoverypath').decode().splitlines()[0].strip()
    config = dict(line.strip().split('=', 1) for line in Path(discovery).read_text().splitlines() if '=' in line)
    token = config.get('grpc.token', '')
    if not token:
        raise RuntimeError('Authenticated local emulator gRPC endpoint required')
    metadata = (('authorization', 'Bearer '+token),)
    channel = grpc.insecure_channel('127.0.0.1:'+config.get('grpc.port', '8554'))
    classes = messages()
    AudioFormat, AudioPacket, MicrophoneState = (classes[key] for key in ['AudioFormat', 'AudioPacket', 'MicrophoneState'])
    prefix = '/android.emulation.control.EmulatorController/'
    serialize = lambda message: message.SerializeToString()
    get_mic = channel.unary_unary(prefix+'getMicrophoneState', request_serializer=serialize, response_deserializer=MicrophoneState.FromString)
    set_mic = channel.unary_unary(prefix+'setMicrophoneState', request_serializer=serialize, response_deserializer=empty_pb2.Empty.FromString)
    inject = channel.stream_unary(prefix+'injectAudio', request_serializer=serialize, response_deserializer=empty_pb2.Empty.FromString)
    stream = channel.unary_stream(prefix+'streamAudio', request_serializer=serialize, response_deserializer=AudioPacket.FromString)
    original = get_mic(empty_pb2.Empty(), metadata=metadata, timeout=5)
    state = {'phase': 'starting', 'phase_started': time.monotonic()}
    stop = threading.Event()
    host = {'mode': options.mode, 'fixture': source.name, 'fixtureSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'hostMicrophoneInitiallyEnabled': original.realAudioEnabled, 'phases': [], 'input': 'emulator gRPC injectAudio, PCM16 mono 16 kHz'}
    if options.no_inject:
        host['input'] = 'No injected speech; microphone concurrency and ownership only'
    (out/'host.json').write_text(json.dumps(host, ensure_ascii=False, indent=2), encoding='utf-8')
    output = {}
    audio_stream = None
    process = None
    injected = None
    fmt = AudioFormat(samplingRate=16000, channels=0, format=1, mode=1)

    def packets():
        next_packet = time.monotonic()
        while not stop.is_set() and state['phase'] in ('capture', 'direct', 'concurrent'):
            now = time.monotonic()
            phase = state['phase']
            elapsed = now-state['phase_started']-1.0
            # Finish before STT's end-of-speech closes the device input. Injecting
            # indefinitely without an active guest microphone can stall this emulator.
            if elapsed >= len(speech)/32000:
                break
            if phase in ('capture', 'direct', 'concurrent') and 0 <= elapsed < len(speech)/32000:
                offset = int(elapsed*16000)*2
                data = speech[offset:offset+640].ljust(640, b'\0')
            else:
                data = bytes(640)
            yield AudioPacket(format=fmt, timestamp=int(time.time()*1000000), audio=data)
            next_packet += .02
            stop.wait(max(0, next_packet-time.monotonic()))

    def record_output():
        try:
            for packet in audio_stream:
                if stop.is_set():
                    break
                if state['phase'] == 'playback':
                    output.setdefault('playback', bytearray()).extend(packet.audio)
        except grpc.RpcError as error:
            if not stop.is_set():
                host['outputError'] = error.code().name

    try:
        set_mic(MicrophoneState(realAudioEnabled=False), metadata=metadata, timeout=5)
        with (out / 'instrumentation.log').open('wb') as log:
            test = 'fileInputEventOrder' if options.mode == 'file-events' else 'recordPlaybackAndRecognize'
            extra = ['-e','auditSource',options.source] if options.source else []
            process = subprocess.Popen([ADB, '-s', 'emulator-5554', 'shell', 'am', 'instrument', '--user', '10', '-w', '-r',
                                        '-e', 'class', 'com.example.hd_project.tiki.RecordingSttAuditTest#'+test,
                                        '-e', 'auditId', options.id, '-e', 'auditMode', options.mode,
                                        *extra,
                                        PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+210
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    phase = json.loads(app_read(device_root+'/phase.json'))['phase']
                    if phase != state['phase']:
                        state.update(phase=phase, phase_started=time.monotonic())
                        host['phases'].append({'phase': phase, 'hostTime': time.time()})
                        print('phase:', phase, flush=True)
                        if phase in ('capture', 'direct', 'concurrent'):
                            # Inspect the guest input before connecting injected audio.
                            audio_state = adb('shell', 'dumpsys', 'audio')
                            (out / (phase+'-audio.txt')).write_bytes(audio_state)
                            if injected is None and not options.no_inject:
                                injected = inject.future(packets(), metadata=metadata, timeout=50)
                        elif phase == 'playback':
                            if injected is not None:
                                injected.result(timeout=5)
                                injected = None
                            audio_stream = stream(AudioFormat(samplingRate=16000, channels=0, format=1), metadata=metadata, timeout=30)
                            threading.Thread(target=record_output, daemon=True).start()
                        elif audio_stream is not None:
                            audio_stream.cancel()
                            audio_stream = None
                    time.sleep(.2)
                except (subprocess.CalledProcessError, json.JSONDecodeError):
                    time.sleep(.2)
                if injected is not None and injected.done():
                    injected.result()
            if process.poll() is None:
                raise TimeoutError('Instrumentation audit exceeded 210 seconds')
        host['instrumentationExitCode'] = process.returncode
    finally:
        stop.set()
        if audio_stream is not None:
            audio_stream.cancel()
        try:
            if injected is not None:
                injected.result(timeout=3)
        except Exception as error:
            host['injectionFinish'] = error.__class__.__name__
        try:
            set_mic(original, metadata=metadata, timeout=5)
            host['hostMicrophoneRestored'] = get_mic(empty_pb2.Empty(), metadata=metadata, timeout=5).realAudioEnabled == original.realAudioEnabled
        except grpc.RpcError as error:
            host['hostMicrophoneRestoreError'] = error.code().name
        finally:
            channel.close()
        for name, data in output.items():
            with wave.open(str(out / (name+'-emulator-output.wav')), 'wb') as wav:
                wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000); wav.writeframes(data)
        for name in ('report.json', 'recorded.m4a', 'phase.json'):
            try:
                (out/name).write_bytes(app_read(device_root+'/'+name))
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                pass
        (out/'host.json').write_text(json.dumps(host, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Artifacts:', out, flush=True)
    log_text = (out/'instrumentation.log').read_text(encoding='utf-8', errors='replace')
    # adb may return zero even when the Android test failed. Observation-only
    # concurrency tests can pass while reporting a silenced STT input.
    if host.get('instrumentationExitCode') != 0 or 'OK (1 test)' not in log_text:
        raise SystemExit('Android audit failed or did not finish; inspect instrumentation.log and report.json')


if __name__ == '__main__':
    main()
