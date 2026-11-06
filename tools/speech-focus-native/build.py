#!/usr/bin/env python3
"""Build an isolated real-engine focus test APK from production focus sources (no app JNI)."""
import argparse
import math
import os
from pathlib import Path
import struct
import subprocess
import wave
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    java = Path(os.environ['JAVA_HOME'])/'bin'
    tools = sdk/'build-tools/37.0.0'
    platform = sdk/'platforms/android-36/android.jar'
    resources = output/'res/raw'
    resources.mkdir(parents=True, exist_ok=True)
    for name, hz in (('background', 440), ('alarm', 880)):
        with wave.open(str(resources/f'{name}.wav'), 'wb') as stream:
            stream.setparams((1, 2, 48000, 0, 'NONE', 'not compressed'))
            stream.writeframes(b''.join(struct.pack('<h', round(6000*math.sin(2*math.pi*hz*i/48000)))
                                       for i in range(48000)))
    def run(*command):
        subprocess.run([str(part) for part in command], check=True)
    run(tools/'aapt2', 'compile', '--dir', output/'res', '-o', output/'resources.zip')
    run(tools/'aapt2', 'link', '-I', platform, '--manifest', HERE/'AndroidManifest.xml',
        '-o', output/'unsigned.apk', output/'resources.zip')
    classes = output/'classes'
    classes.mkdir(exist_ok=True)
    source = ROOT/'Common/src/main/java/tk/glucodata'
    run(java/'javac', '-source', '8', '-target', '8', '-cp', platform, '-d', classes,
        source/'SpeechFocus.java', source/'SpeechAudioFocus.java', HERE/'Applic.java', HERE/'FocusPlaybackActivity.java')
    dex = output/'dex'
    dex.mkdir(exist_ok=True)
    run(tools/'d8', '--lib', platform, '--min-api', '26', '--output', dex, *sorted(classes.rglob('*.class')))
    with zipfile.ZipFile(output/'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(dex/'classes.dex', 'classes.dex')
    run(tools/'zipalign', '-f', '4', output/'unsigned.apk', output/'aligned.apk')
    run(tools/'apksigner', 'sign', '--ks', ROOT/'Common/everyone.keystore', '--ks-key-alias', 'thealias',
        '--ks-pass', 'pass:IDontKnow', '--key-pass', 'pass:IDontKnow',
        '--out', output/'speech-focus-test.apk', output/'aligned.apk')
    print(output/'speech-focus-test.apk')


if __name__ == '__main__':
    main()
