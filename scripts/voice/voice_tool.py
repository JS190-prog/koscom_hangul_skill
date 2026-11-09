#!/usr/bin/env python3
"""코스콤 한글 보고서 스킬의 문체 도구 (표준 라이브러리 + 스킬에 든 hwplib, 결정론).

gongnyang/gn-voice 의 방식(서식을 걷어낸 본문 -> 문체 지표 -> 밴드 -> 스크립트 검사)을 대표 문서 2~3건으로도
되게 줄인 kosoe-office 의 voice_tool.py 를 가져와 두 가지를 바꿨다.
  1. 본문 읽기: KorDoc 대신 이 스킬의 hwplib(.hwp) · 표준 라이브러리(.hwpx) · report.json 을 직접 읽는다.
  2. 문체 분석: 대표 문서의 끝맺음·끝말·라벨·기호·숫자 표기·자주 쓴 표현·예문을 재서 style-guide.md 로 쓴다.
gn-voice 저자의 코퍼스·지문·장르 팩 데이터는 쓰지 않는다. 방식만 참고했다.

  build    대표 문서 -> 문체 폴더 (style-guide.md · style-profile.json · fingerprint-slim.json · ai-tells.json · manifest.jsonl · corpus/)
  check    문체 폴더 + 검사할 문서 -> 합격/불합격 JSON
  fidelity 원문 vs 결과: 숫자·영문 이름이 사라지거나 생겼는지 (금액 보호)
  gate     check + fidelity 를 함께 하고 고칠 항목을 알려준다 (스킬이 쓰는 명령)

읽는 형식: .hwp(Java 필요) · .hwpx · report.json · .txt · .md
"""
import argparse
import hashlib
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

WINDOW = 5            # 표본 단위: 5줄 묶음 (3~10줄에서 결과가 같았음 -- kosoe-office 실험 2026-11-09)
MIN_LINE = 8          # 이보다 짧은 줄(제목 조각, 번호)은 문장으로 보지 않는다
ALLOW = 2             # 밴드를 벗어난 지표가 이 개수까지는 합격 (gn-voice 와 같은 규칙)
LOO_WIDEN = 1.5       # 한 건 빼고 검증이 실패하면 여유를 한 번 넓힌다
KST = timezone(timedelta(hours=9))
SKILL = Path(__file__).resolve().parents[2]   # 스킬 루트 (lib/, scripts/, templates/)

RATIO = ("end_m", "end_da", "end_polite", "end_other")
CV = ("line_len_cv",)
KEYS = RATIO + ("line_len", "period_per1k", "comma_per1k", "paren_per1k") + CV
MIN_SLACK = {**{k: 0.10 for k in RATIO}, **{k: 0.10 for k in CV},
             "line_len": 3.0, "period_per1k": 3.0, "comma_per1k": 3.0, "paren_per1k": 3.0}

# 쓰면 안 되는 일반 AI 상투구. hard=즉시 불합격, capped=한 문서에서 이 횟수까지.
# (gn-voice 의 ai-tells.json 은 저자 코퍼스에서 뽑은 데이터라 가져오지 않고, 일반 표현만 직접 적었다.)
AI_TELLS = {
    "hard": ["알아보겠습니다", "살펴보겠습니다", "궁극적으로", "도움이 되셨다면", "놓치지 마세요"],
    "capped": {"결론적으로": 1, "요약하자면": 1, "다음과 같습니다": 2, "시사합니다": 1, "이번 글에서는": 1},
    "rule": "hard=즉시 불합격 / capped=문서당 상한 초과 시 불합격",
}
EMOJI_RE = re.compile("[\U0001F300-\U0001FAFF✨✅]")
KH_RE = re.compile(r"[ㅋㅎ]{2,}")

# 음슴체(~음/~함/~임) 근사: 문장 끝 어절의 마지막 글자가 ㅁ 받침. 명사 오탐은 막는다.
_STRIP = re.compile(r"[)\"'”….?!\s~;ㄱ-ㅎㅏ-ㅣ]+$")
_TAIL = re.compile(r"[)\"'”….?!\s]+$")
_BLOCK = ("처음", "다음", "마음", "걸음", "얼음", "죽음", "사람", "이름", "아침", "점심",
          "기쁨", "슬픔", "게임", "시스템", "프로그램", "아이템")
# '요'로 끝나는 명사. 보고서의 '~ 필요'를 존댓말로 세지 않게 한다 (원래 도구와 gn-voice 원본에 있던 오분류)
_YO_NOUN = ("필요", "중요", "개요", "주요", "수요", "소요", "강요", "동요")


def _m_end(word):
    w = _STRIP.sub("", word)
    if not w or any(w.endswith(b) for b in _BLOCK):
        return False
    o = ord(w[-1])
    return 0xAC00 <= o <= 0xD7A3 and (o - 0xAC00) % 28 == 16


def end_type(line):
    """줄 끝맺음 한 가지: 존댓말 > ~함/~임 > ~다 > 명사형·기타 (한 줄은 한 유형에만 센다)."""
    e = _TAIL.sub("", line)
    if re.search(r"(요|죠|니다)$", e) and not e.endswith(_YO_NOUN):
        return "end_polite"
    if _m_end(e):
        return "end_m"
    if re.search(r"(다|까|랴|자|네|지)$", e):
        return "end_da"
    return "end_other"


# ---------------------------------------------------------------- 읽기
def java_exe():
    jh = os.environ.get("JAVA_HOME")
    for c in ([Path(jh) / "bin" / "java.exe", Path(jh) / "bin" / "java"] if jh else []):
        if c.exists():
            return str(c)
    j = shutil.which("java")
    if j:
        return j
    sys.exit("Java 를 찾지 못했습니다. Java 8 이상을 설치하거나 JAVA_HOME 을 지정하세요(.hwp 읽기와 렌더링에 필요)")


def _pick(top, nested):
    """본문 문단을 쓰고, 본문이 표·글상자 안에만 있는 문서(본문 문장 3줄 미만)면 그 안의 문단까지 쓴다.
    짧은 보고서에서 제목 칸·박스 글이 문체 기준에 섞이지 않도록 기준을 표본 최소 줄 수(3)에 맞춘다."""
    return "\n".join(top if sum(len(x.strip()) >= MIN_LINE for x in top) >= 3 else top + nested)


_INSPECT = re.compile(r'^(\s*)\S+.*? seg=(?:true|false) "(.*)"$')


def read_hwp(p):
    # ponytail: Inspect 는 첫 구역(section 0)만 읽는다. 구역이 여러 개인 문서가 나오면 Inspect 에 구역 반복을 넣는다.
    cp = os.pathsep.join([str(SKILL / "lib" / "hwplib-1.1.9.jar"), str(SKILL / "scripts" / "koscom-hwp.jar")])
    r = subprocess.run([java_exe(), "-Dfile.encoding=UTF-8", "-cp", cp, "Inspect", str(p)], capture_output=True)
    if r.returncode != 0:
        sys.exit(f"본문 추출 실패: {p.name}: {r.stderr.decode('utf-8', 'replace')[-300:]} (배포용·암호 문서이거나 손상된 파일일 수 있습니다)")
    top, nested = [], []
    for line in r.stdout.decode("utf-8", "replace").splitlines():
        m = _INSPECT.match(line)
        if m:
            (nested if m.group(1) else top).append(m.group(2))
    return _pick(top, nested)


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _para_text(par):
    """문단의 글자만 모은다. 표 칸·글상자(subList) 안은 따로 모으므로 건너뛴다."""
    out = []

    def walk(e):
        for c in e:
            n = _local(c.tag)
            if n == "subList":
                continue
            if n == "t":
                out.append("".join(c.itertext()))
            else:
                walk(c)
    walk(par)
    return "".join(out)


def read_hwpx(p):
    top, nested = [], []
    with zipfile.ZipFile(p) as z:
        secs = sorted((n for n in z.namelist() if re.fullmatch(r"Contents/section\d+\.xml", n)),
                      key=lambda n: int(re.findall(r"\d+", n)[-1]))
        for s in secs:
            for par in ET.fromstring(z.read(s)):
                if _local(par.tag) != "p":
                    continue
                top.append(_para_text(par))
                for sub in par.iter():
                    if _local(sub.tag) == "subList":
                        nested += [_para_text(q) for q in sub if _local(q.tag) == "p"]
    return _pick(top, nested)


MARK = {"o": "ㅇ", "num": "①", "dash": "-", "dot": "·", "note": "*", "ref": "※"}


def _obj(w):
    """목적어 조사: 받침이 없으면 '를', 있거나 한글이 아니면 '을' (DraftRenderer.obj 와 같은 규칙)."""
    o = ord(w[-1]) if w else 0
    return w + ("를" if 0xAC00 <= o <= 0xD7A3 and (o - 0xAC00) % 28 == 0 else "을")


def flatten_report(d):
    """report.json -> 렌더링될 글자. 제목 칸·표 칸과 강조 박스·참고(점선) 박스 안 글은 '| … |' 줄로 둔다.
    문체 검사에서는 빠지고 숫자 대조에는 들어간다. 렌더링한 .hwp 에서도 이 글들은 표·글상자 안에 들어가
    본문으로 읽히지 않으므로, 대표 문서와 같은 기준이 된다(selftest 10 이 다시 읽기로 확인)."""
    out = []
    draft = d.get("type") == "draft"
    colon = draft and d.get("label_style") != "paren"   # 기안문 기본 라벨은 '라벨 : 내용'

    def cell(*vals):
        vs = [str(v).replace("**", "").replace("\n", " ") for v in vals if v not in (None, "")]
        if vs:
            out.append("| " + " | ".join(vs) + " |")

    def line(s, boxed):
        cell(s) if boxed else out.append(s)

    def items(xs, boxed=False):
        for it in xs or []:
            t = it.get("type", "o")
            if t == "table":
                if draft and it.get("unit"):
                    out.append(it["unit"])
                for row in it.get("rows", []):
                    cell(*row)
            elif t == "box":
                cell(it.get("title"))
                items(it.get("items"), True)
            elif t == "figure":
                cell(it.get("caption"))
            else:
                lab = it.get("label")
                lab = (f"{lab} : " if colon else f"({lab}) ") if lab else ""
                line(f"{MARK.get(t, 'ㅇ')} {lab}{it.get('text', '')}", boxed)
                items(it.get("children"), boxed)

    def sections(ss, boxed=False):
        for s in ss or []:
            line("□ " + s.get("heading", ""), boxed)
            items(s.get("items"), boxed)

    if draft:   # 기안문: 머리·꼬리(시스템 자동생성 영역)는 양식 그대로라 본문만. 렌더러(DraftRenderer)와 같은 문장을 만든다
        basis = d.get("basis") or []
        basis = [basis] if isinstance(basis, str) else basis
        if len(basis) == 1:
            out.append(f"1. 관련 근거 : {basis[0]}")
        elif basis:
            out.append("1. 관련 근거")
            out.extend(f"  {'가나다라마바사아자차카타파하'[i]}. {b}" for i, b in enumerate(basis))
        req = d.get("request") or (("위 근거에 의거, " if basis else "") + "아래와 같이 "
                                   + _obj(d.get("purpose") or "○○○○") + " 추진하고자 하오니 결재하여 주시기 바랍니다.")
        out.append(f"{2 if basis else 1}. {req}")
        out.append("-  아    래  -")
        for i, s in enumerate(d.get("sections") or [], 1):
            out.append(f"{i}. {s.get('heading', '')}" + (f" : {s['text']}" if s.get("text") else ""))
            items(s.get("items"))
        return "\n".join(out)
    cell(d.get("title"), d.get("hq"), d.get("dept"), d.get("date"), *(d.get("approval") or {}).values())
    sections(d.get("sections"))
    for ch in d.get("chapters") or []:
        cell(ch.get("title"))
        sections(ch.get("sections"))
        for pt in ch.get("parts") or []:
            cell(pt.get("title"))
            sections(pt.get("sections"))
    for a in d.get("attachments") or []:
        cell(a.get("kind"), a.get("title"))
        sections(a.get("sections"), a.get("kind") == "참고")
    return "\n".join(out)


def read_text(path):
    p = Path(path)
    ext = p.suffix.lower()
    if ext in (".txt", ".md", ".markdown"):
        return p.read_text(encoding="utf-8")
    if ext == ".json":
        return flatten_report(json.loads(p.read_text(encoding="utf-8")))
    if ext == ".hwpx":
        return read_hwpx(p)
    if ext == ".hwp":
        return read_hwp(p)
    if ext == ".pia":
        sys.exit(f"{p.name}: 사내 보호 도구로 암호화된 파일입니다. 사내 절차대로 해제한 뒤 넣으세요")
    sys.exit(f"{p.name}: 읽을 수 없는 형식입니다(.hwp·.hwpx·report.json·.txt·.md). 워드·PDF는 본문을 .txt 로 저장해 넣으세요")


# 줄 머리 기호: □ ㅇ - · * ※ ①, '1.' '가.' 'I.' (뒤에 띄어쓰기가 있을 때만 -- '26.04억'은 그대로), '[1]'.
# 기호 글꼴 문자(사용자 정의 영역 U+E000~F8FF)도 뺀다 -- 코스콤 렌더러는 대제목 □를 U+F071 로 넣는다.
_HEAD = re.compile(r"^(?:[□■○◦●ㅇ\-\*※•▪·∙①-⑳-\s]|\[\d{1,2}\]|(?:\d{1,2}|[가나다라마바사아자차카타파하]|[IVX]{1,4})[.)](?=\s))+")


# 기안문의 고정 줄은 문장이 아니다: '- 아 래 -', 첨부 목록('첨부 1. … 1부', '     2. … 1부. 끝.'), 양식 꼬리의 '첨부∨//.∨끝.'
_FIXED = re.compile(r"-\s*아\s+래\s*-$|첨부(\s|∨|$)|\d{1,2}\.\s.*\d+부\.?(\s*끝\.)?$")


def clean_lines(text):
    """서식 요소(표, 그림, 단위 표기, 번호·기호 머리)를 걷어내고 문장 줄만 남긴다."""
    out = []
    for raw in text.splitlines():
        s = raw.strip()
        if not s or s.startswith("|") or s.startswith("![") or s.startswith("(단위") or re.match(r"^<\s*.*>$", s) or _FIXED.match(s):
            continue
        s = re.sub(r"</?(u|sup)>", "", s).replace("**", "").replace("\\*", "*").replace("\\~", "~")
        s = _HEAD.sub("", s)
        if len(s) >= MIN_LINE:
            out.append(s)
    return out


def windows(lines, w=WINDOW):
    ws = [lines[i:i + w] for i in range(0, len(lines), w)]
    return [x for x in ws if len(x) >= max(3, w // 2)]


# ---------------------------------------------------------------- 지표 (밴드·검사)
def metrics(lines):
    text = "\n".join(lines)
    n, chars = max(len(lines), 1), max(len(text), 1)
    c = Counter(end_type(l) for l in lines)
    lens = [len(l) for l in lines]
    return {
        **{k: c[k] / n for k in RATIO},
        "line_len": sum(lens) / n,
        "line_len_cv": statistics.pstdev(lens) / statistics.mean(lens) if len(lens) > 2 else 0.0,
        "period_per1k": text.count(".") / chars * 1000,
        "comma_per1k": text.count(",") / chars * 1000,
        "paren_per1k": text.count("(") / chars * 1000,
    }


def pct(vals, p):
    s = sorted(vals)
    k = (len(s) - 1) * p / 100.0
    f, c = int(k), min(int(k) + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def make_band(points, mult=1.0):
    """점 5개 미만이면 최솟값~최댓값, 이상이면 5~95% 분위수. 여유(slack)를 덧붙인다."""
    band = {}
    for k in KEYS:
        v = [p[k] for p in points]
        lo, hi = (min(v), max(v)) if len(v) < 5 else (pct(v, 5), pct(v, 95))
        slack = max((hi - lo) * 0.15, MIN_SLACK[k]) * mult
        band[k] = {"lo": round(max(lo - slack, 0.0), 4), "hi": round(hi + slack, 4), "raw_lo": round(lo, 4), "raw_hi": round(hi, 4)}
    return band


def doc_points(lines_by_doc):
    pts = []
    for ls in lines_by_doc:
        pts += [metrics(w) for w in windows(ls)]
    return pts


def doc_metrics(lines):
    """검사할 문서: 줄 묶음마다 재서 지표별 중앙값을 쓴다(밴드를 만든 방식과 같은 단위)."""
    ws = windows(lines) or [lines]
    ms = [metrics(w) for w in ws]
    return {k: statistics.median(m[k] for m in ms) for k in KEYS}, len(ws)


def tier(n_docs):
    return "trial" if n_docs <= 1 else ("small" if n_docs <= 3 else "normal")


# ---------------------------------------------------------------- 문체 분석 (style-guide.md)
END_NAME = {"end_other": "명사형(~ 추진, ~ 필요, ~ 예정 등)", "end_m": "~함·~임·~음",
            "end_da": "~다", "end_polite": "~습니다·~요(존댓말)"}
_LABEL = re.compile(r"^\(([^()\d][^()]{0,9})\)")          # (라벨) 1~10자. (1) 같은 번호는 뺀다
_TOK = re.compile(r"^[\"'“‘(\[<]+|[\"'”’)\]>,.·:;!?]+$")
MARKERS = [("□", r"^[□■]"), ("기호 글꼴(□ 등)", r"^[-]"), ("ㅇ", r"^[ㅇ○◦●]\s"), ("-", r"^-\s"), ("·", r"^[·∙•]\s"), ("*", r"^\*\s"),
           ("※", r"^※"), ("①", r"^[①-⑳]"), ("1.", r"^\d{1,2}\.\s"), ("가.", r"^[가-하]\.\s"), ("(1)", r"^\(\d{1,2}\)")]
DATES = [("2026. 4. 23", r"\d{4}\. \d{1,2}\. \d{1,2}"), ("2026.4.23", r"\d{4}\.\d{1,2}\.\d{1,2}"),
         ("2026-04-23", r"\d{4}-\d{1,2}-\d{1,2}"), ("2026년 4월 23일", r"\d{4}년 ?\d{1,2}월 ?\d{1,2}일"),
         ("'26. 4.", r"['’]\d{2}\.\s?\d{1,2}"), ("’26년", r"['’]\d{2}년")]
_MONEY = re.compile(r"\d[\d,.]*(\s?)(조|억|천만|백만|만|천)?(\s?)원")
SIGNS = [("%p", r"%p"), ("%", r"%(?!p)"), ("↑", "↑"), ("↓", "↓"), ("△", "△"), ("전년 대비", r"전년\s?대비")]


def _words(line):
    return [w for w in (_TOK.sub("", x) for x in _TAIL.sub("", line).split()) if w]


def _ending(line, k):
    ws = _words(line)
    if len(ws) < k or re.search(r"\d", " ".join(ws[-k:])):
        return ""
    return " ".join(ws[-k:])


def analyze(docs):
    """docs: [(id, 파일명, 원문, 문장 줄)] -> 문체 분석값 (모두 대표 문서에서 잰 숫자와 원문 그대로의 예)."""
    lines = [l for d in docs for l in d[3]]
    raws = [r.strip() for d in docs for r in d[2].splitlines() if r.strip()]
    raw = "\n".join(d[2] for d in docs)
    n, nd = max(len(lines), 1), len(docs)
    ends = Counter(end_type(l) for l in lines)
    endings = {k: round(ends[k] / n, 3) for k in RATIO}
    top1 = [(w, c) for w, c in Counter(filter(None, (_ending(l, 1) for l in lines))).most_common(12) if c >= 2]
    top2 = [(w, c) for w, c in Counter(filter(None, (_ending(l, 2) for l in lines))).most_common(8) if c >= 2]
    tail2 = Counter(_TAIL.sub("", l)[-2:] for l in lines)          # gn-voice smoothness 의 끝맺음 엔트로피
    entropy = -sum(v / n * math.log2(v / n) for v in tail2.values()) if lines else 0.0
    lens = [len(l) for l in lines] or [0]
    labels = Counter(m.group(1).strip() for m in (_LABEL.match(l) for l in lines) if m)
    df, tf = Counter(), Counter()                                   # 두 어절 표현: 문서 수와 횟수
    for d in docs:
        seen = set()
        for l in d[3]:
            ws = _words(l)
            for a, b in zip(ws, ws[1:]):
                bg = f"{a} {b}"
                if len(a) == 1 and len(b) == 1:    # '개 요'·'첨 부'처럼 글자를 띄운 제목은 표현이 아니다
                    continue
                if re.search(r"[가-힣]", bg) and not re.search(r"\d", bg):
                    tf[bg] += 1
                    seen.add(bg)
        df.update(seen)
    phrases = [(bg, c) for bg, c in tf.most_common() if c >= 2 and df[bg] >= min(2, nd)][:12]
    money = Counter()
    for m in _MONEY.finditer(raw):
        money[(m.group(2) or "") + (" " if m.group(3) else "") + "원"] += 1
    a = {
        "n_docs": nd, "n_lines": len(lines), "endings": endings,
        "top_endings": top1, "top_endings2": top2,
        "ending_entropy": round(entropy, 2), "ending_kinds": len(tail2),
        "line_len": {"mean": round(statistics.mean(lens), 1), "p25": round(pct(lens, 25)), "p75": round(pct(lens, 75)),
                     "min": min(lens), "max": max(lens)},
        "period_end_ratio": round(sum(1 for l in lines if l.rstrip().endswith(".")) / n, 3),
        "comma_per_line": round(sum(l.count(",") for l in lines) / n, 2),
        "paren_per_line": round(sum(l.count("(") for l in lines) / n, 2),
        "label_rate": round(sum(labels.values()) / n, 3), "labels": labels.most_common(10),
        "markers": [(k, c) for k, c in ((k, sum(1 for r in raws if re.match(p, r))) for k, p in MARKERS) if c],
        "dates": [(k, c) for k, c in ((k, len(re.findall(p, raw))) for k, p in DATES) if c],
        "money": money.most_common(6),
        "signs": [(k, c) for k, c in ((k, len(re.findall(p, raw))) for k, p in SIGNS) if c],
        "phrases": phrases,
    }
    a["dominant"] = max(RATIO, key=lambda k: endings[k])
    a["examples"] = examples(docs, a["dominant"])
    return a


def examples(docs, dominant, k=6):
    """대표 문서에서 주된 끝맺음이고 길이가 중간(25~75%)인 줄을 문서마다 고르게 뽑는다(원문 그대로)."""
    lens = [len(l) for d in docs for l in d[3]]
    if not lens:
        return []
    lo, hi = pct(lens, 25), pct(lens, 75)
    per = -(-k // len(docs))
    out = []
    for d in docs:
        pool = [l for l in d[3] if end_type(l) == dominant and lo <= len(l) <= hi]
        out += pool[::max(len(pool) // per, 1)][:per]
    return out[:k]


def _pc(x):
    return f"{round(x * 100)}%"


def rules_of(a):
    """분석값 -> 작성 규칙(문장). 숫자가 기준을 넘을 때만 규칙을 만든다."""
    e = a["endings"]
    order = sorted(RATIO, key=lambda k: e[k], reverse=True)
    r = [f"줄 끝은 주로 {END_NAME[order[0]]}으로 끝낸다({_pc(e[order[0]])})"
         + (f". {END_NAME[order[1]]}도 섞는다({_pc(e[order[1]])})" if e[order[1]] >= 0.15 else "")]
    if a["top_endings"]:
        r.append("자주 쓴 끝말을 우선 쓴다: " + ", ".join(w for w, _ in a["top_endings"][:8]))
    if e["end_polite"] >= 0.3:
        r.append("주의: 존댓말 끝맺음이 많다. 코스콤 작성 원칙(존댓말 서술 금지)과 맞지 않으니 메일·안내문 같은 다른 유형이 섞였는지 확인한다")
    p = a["period_end_ratio"]
    r.append("줄 끝에 마침표를 찍지 않는다" if p < 0.1 else
             f"줄 끝에 마침표를 찍는다({_pc(p)})" if p > 0.6 else f"줄 끝 마침표는 섞어 쓴다({_pc(p)})")
    ll = a["line_len"]
    r.append(f"한 항목은 평균 {ll['mean']}자, 보통 {ll['p25']}~{ll['p75']}자로 쓴다(3줄 이내 원칙 안에서)")
    lr = a["label_rate"]
    if lr >= 0.3:
        r.append(f"항목 앞에 (라벨)을 붙인다({_pc(lr)}). 자주 쓴 라벨: " + ", ".join(w for w, _ in a["labels"][:6]))
    elif lr < 0.1:
        r.append("항목 앞 (라벨)은 거의 쓰지 않는다 -> report.json 의 label 을 비워 둔다")
    else:
        r.append(f"(라벨)은 일부 항목에만 붙인다({_pc(lr)})")
    if a["dates"]:
        r.append(f"본문 날짜는 '{max(a['dates'], key=lambda x: x[1])[0]}' 꼴로 쓴다(제목 칸 date 는 렌더러 형식을 지킨다)")
    if a["money"]:
        r.append("금액 단위는 " + ", ".join(f"'{u}'" for u, _ in a["money"][:3]) + " 꼴을 쓴다. 근거 금액을 다른 단위로 환산하지는 않는다")
    signs = [k for k, _ in a["signs"] if k not in ("%",)]
    if signs:
        r.append("증감 표기는 " + ", ".join(f"'{s}'" for s in signs) + "를 쓴다")
    if a["phrases"]:
        r.append("자주 쓴 표현을 살려 쓴다: " + ", ".join(bg for bg, _ in a["phrases"][:8]))
    return r


def _fmt(pairs):
    return " · ".join(f"{w}({c})" for w, c in pairs) or "(없음)"


def guide_md(doc_type, meta, rows, a, rules, band):
    loo = meta["leave_one_out"]
    loo_s = ", ".join(f"{x['held_out']} {'통과' if x['pass'] else '불합격'}" for x in loo) or "1건이라 하지 않음"
    e = a["endings"]
    L = [f"# 문체 가이드 — {doc_type} ({meta['label']})", "",
         f"- 근거: 대표 문서 {a['n_docs']}건, 문장 줄 {a['n_lines']}개, 5줄 묶음 점 {meta['n_points']}개",
         *[f"  - {r['id']} {r['source_file']} ({r['lines']}줄)" for r in rows],
         f"- 한 건 빼고 검증: {loo_s}" + (" (여유를 한 번 넓힘)" if meta["widened_after_loo"] else ""),
         f"- 만든 때: {meta['built']} · `scripts/voice/voice_tool.py build`", "",
         "> 코스콤 서식 규칙(한 항목 3줄 이내, □ > ㅇ > - > · 단계, 제목 칸 날짜 형식, 원문에 없는 숫자 금지)은 그대로 지키고,",
         "> 그 안에서 끝맺음·끝말·라벨·숫자 표기·표현은 이 가이드를 따른다. 숫자는 모두 대표 문서에서 잰 값이다.", "",
         "## 1. 이렇게 쓴다", "", *[f"- {r}" for r in rules],
         "- 이모지·ㅋㅋ·AI 상투구(살펴보겠습니다, 결론적으로 등)는 쓰지 않는다", "",
         "## 2. 끝맺음", "", "| 유형 | 비율 | 허용 범위(5줄 묶음) |", "|---|---|---|",
         *[f"| {END_NAME[k]} | {_pc(e[k])} | {_pc(band[k]['lo'])} ~ {_pc(band[k]['hi'])} |" for k in RATIO], "",
         f"- 자주 쓴 끝말: {_fmt(a['top_endings'])}", f"- 자주 쓴 끝 두 어절: {_fmt(a['top_endings2'])}",
         f"- 끝맺음 다양도: 끝 두 글자 {a['ending_kinds']}가지, 엔트로피 {a['ending_entropy']}비트"
         " (gn-voice 의 과매끈 지표. 참고값이며 검사에는 쓰지 않는다)", "",
         "## 3. 길이와 문장부호", "",
         f"- 항목(줄) 길이: 평균 {a['line_len']['mean']}자, 25~75% {a['line_len']['p25']}~{a['line_len']['p75']}자,"
         f" 최소 {a['line_len']['min']} · 최대 {a['line_len']['max']}자",
         f"- 줄 끝 마침표: {_pc(a['period_end_ratio'])} · 줄당 쉼표 {a['comma_per_line']}개 · 줄당 괄호 {a['paren_per_line']}개", "",
         "## 4. 항목 머리와 라벨", "",
         f"- 머리 기호(줄 수): {_fmt(a['markers'])}",
         f"- (라벨) 붙인 줄: {_pc(a['label_rate'])} · 자주 쓴 라벨: {_fmt(a['labels'])}", "",
         "## 5. 숫자·날짜 표기", "",
         f"- 날짜: {_fmt(a['dates'])}", f"- 금액 단위: {_fmt(a['money'])}", f"- 비율·증감: {_fmt(a['signs'])}", "",
         "## 6. 자주 쓴 표현 (두 어절, 여러 문서에 나온 것)", "", f"- {_fmt(a['phrases'])}", "",
         "## 7. 예문 (대표 문서에서 그대로)", "", *[f"- {x}" for x in a["examples"]], "",
         "## 8. 검사 기준 (`gate`)", "",
         f"- 아래 지표 중 허용 범위를 벗어난 것이 {ALLOW}개 이하이면 문체 합격. 이모지·ㅋㅋ·금지 상투구는 즉시 불합격.",
         "- 금액·숫자·영문 이름은 근거 파일(`--facts`)과 대조한다. 근거에 없는 값이 생기면 불합격.", "",
         "| 지표 | 대표 문서 범위 | 허용 범위 |", "|---|---|---|",
         *[f"| {HINT[k]} | {b['raw_lo']} ~ {b['raw_hi']} | {b['lo']} ~ {b['hi']} |" for k, b in band.items()], "",
         "이 파일과 corpus/ 에는 대표 문서의 문장이 그대로 들어 있다. 개인정보가 없는지 확인한 뒤 공유한다.", ""]
    return "\n".join(L)


# ---------------------------------------------------------------- 검사
def violations_of(lines, band, tells):
    text = "\n".join(lines)
    v = []
    if EMOJI_RE.search(text):
        v.append({"type": "must_zero", "msg": "이모지 발견"})
    if KH_RE.search(text):
        v.append({"type": "must_zero", "msg": "ㅋㅋ/ㅎㅎ 발견"})
    for p in tells.get("hard", []):
        if p in text:
            v.append({"type": "forbidden_phrase", "msg": p})
    for p, cap in tells.get("capped", {}).items():
        c = text.count(p)
        if c > cap:
            v.append({"type": "tell_cap", "msg": f"{p}x{c}(상한 {cap})"})
    m, nw = doc_metrics(lines)
    out_of_band = []
    for k, b in band.items():
        if not (b["lo"] <= m[k] <= b["hi"]):
            out_of_band.append({"metric": k, "value": round(m[k], 3), "expected": [b["lo"], b["hi"]]})
    return v, out_of_band, m, nw


def judge(lines, band, tells, allow=ALLOW):
    v, oob, m, nw = violations_of(lines, band, tells)
    ok = not v and len(oob) <= allow
    return {"pass": ok, "hard": v, "out_of_band": oob, "n_windows": nw, "metrics": {k: round(x, 3) for k, x in m.items()}}


# ---------------------------------------------------------------- build
def build(args):
    if not args.human:
        sys.exit("사람이 직접 쓴 문서만 쓸 수 있습니다(--human). AI 도움을 받은 글은 기준을 오염시킵니다")
    files = [Path(f) for f in args.files]
    docs, rows = [], []
    for i, f in enumerate(files, 1):
        raw = read_text(f)
        lines = clean_lines(raw)
        if not windows(lines):
            sys.exit(f"{f.name}: 문장 줄이 너무 적어 표본을 만들 수 없습니다({len(lines)}줄, 최소 3줄)")
        did = f"D-{i:03d}"
        docs.append((did, f.name, raw, lines))
        rows.append({"id": did, "source_file": f.name, "sha256": hashlib.sha256(f.read_bytes()).hexdigest(),
                     "doc_type": args.type, "lines": len(lines), "chars": sum(len(l) for l in lines),
                     "human_written": True, "included": True, "split": "train",
                     "note": "서식 제거 본문(corpus/)만 보관. 개인정보 점검 후 사용"})
    out = Path(args.out)
    (out / "corpus").mkdir(parents=True, exist_ok=True)
    for did, _, _, lines in docs:
        (out / "corpus" / f"{did}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out / "manifest.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")

    n = len(docs)
    pts = doc_points([d[3] for d in docs])

    def run_loo(mu):
        res = []
        for i in range(n if n >= 2 else 0):
            b = make_band(doc_points([d[3] for j, d in enumerate(docs) if j != i]), mu)
            j = judge(docs[i][3], b, AI_TELLS)
            res.append({"held_out": docs[i][0], "pass": j["pass"], "out_of_band": [x["metric"] for x in j["out_of_band"]]})
        return res

    mult, widened = 1.0, False
    loo = run_loo(mult)
    if loo and not all(r["pass"] for r in loo):   # 한 건 빼고 검증이 실패하면 여유를 한 번 넓힌다
        mult, widened = LOO_WIDEN, True
        loo = run_loo(mult)
    status = "trial" if n < 2 else ("verified" if all(r["pass"] for r in loo) else "unverified")
    label = "문체 미검증" if status == "unverified" else {"trial": "문체 시험용(1건)", "small": "문체 소표본", "normal": "문체 표본"}[tier(n)]
    band = make_band(pts, mult)
    meta = {"generator": "scripts/voice/voice_tool.py", "built": datetime.now(KST).replace(microsecond=0).isoformat(),
            "n_docs": n, "n_points": len(pts), "unit": f"{WINDOW}줄 묶음", "tier": tier(n), "status": status,
            "label": label, "slack_mult": mult, "widened_after_loo": widened, "allow_out_of_band": ALLOW,
            "leave_one_out": loo,
            "note": "점 5개 미만이면 min~max, 이상이면 5~95% 분위수 + 여유. 표본이 늘면 build 를 다시 돌린다."}
    (out / "fingerprint-slim.json").write_text(json.dumps({"meta": meta, "doc_type": args.type, "band": band},
                                                          ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (out / "ai-tells.json").write_text(json.dumps(AI_TELLS, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    a = analyze(docs)
    rules = rules_of(a)
    prof = {"doc_type": args.type, "status": status, "label": label, "evidence": [r["id"] for r in rows],
            "rules": rules, "analysis": a,
            "band_rules": [f"{k}: {b['raw_lo']} ~ {b['raw_hi']} (허용 {b['lo']} ~ {b['hi']})" for k, b in band.items()],
            "always": ["코스콤 서식 규칙 우선(3줄 이내, 단계 기호, 제목 칸 날짜 형식)", "이모지·ㅋㅋ 금지",
                       "AI 상투구 금지(ai-tells.json)", "금액·숫자·영문 이름은 근거 그대로(fidelity)"]}
    (out / "style-profile.json").write_text(json.dumps(prof, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (out / "style-guide.md").write_text(guide_md(args.type, meta, rows, a, rules, band), encoding="utf-8")
    print(json.dumps({"out": str(out), "guide": str(out / "style-guide.md"), "n_docs": n, "n_points": len(pts),
                      "tier": tier(n), "status": status, "label": label, "widened": widened, "leave_one_out": loo,
                      "rules": rules}, ensure_ascii=False))
    return 0


# ---------------------------------------------------------------- check / fidelity / gate
def _load(voice):
    v = Path(voice)
    return (json.loads((v / "fingerprint-slim.json").read_text(encoding="utf-8")),
            json.loads((v / "ai-tells.json").read_text(encoding="utf-8")))


def check(args):
    slim, tells = _load(args.voice)
    lines = clean_lines(read_text(args.file))
    if not lines:
        sys.exit("검사할 문장 줄이 없습니다")
    res = judge(lines, slim["band"], tells)
    res.update({"label": slim["meta"]["label"], "status": slim["meta"]["status"], "tier": slim["meta"]["tier"],
                "reference_only": slim["meta"]["tier"] == "trial"})
    print(json.dumps(res, ensure_ascii=False))
    return 0 if res["pass"] else 3


NUM_RE = re.compile(r"\d[\d,.]*\s?[%가-힣a-zA-Z]{0,3}")
LATIN_RE = re.compile(r"[A-Za-z][A-Za-z0-9_.-]{1,}")
# 줄 머리 번호 '1. '만 뺀다. 본문의 날짜 '2026. 11. 10'의 '11.'·'10.'과 소수 '26.04'는 숫자로 센다
_LIST_NO = re.compile(r"(?m)^[ \t□■○◦●ㅇ\-\*※•▪·∙]*\d{1,2}\.(?=\s)")
# 연도로 시작하는 날짜는 연·월·일로 나눈다: 상세보고 표지 '2026.3'이 소수 하나로 읽혀 '2026년 3월'과 어긋나지 않게
_YMD = re.compile(r"\b((?:19|20)\d{2})\.\s?(\d{1,2})(?:\.\s?(\d{1,2}))?(?!\d)")
# 두 자리 연도 '’26년'·''26. 4.'는 '2026'으로 본다: 문체 가이드가 견본의 연도 표기를 따르라고 해도 숫자는 같다
_YY = re.compile(r"['’‘](\d{2})(?=\s?년|\.)")


def assets(text):
    text = _YY.sub(r"20\1", _LIST_NO.sub("", text))
    text = _YMD.sub(lambda m: " ".join(g for g in m.groups() if g), text)
    nums = {re.sub(r"[^\d.]", "", m.group()).strip(".") for m in NUM_RE.finditer(text)}
    nums = {x.lstrip("0") if re.fullmatch(r"0\d+", x) else x for x in nums}   # '09'와 '9'(날짜)는 같은 값
    return {x for x in nums if x}, {m.group().lower() for m in LATIN_RE.finditer(text)}


def fidelity_of(src, out):
    sn, sl = assets(src)
    on, ol = assets(out)
    missing, invented = sorted(sn - on) + sorted(sl - ol), sorted(on - sn) + sorted(ol - sl)
    return {"pass": not missing and not invented, "missing": missing, "invented": invented}


def fidelity(args):
    res = fidelity_of(read_text(args.src), read_text(args.out))
    print(json.dumps(res, ensure_ascii=False))
    return 0 if res["pass"] else 1


HINT = {"end_m": "~함/~임으로 끝나는 줄", "end_da": "~다로 끝나는 줄", "end_polite": "~요/~니다로 끝나는 줄",
        "end_other": "명사 등으로 끝나는 줄", "line_len": "줄 평균 길이", "line_len_cv": "줄 길이의 들쭉날쭉함",
        "period_per1k": "마침표 밀도", "comma_per1k": "쉼표 밀도", "paren_per1k": "괄호 밀도"}


def gate(args):
    """문서 하나(report.json 권장)에 문체 검사와 숫자 대조를 함께 하고, 고칠 항목을 알려준다.
    mode=create: 새로 쓴 문서. 근거(facts)에 없는 숫자·영문 이름이 생기면 불합격, 안 쓴 근거는 참고만.
    mode=rewrite: 원문을 고쳐 쓴 문서. 사라진 것도 불합격."""
    slim, tells = _load(args.voice)
    raw = read_text(args.file)
    lines = clean_lines(raw)
    if not lines:
        sys.exit("검사할 문장 줄이 없습니다")
    style = judge(lines, slim["band"], tells)
    fid = fidelity_of("\n".join(read_text(f) for f in args.facts), raw) if args.facts else None
    fid_pass = fid is None or (not fid["invented"] and (args.mode == "create" or not fid["missing"]))
    todo = [f"[금지] {h['msg']}: 이 표현을 빼고 다시 쓰세요" for h in style["hard"]]
    for o in style["out_of_band"]:
        lo, hi = o["expected"]
        way = "높습니다 -> 줄이세요" if o["value"] > hi else "낮습니다 -> 늘리세요"
        todo.append(f"[문체] {HINT.get(o['metric'], o['metric'])}: {o['value']} (허용 {lo}~{hi}) {way}")
    if fid is not None:
        todo += [f"[숫자] 근거에 없는 값 '{x}': 근거대로 고치거나 [확인 필요]로 표시하세요" for x in fid["invented"]]
        if args.mode == "rewrite":
            todo += [f"[숫자] 원문에 있던 '{x}'가 사라졌습니다: 되살리세요" for x in fid["missing"]]
    ok = style["pass"] and fid_pass
    res = {"pass": ok, "style_pass": style["pass"], "fidelity_pass": fid_pass if fid is not None else None,
           "label": slim["meta"]["label"], "reference_only": slim["meta"]["tier"] == "trial",
           "rework": todo, "unused_facts": (fid or {}).get("missing", []) if args.mode == "create" else [],
           "out_of_band": style["out_of_band"], "n_windows": style["n_windows"]}
    print(json.dumps(res, ensure_ascii=False))
    return 0 if ok else 3


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    b = sp.add_parser("build"); b.add_argument("files", nargs="+"); b.add_argument("--out", required=True)
    b.add_argument("--type", default="보고서"); b.add_argument("--human", action="store_true", help="사람이 직접 쓴 문서임을 확인")
    b.set_defaults(fn=build)
    c = sp.add_parser("check"); c.add_argument("--voice", required=True); c.add_argument("file"); c.set_defaults(fn=check)
    g = sp.add_parser("gate"); g.add_argument("--voice", required=True)
    g.add_argument("--facts", action="append", default=[], help="근거 파일(여러 개면 --facts 를 반복)")
    g.add_argument("--mode", choices=["create", "rewrite"], default="create"); g.add_argument("file"); g.set_defaults(fn=gate)
    f = sp.add_parser("fidelity"); f.add_argument("src"); f.add_argument("out"); f.set_defaults(fn=fidelity)
    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
