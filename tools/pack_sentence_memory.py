"""Build the small, exact-match offline Russian–Chinese photo sentence memory."""
import bz2
import json
from pathlib import Path
import re
import sys
import unicodedata
from urllib.request import urlretrieve

root = Path(__file__).resolve().parents[1]
cache = Path(sys.argv[1])
cache.mkdir(parents=True, exist_ok=True)
base = 'https://downloads.tatoeba.org/exports/per_language/'

def rows(path):
    target = cache / path.split('/')[-1]
    if not target.exists():
        partial = target.with_suffix('.download')
        urlretrieve(base + path, partial)
        partial.replace(target)
    with bz2.open(target, 'rt', encoding='utf-8') as stream:
        for line in stream:
            yield line.rstrip('\n').split('\t')

def key(text):
    text = unicodedata.normalize('NFD', text.lower().replace('ё', 'е')).replace('\u0301', '')
    return re.sub(r'\s+', ' ', unicodedata.normalize('NFC', text)).strip(' .!?…«»"')

pairs = [(int(r[0]), int(r[1])) for r in rows('rus/rus-cmn_links.tsv.bz2') if len(r) == 2]
ru_ids, zh_ids = set(i for i, _ in pairs), set(j for _, j in pairs)
ru = {i: r[2] for r in rows('rus/rus_sentences.tsv.bz2')
      if len(r) == 3 and (i := int(r[0])) in ru_ids}
zh = {i: r[2] for r in rows('cmn/cmn_sentences.tsv.bz2')
      if len(r) == 3 and (i := int(r[0])) in zh_ids}
records = {}
for i, j in sorted(pairs):
    if i not in ru or j not in zh:
        continue
    source, translation = ru[i], zh[j]
    if not (2 <= len(source.split()) and 5 <= len(source) <= 120 and 2 <= len(translation) <= 100):
        continue
    if not re.search(r'[\u4e00-\u9fff]', translation):
        continue
    records.setdefault(key(source), [translation, i, j])

# Original translations for common phrases from signs, station notices and forms.
original = {
    'Пожалуйста, возьмите паспорт.':'请拿上您的护照。',
    'Мы встретимся у вокзала.':'我们在火车站见面。',
    'Предъявите паспорт, пожалуйста.':'请出示护照。',
    'Предъявите билет при посадке.':'上车时请出示车票。',
    'Посадка заканчивается за двадцать минут до вылета.':'登机在起飞前二十分钟结束。',
    'Посадка уже началась.':'已经开始登机。',
    'Пожалуйста, сохраняйте билет до конца поездки.':'请保留车票至本次行程结束。',
    'Осторожно, двери закрываются.':'请注意，车门即将关闭。',
    'Станция закрыта на ремонт.':'车站因维修暂停开放。',
    'На этой станции поезд не останавливается.':'本次列车不停靠本站。',
    'Билеты можно купить в кассе.':'车票可在售票处购买。',
    'Багаж можно оставить здесь.':'行李可以放在这里。',
    'Где находится камера хранения?':'行李寄存处在哪里？',
    'Вход с другой стороны.':'入口在另一侧。',
    'Выход через соседнюю дверь.':'请从旁边的门出去。',
    'Вход только для сотрудников.':'仅限工作人员入内。',
    'Не оставляйте вещи без присмотра.':'请照看好您的随身物品。',
    'Возьмите с собой документы.':'请随身携带证件。',
    'Заполните, пожалуйста, эту анкету.':'请填写这张表。',
    'Укажите номер телефона.':'请填写电话号码。',
    'Здесь можно оплатить картой.':'这里可以刷卡付款。',
    'Где ближайшая аптека?':'最近的药店在哪里？',
    'Заказ можно забрать завтра.':'订单可以明天来取。',
    'Номер комнаты указан на ключе.':'房间号写在钥匙上。',
    'Завтрак включён в стоимость.':'费用已包含早餐。',
    'Пожалуйста, говорите медленнее.':'请说慢一点。',
}
for source, translation in original.items():
    records[key(source)] = [translation, 0, 0]
asset = root / 'app/src/main/assets/photo_sentences.json'
asset.write_text(json.dumps(records, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
print(f'Packed {len(records)} exact sentence pairs, {len(original)} originals, {asset.stat().st_size} bytes.')

