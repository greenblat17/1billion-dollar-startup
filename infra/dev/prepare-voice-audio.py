#!/usr/bin/env python3
"""Build and verify the synthetic DEV voice runner's PCM fixtures."""

import argparse
import ctypes
from hashlib import sha256
import json
from pathlib import Path
import sys
import wave


SAMPLE_RATE = 24000
ENGINE = 'espeakng_loader_0.2.4_python_ratecv'


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def synthesize_turns(turns):
    # Generation runs on the Python 3.12 GitHub runner.
    from audioop import ratecv
    from espeakng_loader import get_data_path, get_library_path

    callback_type = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.POINTER(ctypes.c_short), ctypes.c_int, ctypes.c_void_p)
    library = ctypes.CDLL(get_library_path())
    library.espeak_Initialize.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
    library.espeak_SetSynthCallback.argtypes = [callback_type]
    library.espeak_SetSynthCallback.restype = None
    library.espeak_SetVoiceByName.argtypes = [ctypes.c_char_p]
    library.espeak_Synth.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_uint, ctypes.c_int,
                                    ctypes.c_uint, ctypes.c_uint, ctypes.c_void_p, ctypes.c_void_p]
    synchronous, dont_exit = 2, 0x8000
    sample_rate = library.espeak_Initialize(synchronous, 0, str(Path(get_data_path()).parent).encode(), dont_exit)
    if sample_rate <= 0:
        raise RuntimeError('eSpeak NG initialization failed')
    pcm = bytearray()

    @callback_type
    def collect(samples, count, _):
        if samples and count > 0:
            pcm.extend(ctypes.string_at(samples, count * 2))
        return 0

    library.espeak_SetSynthCallback(collect)
    try:
        for turn in turns:
            text = turn['text']
            voice = b'ru' if any('\u0400' <= char <= '\u04ff' for char in text) else b'en'
            if library.espeak_SetVoiceByName(voice) != 0:
                raise RuntimeError('eSpeak NG voice unavailable')
            pcm.clear()
            speech = text.encode() + b'\0'
            utf8_with_end_pause = 0x1001
            if library.espeak_Synth(speech, len(speech), 0, 1, 0, utf8_with_end_pause, None, None) != 0:
                raise RuntimeError('eSpeak NG synthesis failed')
            yield ratecv(bytes(pcm), 2, 1, sample_rate, SAMPLE_RATE, None)[0]
    finally:
        library.espeak_Terminate()


def generate(directory, paths):
    directory.mkdir(parents=True, exist_ok=True)
    for scenario in paths:
        turns = json.loads(scenario.read_text())['turns']
        inputs = directory / scenario.stem
        inputs.mkdir(exist_ok=True)
        hashes = []
        for index, pcm in enumerate(synthesize_turns(turns), 1):
            wav = inputs / f'turn-{index:02}.wav'
            with wave.open(str(wav), 'wb') as output:
                output.setparams((1, 2, SAMPLE_RATE, 0, 'NONE', 'not compressed'))
                output.writeframes(pcm)
            hashes.append(digest(wav))
        manifest = {'scenarioSha256': digest(scenario), 'engine': ENGINE, 'wavSha256': hashes}
        (inputs / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        verify(directory, scenario)


def verify(directory, scenario):
    document = json.loads(scenario.read_text())
    inputs = (directory / scenario.stem).resolve()
    manifest = json.loads((inputs / 'manifest.json').read_text())
    turns, hashes = document['turns'], manifest['wavSha256']
    if manifest['scenarioSha256'] != digest(scenario) or manifest['engine'] != ENGINE or len(hashes) != len(turns):
        raise ValueError(f'Audio manifest does not match {scenario.name}')

    for index, (turn, expected_hash) in enumerate(zip(turns, hashes), 1):
        wav = inputs / f'turn-{index:02}.wav'
        if digest(wav) != expected_hash:
            raise ValueError(f'Audio checksum mismatch for {scenario.name} turn {index}')
        with wave.open(str(wav), 'rb') as audio:
            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, SAMPLE_RATE, 'NONE'):
                raise ValueError(f'Invalid PCM format for {scenario.name} turn {index}')
            if not 0 < audio.getnframes() <= 25 * SAMPLE_RATE:
                raise ValueError(f'Invalid PCM duration for {scenario.name} turn {index}')
        turn['audio'] = str(wav)
    document['preparedAudio'] = manifest
    print(f'Verified {scenario.name}: {len(turns)} WAV, PCM mono 24 kHz, {ENGINE}', file=sys.stderr)
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    build = commands.add_parser('generate')
    build.add_argument('directory', type=Path)
    build.add_argument('scenarios', type=Path, nargs='+')
    check = commands.add_parser('verify')
    check.add_argument('directory', type=Path)
    check.add_argument('scenario', type=Path)
    options = parser.parse_args()
    if options.command == 'generate':
        generate(options.directory, options.scenarios)
    else:
        print(json.dumps(verify(options.directory, options.scenario), ensure_ascii=False))


if __name__ == '__main__':
    main()
