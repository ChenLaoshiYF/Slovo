"""Package Russian audio and selectable-text PDF pages for the offline Tingye reader."""
import argparse
import collections
from difflib import SequenceMatcher
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

def stems(text):
    words = re.findall(r"[а-яё]{3,}", text.lower())
    stop = {"который","также","этот","такой","всего","если","того","при","для","или","как","это","что","чтобы","этого","равна"}
    return {word[:max(4,min(7,len(word)-2))] for word in words if word not in stop}

def align(cues, pages):
    vocab = [stems(page) for page in pages]
    freq = collections.Counter(word for words in vocab for word in words)
    previous = None
    for cue in cues:
        scores = []
        query = stems(cue["ru"])
        for index, words in enumerate(vocab):
            common = query & words
            score = sum(1 + math.log((len(pages)+1)/(1+freq[word])) for word in common)
            if previous is not None and common:
                score += .25 / (1+abs(index-previous))
            scores.append((score,len(common),index))
        score, hits, best = max(scores,default=(0,0,0))
        if hits >= 2 or (hits and len(query)<=2):
            cue["page"] = best+1
            previous = best
        else:
            cue["page"] = 0

def transcribe(audio, model_root):
    import torch
    from faster_whisper import WhisperModel
    from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
    device = "cuda" if torch.cuda.is_available() else "cpu"
    speech_path=str(model_root/"faster-whisper-large-v3-turbo")
    def recognize(device):
        model=WhisperModel(speech_path, device=device,compute_type="float16" if device=="cuda" else "int8",local_files_only=True)
        segments,_=model.transcribe(str(audio),language="ru",beam_size=3,condition_on_previous_text=False)
        return [dict(ru=s.text.strip(),start=round(s.start,3),end=round(s.end,3)) for s in segments if s.text.strip() and s.end>s.start]
    print("识别俄语录音…",flush=True)
    try:cues=recognize(device)
    except RuntimeError:
        if device=="cpu":raise
        cues=recognize("cpu")
    if not cues:raise ValueError("没有识别到俄语语音")
    if len(cues)>3000:raise ValueError("请把长录音分成数讲后再导入")
    print("生成中文初译…",flush=True)
    path=str(model_root/"nllb-200-distilled-600M")
    tok=AutoTokenizer.from_pretrained(path,src_lang="rus_Cyrl",local_files_only=True)
    model=AutoModelForSeq2SeqLM.from_pretrained(path,local_files_only=True).to(device).eval()
    for i in range(0,len(cues),6):
        batch=cues[i:i+6]
        encoded=tok([c["ru"] for c in batch],return_tensors="pt",truncation=True,max_length=384,padding=True).to(device)
        with torch.inference_mode():
            out=model.generate(**encoded,forced_bos_token_id=tok.convert_tokens_to_ids("zho_Hans"),max_new_tokens=150,num_beams=2)
        for c,translation in zip(batch,tok.batch_decode(out,skip_special_tokens=True)):c["zh"]=translation.strip()
    return cues

def make(audio, pdf, target, title, model_root, glossary=None):
    import fitz
    with tempfile.TemporaryDirectory() as name:
        stage=Path(name)
        subprocess.run(["ffmpeg","-loglevel","error","-y","-i",str(audio),"-vn","-ac","1","-ar","24000","-b:a","80k",str(stage/"audio.mp3")],check=True)
        cues=transcribe(stage/"audio.mp3",model_root)
        with fitz.open(pdf) as doc:
            if not 1<=len(doc)<=450:raise ValueError("每讲支持 1 至 450 页 PDF")
            pages=[page.get_text() for page in doc]
            align(cues,pages)
            for n in range(1,len(doc)+1):
                page=doc[n-1]
                page.get_pixmap(matrix=fitz.Matrix(1100/page.rect.width,1100/page.rect.width),alpha=False).save(stage/f"page{n}.png")
        if glossary:
            entries=json.loads(Path(glossary).read_text(encoding="utf-8-sig"))
            for cue in cues:
                def words(text):
                    return " ".join(re.findall(r"[а-яё0-9]+",text.lower()))
                candidates=sorted(((SequenceMatcher(None,words(cue["ru"]),words(e["ru"]),autojunk=False).ratio(),j) for j,e in enumerate(entries) if e.get("ru") and e.get("zh")),reverse=True)
                if candidates and candidates[0][0]>=.86 and (len(candidates)==1 or candidates[0][0]-candidates[1][0]>=.06):
                    ref=entries[candidates[0][1]]
                    cue["reference"]={"ru":ref["ru"],"zh":ref["zh"]}
                    for field in ("term","meaning","options","question","answer"):
                        if field in ref:cue[field]=ref[field]
                    continue
                for term in entries:
                    if term["term"].lower() in cue["ru"].lower() and term["meaning"] in cue["zh"]:
                        cue.update(term=term["term"],meaning=term["meaning"])
                        if term.get("question") and term.get("answer") in term.get("options",[]):cue.update({k:term[k] for k in ("question","options","answer")})
                        break
        lesson=dict(title=title,course="导入课程",source="本地识别及初译 · 页码按俄文匹配",pages=len(pages),cues=cues)
        (stage/"lesson.json").write_text(json.dumps(lesson,ensure_ascii=False),encoding="utf-8")
        target=Path(target);target.parent.mkdir(parents=True,exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=target.parent,suffix=".part",delete=False) as tmp:tmp_path=Path(tmp.name)
        try:
            with zipfile.ZipFile(tmp_path,"w",compression=zipfile.ZIP_DEFLATED) as pack:
                for file in stage.iterdir():pack.write(file,file.name)
            if sum(f.stat().st_size for f in stage.iterdir())>512*1024**2:raise ValueError("课程包过大，请将录音分讲")
            tmp_path.replace(target)
        finally:tmp_path.unlink(missing_ok=True)
        print("课程已生成",target,flush=True)

def main():
    p=argparse.ArgumentParser()
    for option in ("audio","pdf","output","title","glossary"):p.add_argument("--"+option)
    default=Path.home()/"Desktop/QingOH-WorkSpace/projects/俄汉同传/models"
    p.add_argument("--models",default=os.environ.get("TINGYE_MODELS",str(default)));a=p.parse_args()
    if not all([a.audio,a.pdf,a.output]):
        import tkinter as tk
        from tkinter import filedialog as dialog
        root=tk.Tk();root.withdraw()
        a.audio=dialog.askopenfilename(title="选择俄语课堂录音")
        if not a.audio:return
        a.pdf=dialog.askopenfilename(title="选择有俄文文本层的讲义",filetypes=[("PDF","*.pdf")])
        if not a.pdf:return
        a.output=dialog.asksaveasfilename(title="保存听页课程包",defaultextension=".tingye",initialfile=Path(a.pdf).stem+".tingye")
        if not a.output:return
    make(a.audio,a.pdf,a.output,a.title or Path(a.pdf).stem,Path(a.models),a.glossary)
if __name__=="__main__":main()
