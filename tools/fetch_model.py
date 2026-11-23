"""Prepare the offline model assets from the pinned GGUF or a local copy."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.request import urlopen

MODEL = (
    "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/"
    "50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf"
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
