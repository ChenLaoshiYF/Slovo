"""Build native-resolution wallpaper and aligned glass layers for selected Pexels photographs."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from io import BytesIO
import json, re, requests, sys
from PIL import Image, ImageOps, ImageFilter

root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets'
drawables=root/'app/src/main/res/drawable-nodpi'
cache=Path(sys.argv[1])
cache.mkdir(exist_ok=True,parents=True)
size=(1080,2160)

def prepare(spec):
    number,title,russian,author=spec
    key='px_'+number
    cached=cache/(number+'.jpg')
    try:
        if not cached.exists():
            url=f'https://images.pexels.com/photos/{number}/pexels-photo-{number}.jpeg?auto=compress&cs=tinysrgb&w=3600'
            response=requests.get(url,timeout=25)
            response.raise_for_status()
            cached.write_bytes(response.content)
        with Image.open(cached) as image:
            original=ImageOps.exif_transpose(image).convert('RGB')
        if original.width<1080 or original.height<2160:
            raise ValueError(f'source is too small {original.size}')
        crop=ImageOps.fit(original,size,Image.Resampling.LANCZOS,centering=(.50,.49))
        folder=assets/'scenery'/key
        folder.mkdir(exist_ok=True)
        crop.save(folder/'photo.webp',quality=87,method=4)
        for file,radius in [('dock',10),('header',36)]:
            crop.filter(ImageFilter.GaussianBlur(radius)).save(folder/(file+'.webp'),quality=86,method=4)
        crop.resize((144,288),Image.Resampling.LANCZOS).save(drawables/('photo_'+key+'.webp'),quality=85,method=4)
        print(number,original.size,title,flush=True)
        return dict(id=key,title=title,ru=russian,work='Pexels '+number,
            author=author or 'Pexels 摄影作品 '+number,license='Pexels License',
            licenseUrl='https://www.pexels.com/license/',url='https://www.pexels.com/photo/'+number+'/',
            originalWidth=original.width,originalHeight=original.height)
    except Exception as problem:
        print(number,'SKIP',str(problem),flush=True)
        return None

specs=[(line.split('\t')+[''])[:4] for line in root.joinpath('tools/scenery07.tsv').read_text(encoding='utf8').splitlines() if line]
with ThreadPoolExecutor(max_workers=6) as pool:
    ready=[photo for photo in pool.map(prepare,specs) if photo]
assert len(ready)>=30,len(ready)
meta=assets/'photography.json'
info=json.loads(meta.read_text(encoding='utf8'))
info['scenes']=[row for row in info['scenes'] if not row['id'].startswith('px_')]+ready
meta.write_text(json.dumps(info,ensure_ascii=False),encoding='utf8')
print('Added',len(ready),'photographs.',flush=True)

java = root/'app/src/main/java/studio/tingye/reader/FieldArt.java'
code = java.read_text(encoding='utf8')
catalog = [row for row in info['scenes'] if (assets/'scenery'/row['id']/'photo.webp').is_file()]
for field,key in [('SCENES','id'),('SCENE_LABELS','title'),('SCENE_RUSSIAN','ru')]:
    pattern = r'  static final String\[\] '+field+r' = \{.*?\n  \};'
    values = '\n'.join('    '+json.dumps(row[key],ensure_ascii=False)+',' for row in catalog)
    code,count = re.subn(pattern,lambda _:'  static final String[] '+field+' = {\n'+values+'\n  };',code,flags=re.S)
    assert count==1,field
java.write_text(code,encoding='utf8')

