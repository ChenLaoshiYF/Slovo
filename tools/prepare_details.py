"""Pack complete licensed senses, examples and OpenRussian paradigms.

Usage: python prepare_details.py RU_JSONL OPENRUSSIAN_DIRECTORY
The input files are build-time data. The Android app reads compact SQLite.
"""
from collections import defaultdict
import csv
import gzip
import json
from pathlib import Path
import re
import sqlite3
import sys
import unicodedata
from opencc import OpenCC
CONVERT = OpenCC("t2s").convert

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"


def key(value):
    return unicodedata.normalize("NFC", value).lower().replace("́", "").replace("ё", "е").strip()


def accent(value):
    return value.replace("'", "́")


def build(raw, tables):
    entries = {}
    aliases = {}
    for line in raw.open(encoding="utf8"):
        source = json.loads(line)
        word = source.get("word", "")
        k = key(word)
        if not re.fullmatch("[а-я -]{1,64}", k):
            continue
        entry = entries.setdefault(k, {"word": word, "senses": [], "forms": [], "examples": []})
        for sense in source.get("senses", []):
            for parent in sense.get("form_of", []):
                lemma = key(parent.get("word", ""))
                if lemma and lemma != k:
                    aliases.setdefault(k, []).append(lemma)
                    entry["lemma"] = lemma
                    entry["formLabel"] = CONVERT("；".join(sense.get("glosses", [])))
            for text in sense.get("glosses", []):
                if text and text not in entry["senses"]:
                    entry["senses"].append(CONVERT(text))
            for example in sense.get("examples", []):
                ru = example.get("text", "")
                zh = example.get("translation", "")
                if ru and zh:
                    entry["examples"].append([ru, zh])
        for sound in source.get("sounds", []):
            if sound.get("ipa"):
                entry["ipa"] = sound["ipa"]
        for form in source.get("forms", []):
            if form.get("tags") == ["canonical"]:
                entry["stress"] = form.get("form", word)
        for field in ("synonyms", "antonyms", "related", "derived"):
            values = [x["word"] for x in source.get(field, []) if x.get("word")]
            if values:
                entry[field] = values[:24]
        entry["source"] = "中文维基词典 / Kaikki · CC BY-SA 4.0"

    shelves = json.loads((ASSETS / "shelves.json").read_text(encoding="utf8"))["words"]
    shelves.update(json.loads((ASSETS / "supplements.json").read_text(encoding="utf8")))
    for k, values in shelves.items():
        entry = entries.setdefault(k, {"word": values[0], "senses": [], "forms": [], "examples": []})
        if not entry["senses"]:
            entry["senses"].append(values[1])
        entry["pos"] = values[2]
    authored = ROOT / "tools/dictionary_notes.json"
    notes = json.loads(authored.read_text(encoding="utf8"))
    for k in notes:
        entries.setdefault(k, {"word": k, "senses": [], "forms": [], "examples": []})
    preferred = {k: v.get("stress", "") for k, v in notes.items()}
    usage = json.loads((ASSETS / "usage.json").read_text(encoding="utf8"))
    preferred.update({k: v["stress"] for k, v in usage.items() if v.get("stress")})
    shortcut = {k: [v.get("stress", k), v["senses"][0], v.get("pos", "俄语词条")]
                for k, v in notes.items() if k not in shelves}
    (ASSETS / "dictionary-shortcuts.json").write_text(json.dumps(shortcut, ensure_ascii=False), encoding="utf8")
    form_keys = defaultdict(set)
    form_labels = {}
    fields = {
        "nouns": [
            ("单数 · 主格", "sg_nom"), ("单数 · 属格", "sg_gen"), ("单数 · 与格", "sg_dat"),
            ("单数 · 宾格", "sg_acc"), ("单数 · 工具格", "sg_inst"), ("单数 · 前置格", "sg_prep"),
            ("复数 · 主格", "pl_nom"), ("复数 · 属格", "pl_gen"), ("复数 · 与格", "pl_dat"),
            ("复数 · 宾格", "pl_acc"), ("复数 · 工具格", "pl_inst"), ("复数 · 前置格", "pl_prep"),
        ],
        "verbs": [
            ("я", "presfut_sg1"), ("ты", "presfut_sg2"), ("он / она / оно", "presfut_sg3"),
            ("мы", "presfut_pl1"), ("вы", "presfut_pl2"), ("они", "presfut_pl3"),
            ("过去时 · 阳性", "past_m"), ("过去时 · 阴性", "past_f"),
            ("过去时 · 中性", "past_n"), ("过去时 · 复数", "past_pl"),
            ("命令式 · ты", "imperative_sg"), ("命令式 · вы", "imperative_pl"),
        ],
        "adjectives": [
            (gender + " · " + case, "decl_" + g + "_" + c)
            for gender, g in (("阳性", "m"), ("阴性", "f"), ("中性", "n"), ("复数", "pl"))
            for case, c in (("主格", "nom"), ("属格", "gen"), ("与格", "dat"),
                            ("宾格", "acc"), ("工具格", "inst"), ("前置格", "prep"))
        ] + [("比较级", "comparative"), ("最高级", "superlative"), ("短尾 · 阳性", "short_m"),
             ("短尾 · 阴性", "short_f"), ("短尾 · 中性", "short_n"), ("短尾 · 复数", "short_pl")],
        "others": [],
    }
    for kind, columns in fields.items():
        with (tables / ("openrussian-" + kind + ".csv")).open(encoding="utf8") as stream:
            for row in csv.DictReader(stream, delimiter="\t"):
                k = key(row["bare"])
                if k not in entries:
                    continue
                entry = entries[k]
                wanted = preferred.get(k, "")
                if wanted and wanted != accent(row["accented"]):
                    continue
                pos_for_kind = {"nouns": "名词", "verbs": "动词", "adjectives": "形容词"}
                if k in shelves and entry.get("pos") in pos_for_kind.values() and pos_for_kind.get(kind) != entry.get("pos"):
                    continue
                entry["stress"] = accent(row["accented"])
                entry.setdefault("english", row.get("translations_en", ""))
                aspect = row.get("aspect", "")
                if aspect:
                    entry["aspect"] = "未完成体" if aspect == "imperfective" else "完成体"
                if row.get("partner"):
                    entry["partner"] = row["partner"]
                if row.get("gender"):
                    entry["gender"] = {"m": "阳性", "f": "阴性", "n": "中性"}.get(row["gender"], row["gender"])
                if row.get("animate") == "1":
                    entry["animate"] = "动物名词"
                for label, field in columns:
                    value = accent(row.get(field, "").strip())
                    if not value:
                        continue
                    item = [label, value]
                    if item not in entry["forms"]:
                        entry["forms"].append(item)
                    for token in re.findall("[а-яё́-]+", value.lower()):
                        f = key(token)
                        form_keys[f].add(k)
                        form_labels[(f, k)] = label
                entry["grammarSource"] = "OpenRussian · CC BY-SA 4.0"

    sentences = json.loads((ASSETS / "photo_sentences.json").read_text(encoding="utf8"))
    for sentence, values in sentences.items():
        matched = set()
        for token in re.findall("[а-яё-]+", sentence):
            k = key(token)
            if k in entries:
                matched.add(k)
            matched.update(form_keys.get(k, ()))
        for k in matched:
            entry = entries[k]
            if len(entry["examples"]) < 8:
                entry["examples"].append([sentence[:1].upper() + sentence[1:], values[0],
                                           values[1], values[2]])

    for k, value in notes.items():
        entry = entries.setdefault(k, {"word": k, "forms": [], "examples": []})
        entry.update(value)
        entry["editorial"] = True

    for f, lemmas in aliases.items():
        for lemma in lemmas:
            if lemma in entries:
                form_keys[f].add(lemma)
    database = sqlite3.connect(":memory:")
    database.executescript(
        "CREATE TABLE entries(k TEXT PRIMARY KEY, body TEXT NOT NULL) WITHOUT ROWID;"
        "CREATE TABLE forms(f TEXT, k TEXT, label TEXT, PRIMARY KEY(f,k)) WITHOUT ROWID;"
    )
    database.executemany("INSERT INTO entries VALUES (?,?)",
                        ((k, json.dumps(v, ensure_ascii=False, separators=(",", ":"))) for k, v in entries.items()))
    database.executemany("INSERT OR IGNORE INTO forms VALUES (?,?,?)",
                        ((f, k, form_labels.get((f, k), "")) for f, keys in form_keys.items() for k in keys if f != k))
    database.commit()
    data = database.serialize()
    (ASSETS / "dictionary-details.bin").write_bytes(gzip.compress(data, compresslevel=8))
    print(len(entries), "detailed entries,", sum(bool(x["forms"]) for x in entries.values()),
          "paradigms,", sum(bool(x["examples"]) for x in entries.values()), "entries with examples")
    print("занимать", entries["занимать"]["forms"])


if __name__ == "__main__":
    build(Path(sys.argv[1]), Path(sys.argv[2]))
