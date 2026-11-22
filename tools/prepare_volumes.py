"""Merge author-curated volumes into the built-in shelf and fetch their credited cover thumbnails."""
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
import json, re
from urllib.parse import urljoin
from bs4 import BeautifulSoup
from PIL import Image, ImageOps
import requests
import pymorphy3
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

Image.MAX_IMAGE_PIXELS = 120_000_000

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
RES = ROOT / 'app/src/main/res/drawable-nodpi'
SERIES = ('travel', 'university', 'modern', 'school', 'four', 'six')
POS = {'NOUN':'名词','INFN':'动词','ADJF':'形容词','ADVB':'副词','CONJ':'连词','PREP':'前置词',
       'NPRO':'代词','PRCL':'语气词','INTJ':'感叹词','NUMR':'数词'}
def key(word):return word.lower().replace('ё','е').replace('\u0301','')
MORPH = pymorphy3.MorphAnalyzer()

def lexical_class(word):
    if ' ' in word:return '短语'
    candidates = MORPH.parse(word)
    canonical = next((entry for entry in candidates if key(entry.normal_form) == key(word)), candidates[0])
    return POS.get(canonical.tag.POS,'')

def cover(book):
    idx = book.pop('publisher')
    if not idx.isdecimal():return None
    url = 'https://mlp.fltrp.com/wys/bookstore/detail?id=' + idx
    session = requests.Session()
    session.mount('https://', HTTPAdapter(max_retries=Retry(total=3, backoff_factor=.6)))
    response = session.get(url, timeout=18)
    response.raise_for_status()
    soup = BeautifulSoup(response.content.decode('utf8','replace'), 'html.parser')
    title = soup.title.get_text(strip=True).rsplit('-',1)[0]
    image_tag = next((element for element in soup.find_all('img')
                      if f'/book/{idx}/' in str(element)),None)
    cover_url = urljoin(url, image_tag['src']) if image_tag else f'https://mlp.fltrp.com/uploads/wys/book/{idx}/poster.jpg'
    target = RES / f"book_{book['key']}.webp"
    if not target.exists():
        content = session.get(cover_url, timeout=30)
        content.raise_for_status()
        with Image.open(BytesIO(content.content)) as original:
            original.draft('RGB', (360, 480))
            image = ImageOps.exif_transpose(original).convert('RGB')
        assert image.width >= 90 and image.height >= 120, (idx, image.size)
        image.thumbnail((264, 360), Image.Resampling.LANCZOS)
        image.save(target, quality=89)
    book['reference'] = title
    print('BOOK', idx, title, flush=True)
    return dict(id=book['key'], title=title, url=url, image=cover_url)

def main():
    target = ASSETS/'shelves.json'
    data = json.loads(target.read_text(encoding='utf8'))
    books = [book for book in data['books'] if book['key'] in SERIES]
    original_keys = {word for book in books for word in book['words']}
    core = {key:value for key,value in data['words'].items() if key in original_keys}
    data['words'] = core
    catalog_file = ASSETS/'photography.json'
    media = json.loads(catalog_file.read_text(encoding='utf8'))
    originals = {entry['id']:entry for entry in media['books'] if entry['id'] in {*SERIES, 'exam'}}
    old_titles = {'travel':'走遍俄罗斯 1', 'university':'东方大学俄语 1'}
    for book in books:
        name = book['key']
        cover_id = 'exam' if name == 'school' else name
        book.update(series=name, cover='book_'+cover_id, reference=originals[cover_id]['title'])
        book['title'] = old_titles.get(name,book['title'])
    chapters = []
    manual_pos = {'извините':'常用表达','спасибо':'感叹词','зовут':'动词形式',
                  'наличные':'名词','сбережения':'名词','пожалуйста':'常用表达',
                  'выходные':'名词','общее':'名词','посредством':'前置词',
                  'кстати':'插入语','впрочем':'连词／插入语','итак':'连词'}
    lines = (ROOT/'tools/volumes.txt').read_text(encoding='utf8').splitlines()
    for row in lines:
        row = row.strip()
        if not row or row.startswith('#'):continue
        if row.startswith('['):
            name,title,desc,ru,group,pub = row[1:-1].split('|')
            identifier = name if pub.isdecimal() else pub
            chapters.append(dict(key=name,title=title,sub=desc,ru=ru,series=group,
                                 publisher=pub,cover='book_'+identifier,
                                 reference=originals.get(identifier,{}).get('title',''),words=[]))
            continue
        for pair in row.split(';'):
            matched = re.fullmatch(r'([А-Яа-яЁё -]+?)\s+([^А-Яа-яЁё].*)',pair.strip())
            if not matched:raise ValueError(pair)
            russian,chinese = matched.groups()
            name = key(russian)
            core.setdefault(name,[russian,chinese,manual_pos.get(name,lexical_class(russian))])
            words = chapters[-1]['words']
            assert name not in words, (chapters[-1]['key'],name)
            words.append(name)
    with ThreadPoolExecutor(5) as threads:
        extra = list(filter(None,threads.map(cover,chapters)))
    groups = ('travel','university','modern','school','four','six')
    data['books'] = [b for group in groups for b in books+chapters if b['series'] == group]
    for b in chapters:assert len(b['words']) >= 45 and b['reference'], b['key']
    target.write_text(json.dumps(data,ensure_ascii=False,separators=(',',':')),encoding='utf8')
    # Refresh metadata from disk so an independent photography build has no stale state to overwrite.
    media=json.loads(catalog_file.read_text(encoding='utf8'))
    media['books']=list(originals.values())+extra
    catalog_file.write_text(json.dumps(media,ensure_ascii=False),encoding='utf8')
    print('PASS',len(data['books']),'books;',sum(len(b['words']) for b in data['books']),
          'memberships;',len(core),'unique words.', flush=True)
if __name__ == '__main__':main()

