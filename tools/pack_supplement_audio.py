"""Make the local supplemental MP3 library from the Russian SAPI voice on the build computer."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json
import subprocess
import sys
import wave
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
CACHE=Path(sys.argv[1]).resolve()
CACHE.mkdir(parents=True, exist_ok=True)
SYNTHESIZER=ROOT/'tools/speak_supplements.ps1'

def produce_wave(words, number):
    manifest=CACHE/f'part{number}.tsv'
    manifest.write_text(''.join(f'{k}\t{v[0]}\n' for k,v in words),encoding='utf8')
    subprocess.run(['pwsh','-NoProfile','-NonInteractive','-File',str(SYNTHESIZER),
                    str(manifest),str(CACHE)],check=True)
    print('Recorded',number,len(words),'words',flush=True)

def mp3(key):
    path=CACHE/f'{key}.wav'
    with wave.open(str(path),'rb') as voice:
        channels=voice.getnchannels()
        rate=voice.getframerate()
        assert voice.getsampwidth()==2
        pcm=np.frombuffer(voice.readframes(voice.getnframes()),dtype='<i2')
        present=np.nonzero(np.abs(pcm.astype(np.int32))>100)[0]
        assert present.size>0,key
        left=max(0,int(present[0])-(rate*channels//36))
        right=min(pcm.size,int(present[-1])+(rate*channels//10))
        pcm=pcm[(left//channels)*channels:((right//channels)*channels)].tobytes()
    try:import lameenc
    except ImportError:
        return subprocess.check_output(['ffmpeg','-nostdin','-loglevel','error','-i',str(path),
                            '-af','silenceremove=start_periods=1:start_duration=0.01:start_threshold=-50dB',
                            '-ar','22050','-ac','1','-b:a','32k','-f','mp3','pipe:1'])
    encoder=lameenc.Encoder()
    encoder.set_in_sample_rate(rate)
    encoder.set_out_sample_rate(22050)
    encoder.set_channels(channels)
    encoder.set_bit_rate(32)
    encoder.set_quality(4)
    return bytes(encoder.encode(pcm))+bytes(encoder.flush())

data=json.loads((ASSETS/'supplements.json').read_text(encoding='utf8'))
items=list(data.items())
with ThreadPoolExecutor(6) as pool:
    jobs=[pool.submit(produce_wave,items[i::6],i) for i in range(6)]
    for job in jobs:job.result()
offset=0
index=[]
with (ASSETS/'voices-supplements.tmp').open('wb') as pack:
    with ThreadPoolExecutor(6) as pool:
        for k,content in zip(data,pool.map(mp3,data)):
            assert 200<len(content)<100000,(k,len(content))
            pack.write(content)
            index.append(f'{k}\t{offset}\t{len(content)}\n')
            offset+=len(content)
(ASSETS/'voices-supplements.tmp').replace(ASSETS/'voices-supplements.bin')
(ASSETS/'voices-supplements.tsv').write_text(''.join(index),encoding='utf8')
print('PASS',len(index),'offline extra clips, packed bytes',offset,flush=True)

