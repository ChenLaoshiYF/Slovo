"""Add attested Russian IPA from an English Wiktionary (Kaikki) JSONL gzip to the bundled dictionary.

Usage: python tools/extend_dictionary_ipa.py path/to/kaikki.org-dictionary-Russian.jsonl.gz
"""
import gzip
import json
from pathlib import Path
import sqlite3
import sys
import tempfile

asset = Path(__file__).resolve().parents[1] / "app/src/main/assets/dictionary-details.bin"

def normalize(text):
    return text.lower().replace("ё", "е").replace("́", "")

def reading_score(entry, expected):
    word = entry.get("word", "").lower()
    accented = {form.get("form", "").lower() for form in entry.get("forms", [])
                if "canonical" in form.get("tags", [])}
    if expected in accented:
        return 2
    return int(word == expected.replace("́", ""))

def main(dump):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "detail.sqlite"
        path.write_bytes(gzip.decompress(asset.read_bytes()))
        with sqlite3.connect(path) as db:
            needed = {}
            for key, body in db.execute("SELECT k,body FROM entries"):
                record = json.loads(body)
                if not record.get("ipa"):
                    needed[key] = record.get("stress") or record["word"]
            matches = {}
            with gzip.open(dump, "rt", encoding="utf8") as file:
                for line in file:
                    entry = json.loads(line)
                    key = normalize(entry.get("word", ""))
                    if key not in needed:
                        continue
                    score = reading_score(entry, needed[key].lower())
                    if score < 1 or (key in matches and matches[key][0] >= score):
                        continue
                    for sound in entry.get("sounds", []):
                        ipa = sound.get("ipa", "")
                        if ipa.startswith("[") and ipa.endswith("]") and not sound.get("tags"):
                            matches[key] = (score, ipa)
                            break
            for key, (_, ipa) in matches.items():
                body, = db.execute("SELECT body FROM entries WHERE k=?", (key,)).fetchone()
                record = json.loads(body)
                record["ipa"] = ipa
                db.execute("UPDATE entries SET body=? WHERE k=?", (
                    json.dumps(record, ensure_ascii=False, separators=(",", ":")), key))
            db.commit()
            print("Added dictionary transcriptions:", len(matches))
        db.close()
        asset.write_bytes(gzip.compress(path.read_bytes(), compresslevel=8, mtime=0))

if __name__ == "__main__":
    main(sys.argv[1])
