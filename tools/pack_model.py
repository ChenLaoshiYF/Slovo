"""Package the pinned Qwen GGUF in small pieces for Android Gradle."""
from pathlib import Path
import hashlib
import sys

model = Path(sys.argv[1])
out = Path(__file__).resolve().parents[1]/'app/src/main/assets/ai'
out.mkdir(parents=True, exist_ok=True)
with model.open('rb') as reader:
    assert hashlib.file_digest(reader,'sha256').hexdigest() == '7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed'
    reader.seek(0)
    for index in range(9):
        part = reader.read(48*1024*1024)
        assert part
        (out/f'qwen-q4.part{index}').write_bytes(part)
    assert not reader.read(1)

