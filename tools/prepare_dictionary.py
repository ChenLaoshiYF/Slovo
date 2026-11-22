"""Build the distributable offline lexicon. Usage: python prepare_dictionary.py RU_JSONL.
Install opencc-python-reimplemented and pymorphy3 on the build computer only."""
import json, re, sqlite3, sys, unicodedata
from pathlib import Path
from opencc import OpenCC
import pymorphy3
root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets'
conv=OpenCC('t2s')
morph=pymorphy3.MorphAnalyzer()
def norm(word):
    return word.lower().replace('\u0301','').replace('ё','е').strip()
books=[]; curated={}
for line in (root/'tools/books.txt').read_text(encoding='utf8').splitlines():
    if not line or line.startswith('#'):continue
    if line.startswith('['):
        key,title,sub,ru=line[1:-1].split('|')
        books.append(dict(key=key,title=title,sub=sub,ru=ru,words=[]));continue
    for item in line.split(';'):
        if not item.strip():continue
        m=re.fullmatch(r'([А-Яа-яЁё -]+?)\s+([^А-Яа-яЁё].*)',item.strip())
        if not m:raise ValueError(item)
        ru,zh=m.groups(); k=norm(ru)
        if k not in curated:curated[k]=[ru,zh,'']
        books[-1]['words'].append(k)
db=assets/'lexicon.sqlite'
if db.exists():db.unlink()
c=sqlite3.connect(db)
c.executescript('CREATE TABLE lex(k TEXT PRIMARY KEY,ru TEXT NOT NULL,zh TEXT NOT NULL,pos TEXT NOT NULL) WITHOUT ROWID; CREATE TABLE forms(f TEXT,k TEXT,PRIMARY KEY(f,k)) WITHOUT ROWID;')
posnames={'noun':'名词','verb':'动词','adj':'形容词','adv':'副词','conj':'连词','prep':'前置词','pron':'代词','name':'专名','num':'数词','phrase':'短语','interj':'感叹词'}
glosses={};forms=set()
for line in Path(sys.argv[1]).open(encoding='utf8'):
    x=json.loads(line); word=x.get('word','').strip(); k=norm(word)
    if not re.fullmatch('[а-яё -]{1,48}',k):continue
    vals=[]
    for sense in x.get('senses',[]):
        for g in sense.get('glosses',[]):
            if not re.search('[\u3400-\u9fff]',g) or len(g)>280:continue
            g=re.sub(r'^.{0,40}?〔[阳阴中复完未副形动名][^〕]{0,9}〕','',g).strip(' ,，.。')
            if not g:continue
            if g not in vals:vals.append(g)
    if not vals:continue
    entry=glosses.setdefault(k,[word,[],posnames.get(x.get('pos'),'')])
    entry[1].extend(v for v in vals[:2] if v not in entry[1])
    for f in x.get('forms',[]):
        form=norm(f.get('form',''))
        if form!=k and re.fullmatch('[а-яё-]{2,40}',form):forms.add((form,k))
for k,(word,vals,pos) in glosses.items():
    c.execute('insert into lex values(?,?,?,?)',(k,word,conv.convert('；'.join(vals[:2])[:200]),pos))
tagmap={'NOUN':'名词','INFN':'动词','ADJF':'形容词','ADVB':'副词','CONJ':'连词','NPRO':'代词','PREP':'前置词'}
# The shelf gloss determines these labels. A single token can also have other uses.
curated_pos={'куда':'副词','потому что':'连词','вследствие':'前置词',
             'возможно':'副词／插入语','например':'插入语','напротив':'插入语'}
for k,entry in curated.items():
    analyses=morph.parse(entry[0])
    selected=next((p for p in analyses if norm(p.normal_form)==k),analyses[0])
    entry[2]=curated_pos.get(k,tagmap.get(selected.tag.POS,'短语' if ' ' in k else ''))
    if ' ' not in k:
        for t in selected.lexeme:
            alt=norm(t.word)
            if alt!=k:forms.add((alt,k))
    c.execute('insert or replace into lex values(?,?,?,?)',(k,*entry))
c.executemany('insert or ignore into forms values(?,?)',sorted(forms))
c.execute('create table zh_index(gram text,k text,primary key(gram,k)) without rowid')
for k,zh in c.execute('select k,zh from lex').fetchall():
    tokens={s[i:i+2] for s in re.findall('[\u3400-\u9fff]+',zh) for i in range(len(s)-1)}
    tokens.update(ch for ch in zh if '\u3400'<=ch<='\u9fff')
    c.executemany('insert or ignore into zh_index values(?,?)',[(g,k) for g in tokens])
count=c.execute('select count(*) from lex').fetchone()[0];c.commit();c.execute('vacuum');c.close()
(assets/'shelves.json').write_text(json.dumps(dict(books=books,words=curated,lexCount=count),ensure_ascii=False,separators=(',',':')),encoding='utf8')
print(len(curated),count,len(forms),db.stat().st_size)
import gzip, shutil
with db.open('rb') as src,gzip.open(assets/'lexicon.bin','wb',compresslevel=6) as dst:
    shutil.copyfileobj(src,dst)
db.unlink()

