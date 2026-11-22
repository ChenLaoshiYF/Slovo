"""Prepare the offline model assets from the pinned GGUF or a local copy."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.request import urlopen

MODEL = (
    "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/"
    "9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_0.gguf"
)
PACK = Path(__file__).with_name("pack_model.py")

def pack(model):
    subprocess.run([sys.executable, str(PACK), str(model)], check=True)

if len(sys.argv) > 1:
    pack(Path(sys.argv[1]))
else:
    with tempfile.TemporaryDirectory() as temporary:
        model = Path(temporary) / "qwen-q4.gguf"
        with urlopen(MODEL, timeout=90) as source, model.open("wb") as destination:
            shutil.copyfileobj(source, destination)
        pack(model)
