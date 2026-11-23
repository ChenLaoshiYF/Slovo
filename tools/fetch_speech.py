"""Fetch pinned speech dependencies and pack the on-device Russian voice.

Build-machine only. The released Android app never downloads its speech engine.
"""
from pathlib import Path
import hashlib
import shutil
import tarfile
import tempfile
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FILES = [
    ("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.17/sherpa-onnx-1.12.17.aar",
     "96853e39b5b93f90c7d161cace8eb0898339c7dd6017c9f92975cb330e9d66fd"),
    ("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-denis-medium-int8.tar.bz2",
     "d710e29eb7854c42461d16d65d3cef753cc559639ca75806edc75ba71f23af66"),
    ("https://github.com/csukuangfj/espeak-ng/archive/f6fed6c58b5e0998b8e68c6610125e2d07d595a7.zip",
     "70cbf4050e7a014aae19140b05e57249da4720f56128459fbe3a93beaf971ae6"),
]


def main():
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        downloaded = []
        for url, expected in FILES:
            path = work / url.rsplit("/", 1)[-1]
            with urlopen(url, timeout=90) as source, path.open("wb") as destination:
                shutil.copyfileobj(source, destination)
            actual = hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
            if actual != expected:
                raise ValueError("Speech dependency checksum mismatch")
            downloaded.append(path)
        shutil.copyfile(downloaded[0], ROOT / "app/libs/sherpa-onnx-1.12.17.aar")
        with tarfile.open(downloaded[1]) as archive:
            archive.extractall(work, filter="data")
        voice = work / "vits-piper-ru_RU-denis-medium-int8"
        with zipfile.ZipFile(ROOT / "app/src/main/assets/speech-denis.bin", "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(voice.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(voice).as_posix())
        source = ROOT / ".speech-source"
        source.mkdir(exist_ok=True)
        with zipfile.ZipFile(downloaded[2]) as archive:
            archive.extractall(source)
        print("Build auxiliary voices with tools/build_speech.ps1 -EspeakSource", source / "espeak-ng-f6fed6c58b5e0998b8e68c6610125e2d07d595a7")


if __name__ == "__main__":
    main()
