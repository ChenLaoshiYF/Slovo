"""Fit a rank-8 translation adapter on the shipped quantized base and bundled parallel corpus.
Usage: python tools/train_translation.py BASE.gguf CONFIG_DIR OUTPUT.gguf
Requires torch, transformers, tokenizers and gguf (llama.cpp b7371 gguf-py).
"""
import json
import random
import sys
from pathlib import Path

from zhconv import convert
import gguf
import torch
from tokenizers import Regex, Tokenizer, models, pre_tokenizers
from transformers import Qwen3Config, Qwen3ForCausalLM

root = Path(__file__).resolve().parents[1]
base, config, output = sys.argv[1:]
rng = random.Random(947)
torch.manual_seed(947)
reader = gguf.GGUFReader(base)
def strings(key):
    field = reader.fields[key]
    return [bytes(field.parts[i]).decode() for i in field.data]

vocab = {token: i for i, token in enumerate(strings("tokenizer.ggml.tokens"))}
tok = Tokenizer(models.BPE(vocab, [tuple(m.split(" ", 1)) for m in strings("tokenizer.ggml.merges")]))
tok.pre_tokenizer = pre_tokenizers.Sequence([
    pre_tokenizers.Split(Regex(r"(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"), behavior="isolated"),
    pre_tokenizers.ByteLevel(add_prefix_space=False, use_regex=False),
])
tok.add_special_tokens(["<|im_start|>", "<|im_end|>", "<think>", "</think>"])
cfg = Qwen3Config.from_pretrained(config, local_files_only=True)
with torch.device("meta"):
    model = Qwen3ForCausalLM(cfg)
model.to_empty(device="cuda")
model.to(dtype=torch.bfloat16)
names = dict(attn_k="self_attn.k_proj", attn_q="self_attn.q_proj", attn_v="self_attn.v_proj",
             attn_output="self_attn.o_proj", attn_norm="input_layernorm", ffn_norm="post_attention_layernorm",
             attn_q_norm="self_attn.q_norm", attn_k_norm="self_attn.k_norm",
             ffn_down="mlp.down_proj", ffn_gate="mlp.gate_proj", ffn_up="mlp.up_proj")
params = dict(model.named_parameters())
with torch.no_grad():
    for t in reader.tensors:
        if t.name.startswith("blk."):
            _, i, name, _ = t.name.split(".")
            target = "model.layers."+i+"."+names[name]+".weight"
        else:
            target = {"token_embd.weight":"model.embed_tokens.weight","output_norm.weight":"model.norm.weight"}[t.name]
        params[target].copy_(torch.from_numpy(gguf.dequantize(t.data,t.tensor_type).copy()))
    model.tie_weights()
    # Recreate non-persistent rotary buffers after meta-device initialization.
    rot = type(model.model.rotary_emb)(config=cfg,device="cuda")
    model.model.rotary_emb = rot
model.requires_grad_(False)
class Adapt(torch.nn.Module):
    def __init__(self, original):
        super().__init__()
        self.original = original
        self.a = torch.nn.Parameter(torch.empty(8, original.in_features,device="cuda"))
        torch.nn.init.kaiming_uniform_(self.a,a=5**.5)
        self.b = torch.nn.Parameter(torch.zeros(original.out_features,8,device="cuda"))
    def forward(self, x):
        return self.original(x) + ((x.float() @ self.a.T) @ self.b.T).to(x.dtype)

adapters = {}
for i in range(cfg.num_hidden_layers-8,cfg.num_hidden_layers):
    attn = model.model.layers[i].self_attn
    for name in ("v","o"):
        attr = name+"_proj"
        layer = Adapt(getattr(attn,attr))
        setattr(attn,attr,layer)
        adapters[f"blk.{i}.attn_{'output' if name=='o' else 'v'}.weight"] = layer
corpus = json.loads((root/"app/src/main/assets/photo_sentences.json").read_text(encoding="utf8"))
pairs = [(ru[0].upper()+ru[1:]+("?" if v[0].endswith(("？","?")) else "."), convert(v[0],"zh-cn")) for ru,v in corpus.items() if 8<len(ru)<100 and 3<len(v[0])<60]
rng.shuffle(pairs)
instruction = "中文译成俄语，俄文译成中文。忠实保留否定、人名、数字、时态和体。只输出译文。 /no_think"
def sample(pair, reverse):
    ru,zh = pair
    src,tgt = (zh,ru) if reverse else (ru,zh)
    prefix = tok.encode("<|im_start|>system\n"+instruction+"<|im_end|>\n<|im_start|>user\n"+src+"<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n").ids
    end = tok.encode(tgt+"<|im_end|>").ids
    return prefix+end, [-100]*len(prefix)+end
def loss(batch):
    seq, labels = zip(*batch)
    n = max(map(len, seq))
    ids=torch.tensor([s+[151643]*(n-len(s)) for s in seq],device="cuda")
    labs=torch.tensor([s+[-100]*(n-len(s)) for s in labels],device="cuda")
    return model(input_ids=ids,labels=labs,use_cache=False).loss
valid=[sample(p,d) for p in pairs[:32] for d in (False,True)]
def validate():
    with torch.no_grad():
        return sum(loss(valid[i:i+4]).item() for i in range(0,len(valid),4))/16
# Contrastive parallel pairs exercise calendar anchors, negation and numeral agreement.
anchors=[]
days=["понедельник","вторник","среду","четверг","пятницу","субботу","воскресенье"]
for i,day in enumerate(days):
    when=("во " if i==1 else "в ")+day
    zh="星期"+"一二三四五六日"[i]
    for ru,cn in [("У нас занятие","我们有课"),("Я не приду","我不来"),("Мы встречаемся","我们见面"),("Он не работает","他不上班")]:
        anchors.append((ru+" "+when+".", cn[:1]+zh+cn[1:]+"。" if cn[0] in "我他" and not cn.startswith("我们") else "我们"+zh+cn[2:]+"。"))
for n,cn in enumerate("一两三四五",1):
    for noun,count,forms,gender in [
        ("票","张",["билет","билета","билетов"],"m"),
        ("书","本",["книга","книги","книг"],"f"),
        ("词典","本",["словарь","словаря","словарей"],"m"),
        ("座位","个",["место","места","мест"],"n")]:
        num=({"m":"один","f":"одна","n":"одно"}[gender] if n==1 else ("две" if gender=="f" else "два") if n==2 else ["три","четыре","пять"][n-3])
        ru=num+" "+forms[0 if n==1 else 2 if n==5 else 1]
        anchors.append(("У нас есть "+ru+".","我们有"+cn+count+noun+"。"))
        anchors.append(("Здесь "+ru+".","这里有"+cn+count+noun+"。"))
before=validate()
opt=torch.optim.AdamW([p for p in model.parameters() if p.requires_grad],lr=.00045)
for step in range(320):
    batch=[sample(p,(step+j)%2) for j,p in enumerate(pairs[32+4*step:36+4*step])]
    if step%2==0: batch.append(sample(rng.choice(anchors), step%4==0))
    opt.zero_grad(); value=loss(batch); value.backward()
    torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad],1.)
    opt.step()
    if step%40==0: print("step",step,value.item(),flush=True)
after=validate()
print("held-out NLL",before,after,flush=True)
if after >= before:raise RuntimeError("Held-out translation loss did not improve")
writer=gguf.GGUFWriter(output,"qwen3")
writer.add_type("adapter");writer.add_string("adapter.type","lora");writer.add_float32("adapter.lora.alpha",8.)
for name,layer in adapters.items():
    for suffix,param in (("a",layer.a),("b",layer.b)):
        writer.add_tensor(name+".lora_"+suffix,param.detach().half().cpu().numpy())
writer.write_header_to_file(); writer.write_kv_data_to_file(); writer.write_tensors_to_file(); writer.close()

