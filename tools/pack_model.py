"""Package the pinned Qwen GGUF in small pieces for Android Gradle."""
from pathlib import Path
import hashlib
import sys

model = Path(sys.argv[1])
out = Path(__file__).resolve().parents[1]/'app/src/main/assets/ai'
out.mkdir(parents=True, exist_ok=True)
with model.open('rb') as reader:
    assert hashlib.file_digest(reader,'sha256').hexdigest() == 'ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a'
    reader.seek(0)
    for index in range(8):
        part = reader.read(48*1024*1024)
        assert part
        (out/f'qwen-q4.part{index}').write_bytes(part)
    assert not reader.read(1)

