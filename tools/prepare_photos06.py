"""Prepare offline portrait crops and full-resolution Gaussian glass from Pexels photographs."""
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
import json
import requests
from PIL import Image, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
RES = ROOT / 'app/src/main/res/drawable-nodpi'
# Stable IDs align with FieldArt.SCENES. Each portrait uses a separate photograph from the word cards.
SPECS = [
    ('volcano_coast', '堪察加 · 科里亚克火山', 'Корякская сопка', 8922559, 'Julia Volk', (.54,.5)),
    ('baikal_ice', '贝加尔湖 · 萨满岩', 'Скала Шаманка', 8800119, 'Alexander Nerozya', (.57,.5)),
    ('altai_autumn', '阿尔泰 · 秋日河谷', 'Осенний Алтай', 35261724, 'Юрий Жданов', (.51,.5)),
    ('neva_evening', '圣彼得堡 · 涅瓦河', 'Нева', 17792377, 'Vitali Adutskevich', (.47,.5)),
    ('karelia_shore', '卡累利阿 · 湖畔森林', 'Карелия', 34894249, 'Яна Гал', (.56,.5)),
    ('kamchatka_ridge', '堪察加 · 火山山脊', 'Камчатка', 11253383, 'Александр Максин', (.56,.5)),
    ('altai_mist', '阿尔泰 · 雾中河谷', 'Алтай', 8974343, 'Александр Доровских', (.47,.5)),
    ('sochi_hills', '索契 · 山间村庄', 'Сочи', 4513207, 'Anastasia Shuraeva', (.52,.5)),
    ('neva_fortress', '圣彼得堡 · 彼得保罗要塞', 'Петропавловская крепость', 32832223, 'Nadine K', (.49,.5)),
    ('moscow_river', '莫斯科 · 日落河岸', 'Москва-река', 11489891, 'Ruby Ruby', (.51,.5)),
]

def make(row):
    name, title, russian, number, author, center = row
    asset = ASSETS / 'scenery' / name
    asset.mkdir(parents=True, exist_ok=True)
    source = f'https://images.pexels.com/photos/{number}/pexels-photo-{number}.jpeg?auto=compress&cs=tinysrgb&w=3800'
    response = requests.get(source, timeout=35)
    response.raise_for_status()
    original = ImageOps.exif_transpose(Image.open(BytesIO(response.content))).convert('RGB')
    size = (1080, 2160)
    assert min(original.width / size[0], original.height / size[1]) >= 1, (number, original.size)
    crop = ImageOps.fit(original, size, Image.Resampling.LANCZOS, centering=center)
    crop.save(asset / 'photo.webp', quality=91, method=5)
    # Filter after the final crop at the same pixel dimensions. Neither layer is downsampled.
    for filename, radius in [('dock', 10), ('header', 36)]:
        crop.filter(ImageFilter.GaussianBlur(radius)).save(asset / f'{filename}.webp', quality=94, method=5)
    thumb = crop.copy()
    thumb.thumbnail((144, 288), Image.Resampling.LANCZOS)
    thumb.save(RES / f'photo_{name}.webp', quality=88)
    page = f'https://www.pexels.com/photo/{number}/'
    print(f'{name} source={original.size}, portrait={crop.size}', flush=True)
    if author.startswith('Pexels photo'):
        from bs4 import BeautifulSoup
        site = requests.get(page, timeout=12)
        if site.ok:
            document = BeautifulSoup(site.text, 'html.parser')
            for node in document.select('script[type="application/ld+json"]'):
                try:
                    data = json.loads(node.get_text())
                    creator = data.get('creator', data.get('author', {}))
                    if isinstance(creator, dict) and creator.get('name'):
                        author = creator['name']
                        break
                except (ValueError, AttributeError):
                    continue
    return dict(id=name, title=title, ru=russian, work=f'Pexels {number}', author=author,
                license='Pexels License', licenseUrl='https://www.pexels.com/license/',
                url=page, originalWidth=original.width, originalHeight=original.height)

if __name__ == '__main__':
    with ThreadPoolExecutor(5) as workers:
        photos = list(workers.map(make, SPECS))
    metadata = ASSETS / 'photography.json'
    data = json.loads(metadata.read_text(encoding='utf8'))
    new_keys = {photo['id'] for photo in photos}
    data['scenes'] = [old for old in data['scenes'] if old['id'] not in new_keys] + photos
    metadata.write_text(json.dumps(data, ensure_ascii=False), encoding='utf8')


