"""Build compact Russian stress and inflection hints from OpenRussian TSV exports."""
import csv
import json
import re
import sys
from pathlib import Path

project = Path(__file__).resolve().parents[1]
assets = project / 'app/src/main/assets'
source = Path(sys.argv[1])
core = json.loads((assets / 'shelves.json').read_text(encoding='utf8'))['words']
keys = {'nouns': [('单数第二格', 'sg_gen'), ('复数第一格', 'pl_nom')],
        'verbs': [('я', 'presfut_sg1'), ('ты', 'presfut_sg2')],
        'adjectives': [('阴性', 'decl_f_nom'), ('复数', 'decl_pl_nom')], 'others': []}
normalize = lambda s: s.lower().replace('ё', 'е').replace('́', '')
source_for = {'名词': 'nouns', '动词': 'verbs', '形容词': 'adjectives'}
# Same part of speech, different lexemes or senses in the source. Match the shelf gloss.
stress_for_sense = {'правило': 'пра́вило', 'ужин': 'у́жин', 'лекарство': 'лека́рство',
                    'свойство': 'сво́йство', 'писать': 'писа́ть'}
# The export lists справа under nouns. The shelf uses the adverb meaning “on the right”.
clues = {'справа': {'stress': 'спра́ва', 'forms': ''}}
for kind, fields in keys.items():
    with (source / (kind + '.data')).open(encoding='utf8') as f:
        for row in csv.DictReader(f, delimiter='\t'):
            key = normalize(row['bare'])
            if key not in core or kind != source_for.get(core[key][2], 'others'):
                continue
            accent = row['accented'].replace("'", '́')
            if normalize(accent) != key or accent != stress_for_sense.get(key, accent):
                continue
            if key in clues:
                raise ValueError(f"Choose a sense for {key!r} before publishing its word forms.")
            forms = []
            for title, field in fields:
                value = row.get(field, '').strip().replace("'", '́')
                if re.fullmatch('[а-яё́ -]{1,30}', value) and normalize(value) != key:
                    forms.append(title + '　' + value)
            clues[key] = {'stress': accent, 'forms': '   ·   '.join(forms)}
if clues.keys() != core.keys():
    raise ValueError(f"Missing or extra core hints: {clues.keys() ^ core.keys()}")
clues['родители']['stress'] = 'роди́тели'
for line in (project / 'tools/usage_examples.txt').read_text(encoding='utf8').splitlines():
    key, ru, zh = line.split('|')
    assert key in core
    clues.setdefault(key, {}).update(example=ru, translation=zh)
(assets / 'usage.json').write_text(json.dumps(clues, ensure_ascii=False, separators=(',', ':')), encoding='utf8')
print(len(clues), 'words with study hints,', sum('example' in c for c in clues.values()), 'bilingual examples')

