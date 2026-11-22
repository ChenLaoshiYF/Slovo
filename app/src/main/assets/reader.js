"use strict";
const app = document.querySelector("#app"),
  masthead = document.querySelector("#masthead"),
  nav = document.querySelector("#nav"),
  sheet = document.querySelector("#sheet");
const esc = (v) =>
  String(v == null ? "" : v).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const paths = {
  book: "M3 5q5-2 9 1 4-3 9-1v14q-5-2-9 1-4-3-9-1z M12 6v14",
  plus: "M12 4v16 M4 12h16",
  back: "m15 5-7 7 7 7",
  next: "m9 5 7 7-7 7",
  review: "M19 8a8 8 0 1 0 1 7 M19 3v5h-5 M12 8v5l3 2",
  terms: "M5 3h14v18l-7-4-7 4z",
  play: "m8 5 11 7-11 7z",
  stop: "M7 6h10v12H7z",
  ear: "M6 10a6 6 0 0 1 12 0c0 4-5 5-5 8a3 3 0 0 1-6 0 M9 10a3 3 0 0 1 6 0",
  edit: "m4 16-1 5 5-1L20 8l-4-4z",
  page: "M5 3h10l4 4v14H5z M8 12h8 M8 16h6",
};
const icon = (k) =>
  '<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="' +
  paths[k] +
  '"/></svg>';
const button = (act, body, cls = "", n = "") =>
  '<button class="' +
  cls +
  '" data-act="' +
  act +
  '" data-n="' +
  esc(n) +
  '">' +
  body +
  "</button>";
const art =
  '<svg class="art" viewBox="0 0 190 180" preserveAspectRatio="xMidYMid slice"><defs><pattern id="g" width="14" height="14" patternUnits="userSpaceOnUse"><path d="M14 0H0v14" fill="none" stroke="#8091a0" stroke-width=".5"/></pattern></defs><rect width="190" height="180" fill="url(#g)"/><g fill="none" stroke="#e2cd9e" stroke-width="1.6" transform="rotate(-11 110 90)"><path d="M40 53h24l4-7 6 14 6-14 6 14 6-14 5 7h30v53H96m-16 0H40z M88 94v24m7-21v18"/><circle cx="40" cy="80" r="12" fill="#29445b"/><path d="M34 80q3-8 6 0t6 0 M115 32h25m-5-4 5 4-5 4"/><circle cx="127" cy="53" r="3" fill="#edb66c"/></g></svg>';
const empty = {
  cards: {},
  corrections: {},
  positions: {},
  last: "demo",
  rate: 1,
  hide: false,
};
let saved;
try {
  saved = {
    ...empty,
    ...JSON.parse(localStorage.getItem("tingye.v1") || "{}"),
  };
} catch (error) {
  saved = empty;
}
const persist = () => localStorage.setItem("tingye.v1", JSON.stringify(saved));
const books = new Map();
let screen = "home",
  bookId = saved.last,
  playing = "",
  current = null,
  result = null,
  reviewCount = 0;
const book = () => books.get(bookId);
const key = (id, i) => id + ":" + i;
const root = (id) => (id === "demo" ? "demo/" : "books/" + id + "/");
const pad = (v) => String(v).padStart(2, "0");
const time = (s) => Math.floor(s / 60) + ":" + pad(Math.floor(s % 60));
const due = () =>
  Object.entries(saved.cards)
    .filter(([k, c]) => books.has(c.book) && c.due <= Date.now())
    .sort((a, b) => a[1].due - b[1].due);
const label = (c) => c.term || c.ru;
function translations(id, cue) {
  let text = cue.zh;
  for (const [old, value] of Object.entries(saved.corrections[id] || {})) {
    if (old && text.includes(old)) text = text.split(old).join(value);
  }
  return text;
}
const endAudio = () => {
  if (playing && window.Reader) Reader.stop();
  playing = "";
};
window.audioDone = () => {
  playing = "";
  syncAudio();
};
function syncAudio() {
  for (const el of document.querySelectorAll('[data-act="play"]')) {
    const on = playing === key(bookId, el.dataset.n);
    el.classList.toggle("active", on);
    el.innerHTML = icon(on ? "stop" : "play") + (on ? "停止" : "听原句");
  }
}
function header(back = false) {
  return (
    "<header>" +
    (back
      ? button("back", icon("back") + "课程", "back")
      : '<strong class="brand">课堂听读</strong>') +
    button("import", icon("plus") + "导入课程", "plain") +
    "</header>"
  );
}
function row(act, n, title, sub, sym) {
  return button(
    act,
    '<span class="tile">' +
      icon(sym) +
      '</span><span class="grow"><b>' +
      esc(title) +
      '</b><span class="meta">' +
      esc(sub) +
      '</span></span><span class="chev">' +
      icon("next") +
      "</span>",
    "row",
    n,
  );
}
function home() {
  const b = book();
  return (
    header() +
    '<div class="kicker">' +
    new Intl.DateTimeFormat("zh-CN", {
      month: "long",
      day: "numeric",
      weekday: "long",
    }).format(new Date()) +
    '</div><h1 class="title">我的书桌</h1><article class="cover">' +
    art +
    "<small>" +
    esc(b.course || "我的课程") +
    "</small><h2>" +
    esc(b.title) +
    "</h2><p>" +
    b.cues.length +
    " 段讲解 · " +
    time(b.cues[b.cues.length - 1].end) +
    "</p>" +
    button(
      "open",
      icon("play") +
        (saved.positions[bookId] != null ? "继续听读" : "开始听读"),
      "",
      bookId,
    ) +
    '</article><div class="section"><h2>今日复习</h2><span class="meta">间隔练习</span></div>' +
    button(
      "review",
      '<span class="tile">' +
        icon("review") +
        '</span><span class="grow"><b>' +
        (due().length ? due().length + " 项待练习" : "已完成今日练习") +
        '</b><span class="meta">听辨术语 · 应用概念</span></span>' +
        icon("next"),
      "row reviewrow",
    ) +
    '<div class="section"><h2>我的课程</h2><span class="meta">' +
    books.size +
    " 讲</span></div>" +
    [...books]
      .map(([id, b]) => row("open", id, b.title, b.course || "已导入", "book"))
      .join("")
  );
}
function lesson() {
  const b = book();
  return (
    header(true) +
    '<div class="lessonhead"><span class="smallcap">' +
    esc(b.course) +
    "</span><h1>" +
    esc(b.title) +
    '</h1><p class="meta">' +
    esc(b.source || "本机课程包") +
    '</p></div><div class="toolsbar">' +
    button("hide", saved.hide ? "显示译文" : "隐藏译文", "pill") +
    button("rate", saved.rate + "× 语速", "pill") +
    "</div>" +
    b.cues
      .map(
        (c, i) =>
          '<article class="cue" id="cue' +
          i +
          '"><div class="tools"><span class="stamp">' +
          pad(i + 1) +
          " / " +
          time(c.start) +
          "</span>" +
          button("play", icon("play") + "听原句", "play", i) +
          '</div><p lang="ru" class="ru">' +
          esc(c.ru) +
          "</p>" +
          (!saved.hide
            ? '<p class="zh">' + esc(translations(bookId, c)) + "</p>"
            : "") +
          '<div class="toolsbar">' +
          (c.page
            ? button("page", "讲义 p." + c.page, "link", i)
            : '<span class="meta">讲义未匹配</span>') +
          button(
            "save",
            saved.cards[key(bookId, i)] ? "已加入复习" : "加入复习",
            "plain",
            i,
          ) +
          (c.term ? button("edit", "校正术语", "plain", i) : "") +
          "</div></article>",
      )
      .join("")
  );
}
function pick() {
  current = due()[0] || null;
  result = null;
}
function nextDate() {
  const dates = Object.values(saved.cards).map((c) => c.due);
  if (!dates.length) return "";
  const dt = new Date(Math.min(...dates));
  return (
    "下次练习 " +
    new Intl.DateTimeFormat("zh-CN", {
      month: "numeric",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    }).format(dt)
  );
}
function review() {
  if (!current)
    return (
      header() +
      '<div class="kicker">听辨与应用</div><h1 class="title">复习</h1><div class="question"><h2>' +
      (reviewCount ? "本轮完成" : "今天的练习已完成") +
      '</h2><p class="note">' +
      (nextDate() || "打开课程，选择要复习的原句。") +
      "</p>" +
      button("home", "回到书桌", "primary") +
      "</div>"
    );
  const [k, card] = current;
  bookId = card.book;
  const c = book().cues[card.index],
    stage = card.skill === "apply" ? 1 : 0;
  let task =
    stage % 2 === 0 ? "听原句，选出它的意思" : "在新情境中应用这个概念";
  let prompt =
    stage % 2 === 0 ? "" : c.question || "回想原句的含义，再核对译文。";
  let options =
    stage % 2 === 0
      ? [
          translations(bookId, c),
          ...book()
            .cues.filter((x) => x !== c)
            .slice(0, 2)
            .map((x) => translations(bookId, x)),
        ]
      : c.options || [];
  options = [...new Set(options)];
  let answer = stage % 2 === 0 ? translations(bookId, c) : c.answer;
  if (options.length > 1) {
    const offset = (card.index + stage + 1) % options.length;
    options = options.slice(offset).concat(options.slice(0, offset));
  }
  window.choiceValues = options;
  window.choiceAnswer = answer;
  const noChoice = options.length < 2;
  return (
    header() +
    '<div class="kicker">' +
    esc(book().title) +
    '</div><h1 class="title">今日复习</h1><div class="question"><span class="smallcap">' +
    (stage % 2 === 0 ? "听辨" : "运用") +
    " · 还剩 " +
    due().length +
    " 项</span><h2>" +
    task +
    "</h2>" +
    (prompt ? '<p class="zh">' + esc(prompt) + "</p>" : "") +
    button("play", icon("play") + "听原句", "play", card.index) +
    (noChoice
      ? result
        ? '<p class="ru">' +
          esc(c.ru) +
          "</p><p>" +
          esc(translations(bookId, c)) +
          "</p>"
        : button("reveal", "核对原句和译文", "link")
      : options
          .map((v, i) =>
            button(
              "answer",
              esc(v),
              "option " +
                (result && v === answer
                  ? "good"
                  : result && i === result.index
                    ? "bad"
                    : ""),
              i,
            ),
          )
          .join("")) +
    (result
      ? '<p class="note">' +
        (noChoice
          ? "按回忆情况安排下一次练习。"
          : result.correct
            ? "答对了。"
            : "再听一次，回讲义核对。") +
        "</p>" +
        (c.page
          ? button("page", "查看原页 p." + c.page, "link", card.index)
          : "") +
        (noChoice
          ? button("grade", "记得", "primary", "1") +
            button("grade", "需要重听", "pill", "0")
          : button("next", "继续", "primary"))
      : "") +
    "</div>"
  );
}
function glossary() {
  return (
    header() +
    '<div class="kicker">随课程积累</div><h1 class="title">术语本</h1>' +
    [...books]
      .map(
        ([id, b]) =>
          '<div class="section"><h2>' +
          esc(b.title) +
          "</h2></div>" +
          b.cues
            .map((c, i) =>
              c.term
                ? '<div class="term">' +
                  button(
                    "term-edit",
                    icon("edit") + "校正",
                    "plain",
                    key(id, i),
                  ) +
                  '<p class="ru">' +
                  esc(label(c)) +
                  "</p><p>" +
                  esc((saved.corrections[id] || {})[c.meaning] || c.meaning) +
                  "</p></div>"
                : "",
            )
            .join(""),
      )
      .join("")
  );
}
let lastDrawnScreen = "", pageMotion;

const rounded = "main,nav,button,input,textarea,dialog,.cover,.tile,.reviewrow,.question,.tag,.row";
const nativePath = window.CSS && CSS.supports("clip-path",'path("M0 0H1V1Z")');
const svgNamespace = "http://www.w3.org/2000/svg";
const cornerArt = document.createElementNS(svgNamespace,"svg");
cornerArt.setAttribute("width","0");
cornerArt.setAttribute("height","0");
cornerArt.setAttribute("aria-hidden","true");
cornerArt.style.position = "absolute";
if(!nativePath) document.body.appendChild(cornerArt);
let nextCorner=0;

function contour(element) {
  const w = element.offsetWidth, h = element.offsetHeight;
  if (!w || !h) return;
  if (!element.dataset.corner)
    element.dataset.corner = parseFloat(getComputedStyle(element).borderTopLeftRadius) || "0";
  const d = Math.min(Number(element.dataset.corner)*1.22, w/2, h/2);
  if (!d) return;
  const a=.18*d, k=.36*d, m=.72*d;
  element.style.borderRadius = "0";
  const shape = 'M'+d+' 0 H'+(w-d)
    +' C'+(w-m)+' 0 '+(w-k)+' 0 '+(w-a)+' '+a
    +' C'+w+' '+k+' '+w+' '+m+' '+w+' '+d+' V'+(h-d)
    +' C'+w+' '+(h-m)+' '+w+' '+(h-k)+' '+(w-a)+' '+(h-a)
    +' C'+(w-k)+' '+h+' '+(w-m)+' '+h+' '+(w-d)+' '+h+' H'+d
    +' C'+m+' '+h+' '+k+' '+h+' '+a+' '+(h-a)
    +' C0 '+(h-k)+' 0 '+(h-m)+' 0 '+(h-d)+' V'+d
    +' C0 '+m+' 0 '+k+' '+a+' '+a
    +' C'+k+' 0 '+m+' 0 '+d+' 0 Z';
  if(nativePath) element.style.clipPath = 'path("'+shape+'")';
  else {
    let clip=document.getElementById(element.dataset.cornerId || "");
    if(!clip) {
      clip=document.createElementNS(svgNamespace,"clipPath");
      clip.id="reader-corner-"+nextCorner++;
      clip.appendChild(document.createElementNS(svgNamespace,"path"));
      cornerArt.appendChild(clip);
      element.dataset.cornerId=clip.id;
    }
    clip.firstChild.setAttribute("d",shape);
    element.style.clipPath="url(#"+clip.id+")";
  }
}

const cornerObserver = typeof ResizeObserver==="function"
    ? new ResizeObserver(entries => entries.forEach(entry => contour(entry.target))) : null;
function fitCorners() {
  if(cornerObserver)cornerObserver.disconnect();
  if(!nativePath)cornerArt.textContent="";
  document.querySelectorAll(rounded).forEach(view=>{
    contour(view);
    if(cornerObserver)cornerObserver.observe(view);
  });
}
if(!cornerObserver)window.addEventListener("resize",fitCorners);

function draw() {
  if (lastDrawnScreen !== screen && window.Reader) Reader.scene(screen);
  const movingPage = lastDrawnScreen && lastDrawnScreen !== screen;
  const pages = ["home", "lesson", "review", "terms"];
  const direction = Math.sign(pages.indexOf(screen) - pages.indexOf(lastDrawnScreen));
  lastDrawnScreen = screen;
  app.innerHTML =
    screen === "home"
      ? home()
      : screen === "lesson"
        ? lesson()
        : screen === "review"
          ? review()
          : glossary();
  const titleBar=app.querySelector("header");
  if(titleBar) {
    masthead.textContent="";
    masthead.appendChild(titleBar);
  }
  if (screen === "lesson")
    app.querySelectorAll(".cue .toolsbar").forEach((el, i) => {
      const c = book().cues[i];
      el.insertAdjacentHTML(
        "beforeend",
        button(
          "proof",
          c.reference && !((saved.annotations || {})[bookId] || {})[i]
            ? "核对参考译文"
            : "校对本句",
          "plain",
          i,
        ),
      );
    });
  nav.className = "tabs";
  nav.innerHTML = [
    ["home", "book", "书桌"],
    ["review", "review", "复习"],
    ["terms", "terms", "术语"],
  ]
    .map(([k, ic, t]) =>
      button(
        k,
        icon(ic) + t,
        screen === k || (screen === "lesson" && k === "home") ? "on" : "",
      ),
    )
    .join("");
  syncAudio();
  fitCorners();
  if (movingPage && window.matchMedia("(prefers-reduced-motion: no-preference)").matches &&
      typeof app.animate === "function" && Reader.motionEnabled()) {
    if (pageMotion) pageMotion.cancel();
    pageMotion = app.animate(
      [{transform: "translateX(" + direction * 6 + "px)"}, {transform: "translateX(0)"}],
      {duration: 240, easing: "cubic-bezier(.18,.78,.22,1)"});
  }
}
function to(view) {
  endAudio();
  screen = view;
  if (view === "review") {
    reviewCount = 0;
    pick();
  }
  draw();
  app.scrollTop=0;
}
function modal(title, html) {
  sheet.innerHTML =
    "<header><b>" +
    esc(title) +
    "</b>" +
    button("close", "×", "close") +
    "</header>" +
    html;
  sheet.showModal();
  fitCorners();
}
function grade(correct) {
  const card = current[1],
    intervals = card.skill === "apply" ? [2, 5, 12, 21, 40] : [1, 3, 7, 14, 30];
  if (correct) {
    card.due = Date.now() + 86400000 * intervals[Math.min(card.stage, 4)];
    card.stage++;
  } else {
    card.due = Date.now() + 600000;
    card.stage = Math.max(0, card.stage - 1);
    card.errors = (card.errors || 0) + 1;
  }
  card.last = Date.now();
  persist();
  endAudio();
  reviewCount++;
  pick();
  draw();
}
function addCard(id, i) {
  const k = key(id, i);
  if (!saved.cards[k])
    saved.cards[k] = {
      book: id,
      index: i,
      skill: "listen",
      stage: 0,
      due: Date.now(),
    };
  const cue = books.has(id) && books.get(id).cues[i];
  if (
    cue && cue.question &&
    cue.options && cue.options.includes(cue.answer) &&
    !saved.cards[k + ":apply"]
  )
    saved.cards[k + ":apply"] = {
      book: id,
      index: i,
      skill: "apply",
      stage: 0,
      due: Date.now(),
    };
}
function openBook(id) {
  bookId = id;
  saved.last = id;
  persist();
  to("lesson");
  const previous = document.getElementById("cue" + saved.positions[id]);
  if (previous) previous.scrollIntoView();
}
let editing = null;
document.addEventListener("click", (ev) => {
  const el = ev.target.closest("[data-act]");
  if (!el) return;
  if (window.Reader) window.Reader.tapFeedback();
  const a = el.dataset.act,
    n = el.dataset.n,
    i = Number(n);
  if (["home", "terms", "review"].includes(a)) {
    if (a === "home") bookId = saved.last;
    return to(a);
  }
  if (a === "open") return openBook(n);
  if (a === "back") return to("home");
  if (a === "close") return sheet.close();
  if (a === "import")
    return modal(
      "导入课程",
      '<p class="blank">选择用电脑端「听页制课」生成的课程包。原声、双语逐句稿和讲义页会一起保存在手机上。</p>' +
        button("choose", "选择课程包", "primary"),
    );
  if (a === "choose") {
    sheet.close();
    window.Reader && window.Reader.chooseBook();
    return;
  }
  if (a === "play") {
    const same = playing === key(bookId, i);
    endAudio();
    if (!same) {
      const c = book().cues[i];
      playing = key(bookId, i);
      if (screen === "lesson") {
        saved.positions[bookId] = i;
        persist();
      }
      window.Reader && window.Reader.play(bookId, c.start, c.end, saved.rate);
    }
    return syncAudio();
  }
  if (a === "hide") {
    saved.hide = !saved.hide;
    persist();
    return draw();
  }
  if (a === "rate") {
    const rates = [0.8, 1, 1.2];
    saved.rate = rates[(rates.indexOf(saved.rate) + 1) % 3];
    endAudio();
    persist();
    return draw();
  }
  if (a === "save") {
    addCard(bookId, i);
    persist();
    return draw();
  }
  if (a === "page") {
    const c = book().cues[i];
    return modal(
      "讲义 · 第 " + c.page + " 页",
      '<img class="source" alt="原讲义第 ' +
        c.page +
        ' 页" src="' +
        root(bookId) +
        "page" +
        Number(c.page) +
        '.png">',
    );
  }
  if (a === "proof") {
    const c = book().cues[i],
      max =
        book().pages ||
        Math.max(...book().cues.map((x) => Number(x.page) || 0));
    const ref = c.reference
      ? '<p class="note"><strong>课程参考句</strong><br>' +
        esc(c.reference.ru) +
        "<br>" +
        esc(c.reference.zh) +
        "</p>" +
        button("adopt", "填入参考句", "link", i)
      : "";
    return modal(
      "校对本句",
      ref +
        '<label>俄语原句<textarea id="proof-ru" maxlength="2000">' +
        esc(c.ru) +
        '</textarea></label><label>中文译文<textarea id="proof-zh" maxlength="2000">' +
        esc(c.zh) +
        '</textarea></label><label>讲义页码（0 表示未匹配）<input type="number" id="proof-page" min="0" max="' +
        max +
        '" value="' +
        Number(c.page || 0) +
        '"></label><details><summary>查看导入时的原稿</summary><p>' +
        esc(c.original.ru) +
        "</p><p>" +
        esc(c.original.zh) +
        "</p></details>" +
        button("save-proof", "保存校对", "primary", i),
    );
  }
  if (a === "adopt") {
    const ref = book().cues[i].reference;
    document.querySelector("#proof-ru").value = ref.ru;
    document.querySelector("#proof-zh").value = ref.zh;
    return;
  }
  if (a === "save-proof") {
    const field = sheet.querySelector("#proof-page"),
      ru = sheet.querySelector("#proof-ru").value.trim(),
      zh = sheet.querySelector("#proof-zh").value.trim();
    if (!ru || !zh || !field.reportValidity()) return;
    const update = { ru, zh, page: Number(field.value) };
    if (!saved.annotations) saved.annotations = {};
    if (!saved.annotations[bookId]) saved.annotations[bookId] = {};
    saved.annotations[bookId][i] = update;
    Object.assign(book().cues[i], update);
    persist();
    sheet.close();
    draw();
    return;
  }
  if (a === "edit" || a === "term-edit") {
    const parts = a === "edit" ? [bookId, i] : n.split(":");
    const c = books.get(parts[0]).cues[+parts[1]];
    editing = [parts[0], c.meaning];
    return modal(
      "校正术语",
      '<p class="ru">' +
        esc(c.term) +
        '</p><label>本课程统一译为<input id="newterm" maxlength="60" value="' +
        esc((saved.corrections[parts[0]] || {})[c.meaning] || c.meaning) +
        '"></label>' +
        button("commit", "保存译名", "primary"),
    );
  }
  if (a === "commit") {
    const value = document.querySelector("#newterm").value.trim();
    if (!value) return;
    const [id, orig] = editing;
    if (!saved.corrections[id]) saved.corrections[id] = {};
    saved.corrections[id][orig] = value;
    persist();
    sheet.close();
    return draw();
  }
  if (a === "answer" && !result) {
    result = {
      index: i,
      correct: window.choiceValues[i] === window.choiceAnswer,
    };
    return draw();
  }
  if (a === "reveal") {
    result = {};
    return draw();
  }
  if (a === "grade" && current) return grade(n === "1");
  if (a === "next" && result) return grade(result.correct);
});
window.goBack = () => {
  if (sheet.open) {
    sheet.close();
    return true;
  }
  if (screen !== "home") {
    to("home");
    return true;
  }
  return false;
};
function annotate(id, lesson) {
  const updates = (saved.annotations || {})[id] || {};
  lesson.cues.forEach((c, i) => {
    c.original = { ru: c.ru, zh: c.zh, page: c.page };
    if (updates[i]) Object.assign(c, updates[i]);
  });
  return lesson;
}
async function load(id) {
  const response = await fetch(root(id) + "lesson.json");
  if (!response.ok) throw Error("lesson");
  books.set(id, annotate(id, await response.json()));
}
window.imported = async (id) => {
  await load(id);
  openBook(id);
};
(async () => {
  await load("demo");
  for (const id of JSON.parse(window.Reader && window.Reader.libraryIds() || "[]")) {
    try {
      await load(id);
    } catch (error) {}
  }
  if (!books.has(bookId)) bookId = "demo";
  if (!saved.started) {
    addCard("demo", 0);
    addCard("demo", 1);
    saved.started = true;
    persist();
  }
  draw();
})().catch(() => (app.textContent = "课程读取失败，请重新打开课堂听读。"));
