"""Build graded supplemental volumes from licensed Russian dictionary and frequency data.

Usage: python expand_shelves.py DIRECTORY_OF_OPENRUSSIAN_CSVS
Run prepare_volumes.py first. The directory holds openrussian-{nouns,verbs,adjectives,others}.csv
from Badestrand/russian-dictionary. Install wordfreq==3.1.1 on the build computer.
"""
from collections import Counter
import csv
import gzip
import json
from pathlib import Path
import re
import sqlite3
import sys
from wordfreq import top_n_list
import pymorphy3

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
# Content bands are editorial parameters, not official examination or CEFR assignments.
# Select topical words first, then use the remaining space for common words of the same level.
VOLUMES={
 'travel': (420,1,3500,'旅行 交通 出发 到达 地点 地图 车站 方向 机场 飞机 酒店 住宿 票 公交 地铁 街道 城市 过夜 房间 通行'),
 'travel_2': (535,100,5600,'购物 食物 饮料 餐馆 烹饪 市场 购买 付款 面包 水果 衣服 价格 邮局 物品 商店 营业 服务 退货'),
 'travel_3': (625,850,8200,'旅行 旅游 游览 景点 博物馆 建筑 王宫 教堂 接待 路线 出境 证件 意外 错过 航班 急救 户外 地理'),
 'travel_4': (780,1800,12000,'租赁 住房 合同 押金 维修 工作 办理 预约 证明 手续 医疗 支付 保险 报销 使用 登记 雇用 缴纳 银行 账单'),
 'university': (460,1,3500,'教学 学习 读书 写字 教室 家庭 职业 学生 语言 学校 人 时间 日常 课堂'),
 'university_2': (570,400,5600,'日常 房屋 家具 住处 家庭 工具 清洗 穿着 房间 做饭 睡眠 友谊 饮食 打扫 起床'),
 'university_3': (650,900,7000,'成长 心理 感情 人际 学校 青年 友谊 交谈 心情 情绪 观点 学习 表达 关系 个人 性格'),
 'university_4': (725,1450,8600,'社会 生产 教育 劳动 金融 公司 价格 经济 技术 行业 就业 政策 商业 收入 政府 发展 区域'),
 'university_5': (810,1900,10700,'文学 文化 作品 劇 剧 诗 音乐 绘画 美术 表演 艺术 民俗 创作 语言 小说 角色 观众 建筑 电影'),
 'university_6': (880,2300,12900,'政治 法律 历史 行政 国家 社会 公共 政府 权力 制度 新闻 报纸 媒体 传播 条例 机构 地区'),
 'university_7': (960,3000,15100,'语言 文字 翻译 词汇 语法 语音 书面 文章 写作 文献 语言学 作者 文体 意义 形式 文本'),
 'university_8': (1070,3700,18400,'研究 实验 理论 学术 统计 数据 证据 分析 技术 逻辑 科学 合作 外交 国际 组织 生产 协议'),
 'modern_1': (490,1,4200,'生活 信息 家庭 商店 工作 时间 交通 出行 对话 手机 采访 朋友 通信 客人 问题 吃饭 社交'),
 'modern_2': (665,800,8400,'媒介 信息 联系 同事 合作 项目 计算 技术 沟通 组织 资料 数据 技术 工作 网络 演示 演讲 国家 互动'),
 'school_1': (355,1,3200,'问候 校园 同学 人 家庭 人物 颜色 文具 动物 日常 水果 衣服 天气 时间 数字'),
 'school_2': (520,400,5600,'运动 比赛 训练 户外 音乐 爱好 旅行 球 休闲 周末 照相 假日 参观 日程 自然'),
 'school': (1620,1,8000,'生活 自然 教育 科学 文化 健康 保护 动物 社会 成长 季节 购物 劳动 规划 新闻 环境 阅读'),
 'four': (2425,450,14000,'社会 文化 公益 学习 经济 变化 人口 职业 合作 环境 制度 发展 比较 科学 报告 组织'),
 'four_read': (730,700,11000,'语言 写作 文体 阅读 描述 叙述 修辞 文章 语法 列举 比较 说明 内容 背景 逻辑 句子 论点'),
 'six': (3240,1700,24000,'科技 传媒 国情 经济 人文 政策 分析 国际 趋势 制度 研究 哲学 法律 心理 学术'),
 'six_read': (980,2300,22000,'思想 原理 推论 争议 标准 理论 内容 视角 观点 比较 语用 逻辑 模型 假设 立场 结构 正式 表达'),
}
EXCLUDED=re.compile(r'古俄语|方言词|污言|粗俗|猥亵|脏话|侮辱性|操你|鸡巴|婊子|混账|同义词：|本词条|维基|仅用作|转写自')
TABOO=re.compile(r'^(?:хер|хрен|хуй|пизд|еба|ёба|бля|сука$|мудак|жоп|дерьм|гандон|нахер|похер|охуе|лох$|дура$|фифа$|пли$)')
SOURCE_FLAGS=re.compile(r'\b(?:vulgar|obscene|offensive|derogatory|archaic|dated|obsolete)\b',re.I)
CYRILLIC=re.compile('[а-яё]+(?:-[а-яё]+)?$',re.I)
WORD_TYPE={'nouns':'名词','verbs':'动词','adjectives':'形容词'}
SUPPORTED={'NOUN','INFN','ADJF','ADVB','CONJ','PREP','NPRO','PRCL','INTJ','NUMR'}
SPECIAL={'Name','Surn','Patr','Geox','Orgn','Abbr','Arch'}
def key(word):return word.lower().replace('ё','е').replace('\u0301','')

def clean_definition(value):
    value=re.sub(r'\s+',' ',value).strip(' ；。/')
    if not 1<=len(value)<=145 or not re.search('[\u3400-\u9fff]',value):return ''
    if EXCLUDED.search(value) or '<' in value or '[' in value or '►' in value:return ''
    # Wiktionary entries explicitly pointing to an inflected form are not headwords.
    if re.search('^(参见|见下|见 |复数形式|变格形式|变位形式|另一种拼写|替代形式|旧式拼法)',value):return ''
    if re.search(r'[а-яё].{0,72}的(?:屈折|工具格|宾格|属格|与格|主格|前置格|单数|复数)', value, re.I):
        return ''
    if value.startswith('（') and value.endswith('）') and len(value)<12:return ''
    return value

def load_dictionary():
    with sqlite3.connect(':memory:') as db:
        db.deserialize(gzip.decompress((ASSETS/'lexicon.bin').read_bytes()))
        return {row[0]:row[1:] for row in db.execute('select k,ru,zh,pos from lex')}

def load_source(source, ranks, chinese):
    found={}
    for part in ('nouns','verbs','adjectives','others'):
        with (source/f'openrussian-{part}.csv').open(encoding='utf-8-sig',newline='') as stream:
            for item in csv.DictReader(stream,delimiter='\t'):
                bare=item['bare'].strip()
                k=key(bare)
                if k in found or k not in ranks or k not in chinese or not CYRILLIC.fullmatch(bare):continue
                original,translation,pos=chinese[k]
                if not 2<=len(k)<=26 or bare[0].isupper() or TABOO.search(bare):continue
                if SOURCE_FLAGS.search(item['translations_en']):continue
                gloss=clean_definition(translation)
                if not gloss or pos=='专名':continue
                stress=item['accented'].strip()
                if key(stress)!=k or (not '\u0301' in stress and 'ё' not in stress):stress=''
                found[k]=(bare,gloss,pos or WORD_TYPE.get(part,''),ranks[k],stress)
    primary = len(found)
    morph = pymorphy3.MorphAnalyzer()
    for k, (russian, translation, pos) in chinese.items():
        if k in found or k not in ranks or not 2<=len(k)<=26:continue
        if not CYRILLIC.fullmatch(russian) or russian[0].isupper() or pos=='专名' or TABOO.search(russian):continue
        gloss=clean_definition(translation)
        if not gloss:continue
        forms=morph.parse(russian)
        if not any(form.is_known and key(form.normal_form)==k and form.tag.POS in SUPPORTED
                   and not SPECIAL.intersection(form.tag.grammemes) for form in forms):continue
        found[k]=(russian,gloss,pos,ranks[k],'')
    print('OpenRussian matches',primary,'and independently validated dictionary lemmas',len(found)-primary)
    return found

def choose(book, pool, assigned, frequency, core):
    amount, low, high, theme=VOLUMES[book['key']]
    terms=theme.split()
    eligible=[]
    for k,(_,gloss,_,rank,_) in pool.items():
        if k in assigned or k in book['words'] or k in core:continue
        if rank>high+7000:continue
        score=sum(term in gloss for term in terms)
        in_band=low<=rank<=high
        distance=0 if in_band else min(abs(rank-low),abs(rank-high))
        eligible.append((k,rank,score,distance))
    def by_topic(r):
        return (r[3]//400,-min(2,r[2]),r[1]+frequency[r[0]]*40,r[0])
    def by_general(r):
        return (r[3]//400,r[1]+frequency[r[0]]*55,r[0])
    need=amount-len(book['words'])
    themed=sorted((r for r in eligible if r[2] and r[3]<300),key=by_topic)[:round(need*.55)]
    seen={r[0] for r in themed}
    extra=themed+sorted((r for r in eligible if r[0] not in seen),key=by_general)[:need-len(themed)]
    if len(extra)!=need:raise ValueError((book['key'],need,len(extra),len(eligible)))
    # The learning order puts the topic vocabulary before the broader graded supplement.
    extra.sort(key=lambda record:(record[0] not in seen,record[1],record[0]))
    book['themeCount']=len(book['words'])+len(themed)
    book['words'].extend(r[0] for r in extra)
    frequency.update(r[0] for r in extra)
    assigned.update(r[0] for r in extra)
    median=sorted(record[1] for record in extra)[len(extra)//2]
    print(book['key'],len(book['words']),'topics',book['themeCount'],'median rank',median,
          'sample',', '.join(r[0] for r in extra[::max(1,len(extra)//7)]),flush=True)
    return [r[0] for r in extra]

def main():
    source=Path(sys.argv[1])
    shelf_file=ASSETS/'shelves.json'
    data=json.loads(shelf_file.read_text(encoding='utf8'))
    supplemental_file=ASSETS/'supplements.json'
    previous=json.loads(supplemental_file.read_text(encoding='utf8')) if supplemental_file.exists() else {}
    for book in data['books']:
        book['words']=[k for k in book['words'] if k not in previous]
    core=set(data['words'])
    ranks={key(word):i for i,word in reversed(list(enumerate(top_n_list('ru',315000),1)))}
    pool=load_source(source,ranks,load_dictionary())
    print('Validated headword pool:',len(pool),flush=True)
    assert len(pool)>6700
    ordered=sorted(pool.items(),key=lambda entry:(entry[1][3],entry[0]))
    pool={key:(ru,zh,pos,i,stress) for i,(key,(ru,zh,pos,_,stress)) in enumerate(ordered,1)}
    use=Counter()
    added=set()
    reserved={s:set() for s in {b['series'] for b in data['books']}}
    for book in data['books']:reserved[book['series']].update(book['words'])
    # Keep the series distinct and reserve themed exam and school volumes before the broad surveys.
    order=sorted(data['books'],key=lambda book:(
        book['key'] in ('school','four','six'),
        VOLUMES[book['key']][1], VOLUMES[book['key']][2]))
    for book in order:
        added.update(choose(book,pool,reserved[book['series']],use,core))
    supplements={k:[*pool[k][:3],pool[k][4]] for k in sorted(added)}
    supplemental_file.write_text(json.dumps(supplements,ensure_ascii=False,separators=(',',':')),encoding='utf8')
    shelf_file.write_text(json.dumps(data,ensure_ascii=False,separators=(',',':')),encoding='utf8')
    print('PASS',len(data['books']),'books',sum(len(b['words']) for b in data['books']),'memberships',
          len(core|added),'global headwords',len(added),'supplements',flush=True)
if __name__=='__main__':main()

