#!/usr/bin/env python3
"""문서 종류를 바꿀 때 앞서 만든 원천의 내용을 새 종류의 원천으로 옮긴다(스튜디오가 쓴다. 표준 라이브러리만).

  원천: report.json(summary 보고서 · detail 투자계획서 · draft 기안문 본문), source.json(gian/1.0 결재란까지 갖춘 기안문)
  공통 뼈대: {title, approval{담당,팀장,부서장}, date, dept, hq, basis[문자열], purpose, request, sections[{heading, items}]}
  items 는 report.json 항목 형식(o·num·dash·dot·note·ref·table·box)이다.

  python convert.py <원천.json> <summary|detail|draft|gian> <결과.json>
  python convert.py --selftest
옮긴 뒤에는 사람이 고쳐야 할 수 있다: 기안문 요청 문장(purpose)은 "<제목>을 보고"로 시작하고, 결재선 전결권자·연락 메일·첨부는 비어 있다.
"""
import json
import re
import sys
from datetime import date as _date

ROLE_IN = {"부서원": "담당", "담당": "담당", "담당자": "담당", "팀장": "팀장", "부서장": "부서장", "부장": "부서장"}
MARKS = "가나다라마바사아자차카타파하"


def _plain(s):
    return re.sub(r"\*\*", "", str(s if s is not None else ""))


def _name(s):
    return re.sub(r"\s*\([^()]*\)\s*$", "", str(s or "")).strip()


def _eul(word):
    w = re.sub(r"[^가-힣]", "", word)[-1:]
    return "을" if w and (ord(w) - 0xAC00) % 28 else "를"


def _split_label(text):
    m = re.match(r"^(.{1,14}?)\s+:\s+(.+)$", text)
    return (m.group(1), m.group(2)) if m else ("", text)


# ---------------------------------------------------------------- 원천 → 뼈대
def outline(kind, d):
    if kind == "gian" or d.get("schema") == "gian/1.0":
        return _outline_gian(d)
    t = d.get("type", "summary")
    ap = dict(d.get("approval") or {})
    if "부장" in ap:
        ap["부서장"] = ap.pop("부장")
    o = {"title": d.get("title", ""), "approval": ap, "date": d.get("date", ""), "dept": d.get("dept", ""), "hq": d.get("hq", ""),
         "basis": [str(b) for b in d.get("basis") or []], "purpose": d.get("purpose", ""), "request": d.get("request", ""), "sections": []}
    if t == "detail":
        for ch in d.get("chapters") or []:
            secs = list(ch.get("sections") or []) + [s for p in ch.get("parts") or [] for s in p.get("sections") or []]
            o["sections"] += [{"heading": s.get("heading", ""), "items": list(s.get("items") or [])} for s in secs]
    else:
        for s in d.get("sections") or []:
            items = list(s.get("items") or [])
            if s.get("text"):  # 기안문 본문의 "4. 소요 예산 : 1,000원" 한 줄 내용
                items.insert(0, {"type": "o", "text": s["text"]})
            o["sections"].append({"heading": s.get("heading", ""), "items": items})
    return o


def _outline_gian(d):
    ap = d.get("approval") or {}
    appr = {ROLE_IN[l["role"]]: l.get("name", "") for l in ap.get("line") or [] if l.get("role") in ROLE_IN}
    basis = []
    for b in d.get("basis") or []:
        dt = re.sub(r"^(\d{4})-0?(\d{1,2})-0?(\d{1,2})$", r"\1. \2. \3.", b.get("date") or "")
        basis.append(f"{b.get('docNo')}({dt + ' : ' if dt else ''}{b.get('title', '')})" if b.get("docNo") else b.get("title", ""))
    o = {"title": (d.get("header") or {}).get("title", ""), "approval": appr, "date": "", "hq": "",
         "dept": (ap.get("drafter") or "").split(" ")[0], "basis": basis, "purpose": d.get("purpose", ""),
         "request": d.get("request", ""), "sections": []}
    for s in d.get("sections") or []:
        bl = []
        for b in s.get("bullets") or []:
            lab, txt = _split_label(b.get("text", ""))
            kids = [{"type": "note", "text": x.lstrip("* ").strip()} if x.startswith("*")
                    else dict(zip(("label", "text"), _split_label(x)), type="dash") for x in b.get("sub") or []]
            bl.append({"type": "o", "label": lab, "text": txt, **({"children": kids} if kids else {})})
        tbs = []
        if s.get("table"):
            t = s["table"]
            rows = [list(t.get("head") or [])]
            for r in t.get("rows") or []:
                r = [str(c) for c in r]
                if r and r[0] in ("합계", "소계"):  # 기안문: 첫 값 앞까지 자동 가로 병합 → 보고서: "<"
                    k = next((i for i in range(1, len(r)) if r[i] != ""), len(r))
                    r = [r[0]] + ["<"] * (k - 1) + r[k:]
                elif r and r[0] == "" and len(rows) > 1:  # 기안문: 빈 첫 칸은 위 칸과 세로 병합 → "^"
                    r = ["^"] + r[1:]
                rows.append(r)
            tbs.append({"type": "table", "rows": rows, **({"unit": t["unit"]} if t.get("unit") else {})})
        if s.get("schedule"):
            sc = s["schedule"]
            rows = [["추진내용"] + list(sc.get("months") or [])] + [[r[0]] + ["●" if v else "" for v in r[1]] for r in sc.get("rows") or []]
            tbs.append({"type": "table", "rows": rows, **({"unit": sc["year"]} if sc.get("year") else {})})
        if s.get("bulletsAfterTable"):
            items = tbs + [{"type": "ref", "text": (b["label"] + " : " if b["label"] else "") + b["text"]} for b in bl]
        else:
            items = bl + tbs
        o["sections"].append({"heading": s.get("title", ""), "items": items})
    return o


# ---------------------------------------------------------------- 뼈대 → 원천
def _opt(opt, k, *fallback):
    for v in ((opt or {}).get(k), *fallback):
        if v:
            return v
    return ""


def _today(kind):
    t = _date.today()
    return f"{t.year}.{t.month}" if kind == "detail" else f"{t.year}. {t.month}. {t.day}"


def _report_items(items, kind):
    allowed = {"o", "num", "dash", "dot", "note", "ref", "table", "box"} | ({"figure"} if kind == "detail" else set())
    if kind == "draft":
        allowed -= {"num", "box"}
    out = []
    for it in items:
        it = dict(it)
        typ = it.get("type", "o")
        if typ == "box" and kind == "draft":  # 기안문 본문에는 강조 박스가 없다 → ㅇ 항목 + 하위
            out.append({"type": "o", "text": it.get("title", ""), "children": _report_items(it.get("items") or [], kind)})
            continue
        if typ not in allowed:
            it["type"] = "o"
        if it.get("type") == "table" and it.get("unit") and kind != "draft":  # 보고서·투자계획서 표에는 단위 칸이 없다
            out.append({"type": "ref", "text": "단위 : " + str(it.pop("unit")).strip("() ")})
        if it.get("children"):
            it["children"] = _report_items(it["children"], kind)
        out.append(it)
    return out


def to_report(o, kind, opt=None):
    """뼈대 → report.json(summary·detail·draft). opt 는 스튜디오 서식 칸 값(title·dept·date·담당…)으로 뼈대 값보다 앞선다."""
    opt = opt or {}
    ap = o.get("approval") or {}
    title = _opt(opt, "title", o.get("title"))
    secs = [{"heading": s.get("heading", ""), "items": _report_items(s.get("items") or [], kind)} for s in o.get("sections") or []]
    if kind == "draft":
        r = {"type": "draft", "basis": list(o.get("basis") or []), "sections": secs}
        if o.get("request"):
            r["request"] = o["request"]
        else:
            r["purpose"] = o.get("purpose") or (f"{title}{_eul(title)} 보고" if title else "○○○ 추진")
        return r
    who = {k: _opt(opt, k, ap.get(k)) or "○○○(내선)" for k in ("담당", "팀장", "부서장")}
    if kind == "detail":
        who["부장"] = _opt(opt, "부장", who.pop("부서장"))
        d = _opt(opt, "date")
        if not d and re.match(r"^\d{4}\.\s*\d{1,2}", o.get("date", "")):
            d = re.sub(r"^(\d{4})\.\s*(\d{1,2}).*$", r"\1.\2", o["date"])
        return {"type": "detail", "title": title, "date": d or _today("detail"), "hq": _opt(opt, "hq", o.get("hq")) or "○○본부",
                "dept": _opt(opt, "dept", o.get("dept")) or "○○부", "approval": who, "cover": _opt(opt, "cover") or "full",
                "chapters": [{"title": "주요 내용", "sections": secs}]}
    r = {"type": "summary", "title": title, "approval": who, "date": _opt(opt, "date", o.get("date") if re.match(r"^\d{4}\. \d", o.get("date") or "") else "") or _today("summary"),
         "dept": _opt(opt, "dept", o.get("dept")) or "○○부", "sections": secs}
    if opt.get("heading_style"):
        r["heading_style"] = opt["heading_style"]
    return r


def _gian_line(it):
    lab, txt = _plain(it.get("label")), _plain(it.get("text"))
    return f"{lab} : {txt}" if lab else txt


def to_gian(o, opt=None):
    """뼈대 → source.json(gian/1.0). 결재선은 담당·팀장·부서장 이름만 옮기고 전결권자·메일·첨부는 비워 둔다."""
    opt = opt or {}
    ap = o.get("approval") or {}
    title = _opt(opt, "title", o.get("title"))
    sections = []
    for s in o.get("sections") or []:
        bullets, tables = [], []
        for it in s.get("items") or []:
            typ = it.get("type", "o")
            if typ == "table":
                rows = [[_plain(c) for c in r] for r in it.get("rows") or []]
                if len(rows) < 2:
                    continue
                n = max(len(r) for r in rows)
                rows = [r + [""] * (n - len(r)) for r in rows]
                body = [["" if c in ("^", "<") else c for c in r] for r in rows[1:]]
                tables.append({"head": rows[0], "rows": body, **({"unit": it["unit"]} if it.get("unit") else {})})
            elif typ in ("note", "ref") and bullets:
                bullets[-1].setdefault("sub", []).append("* " + _plain(it.get("text")))
            elif typ == "box":
                bullets.append({"text": _plain(it.get("title")), "sub": [_gian_line(x) for x in it.get("items") or []]})
            elif typ != "figure":
                b = {"text": _gian_line(it)}
                kids = [("* " + _plain(c.get("text"))) if c.get("type") in ("note", "ref") else _gian_line(c)
                        for c in it.get("children") or [] if c.get("type") != "table"]
                if kids:
                    b["sub"] = kids
                bullets.append(b)
        first = {"title": _plain(s.get("heading")), **({"bullets": bullets} if bullets else {})}
        if tables:
            first["table"] = tables[0]
        if bullets or tables:
            sections.append(first)
        for k, t in enumerate(tables[1:], 2):  # 기안문 번호 항목에는 표가 하나 → 나머지 표는 이어지는 항목으로
            sections.append({"title": f"{_plain(s.get('heading'))}({k})", "table": t})
    for i, s in enumerate(sections, 1):
        s["no"] = i
    basis = []
    for i, b in enumerate(o.get("basis") or []):
        m = re.match(r"^\s*([가-힣A-Za-z]{1,8}\d{4}-\d{3,4})\s*\((?:(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.?\s*:\s*)?(.+)\)\s*$", b)
        basis.append({"mark": MARKS[i % len(MARKS)], "docNo": m.group(1), "title": m.group(5),
                      **({"date": f"{m.group(2)}-{int(m.group(3)):02d}-{int(m.group(4)):02d}"} if m.group(2) else {})}
                     if m else {"mark": MARKS[i % len(MARKS)], "title": b})
    who = [(r, _name(_opt(opt, r, ap.get(r)))) for r in ("담당", "팀장", "부서장")]
    dept = _opt(opt, "dept", o.get("dept"))
    g = {"schema": "gian/1.0",
         "header": {"recipient": _opt(opt, "recipient") or "내부결재", "title": title, "kind": "보고"},
         "basis": basis, "sections": sections, "attachments": [],
         "approval": {"drafter": f"{dept} {who[0][1]}".strip(),
                      "line": [{"role": "부서원" if r == "담당" else r, "name": n or "○○○"} for r, n in who], "cooperation": []},
         "footer": {"docNo": "", "date": "", "org": {"tel": "02-767-8114"}, "email": _opt(opt, "email")}}
    if o.get("request"):
        g["request"] = _plain(o["request"])
    else:
        g["purpose"] = _plain(o.get("purpose")) or (f"{title}{_eul(title)} 보고" if title else "○○○ 추진")
    return g


def convert(kind, data, target, opt=None):
    """target: summary|detail|draft|gian"""
    o = outline(kind, data)
    return to_gian(o, opt) if target == "gian" else to_report(o, target, opt)


# ---------------------------------------------------------------- 자체 검사
def _selftest():
    rep = {"type": "summary", "title": "사무환경 개선 추진계획", "approval": {"담당": "홍길동(1234)", "팀장": "김철수(2345)", "부서장": "이영희(3456)"},
           "date": "2026. 11. 9", "dept": "경영지원부", "sections": [
               {"heading": "추진 배경", "items": [{"type": "o", "label": "현황", "text": "회의실 **14실** 부족", "children": [
                   {"type": "dash", "label": "수요", "text": "68% 응답"}, {"type": "note", "text": "복수 응답"}]}]},
               {"heading": "사업비", "items": [{"type": "o", "text": "30.6억원"}, {"type": "table", "rows": [
                   ["구분", "항목", "금액"], ["시공", "건축", "10"], ["^", "전기", "5"], ["합계", "<", "15"]]},
                   {"type": "ref", "text": "부가세 별도"}, {"type": "table", "rows": [["a", "b"], ["1", "2"]]}]}]}
    g = convert("report", rep, "gian")
    assert g["purpose"] == "사무환경 개선 추진계획을 보고" and g["header"]["title"] == "사무환경 개선 추진계획", g
    assert g["approval"]["drafter"] == "경영지원부 홍길동" and [l["name"] for l in g["approval"]["line"]] == ["홍길동", "김철수", "이영희"]
    s1 = g["sections"][0]["bullets"][0]
    assert s1["text"] == "현황 : 회의실 14실 부족" and s1["sub"] == ["수요 : 68% 응답", "* 복수 응답"], s1
    t = g["sections"][1]["table"]
    assert t["rows"] == [["시공", "건축", "10"], ["", "전기", "5"], ["합계", "", "15"]] and g["sections"][1]["bullets"][0]["sub"] == ["* 부가세 별도"]
    assert [s["no"] for s in g["sections"]] == [1, 2, 3] and g["sections"][2]["title"] == "사업비(2)"
    back = convert("gian", g, "summary")  # 기안문 → 보고서: 병합 표시와 라벨이 돌아온다
    assert back["sections"][1]["items"][1]["rows"] == [["구분", "항목", "금액"], ["시공", "건축", "10"], ["^", "전기", "5"], ["합계", "<", "15"]], back
    assert back["sections"][0]["items"][0]["label"] == "현황" and back["approval"]["담당"] == "홍길동"
    dt = convert("report", rep, "detail", {"hq": "경영지원본부"})
    assert dt["approval"]["부장"] == "이영희(3456)" and dt["date"] == "2026.11" and dt["chapters"][0]["sections"][0]["heading"] == "추진 배경"
    dr = convert("report", rep, "draft")
    assert dr["purpose"] == "사무환경 개선 추진계획을 보고" and dr["sections"][0]["items"][0]["children"][0]["type"] == "dash"
    gb = {"schema": "gian/1.0", "header": {"title": "EDR 도입"}, "basis": [{"docNo": "보안2026-0042", "date": "2026-01-22", "title": "정보보호 계획"}],
          "purpose": "EDR 도입", "sections": [{"no": 1, "title": "일정", "schedule": {"year": "’26년", "months": ["10월", "11월"], "rows": [["공고", [1, 0]]]}}],
          "approval": {"drafter": "정보보호부 윤가람", "line": [{"role": "부서원", "name": "윤가람"}]}}
    sm = convert("gian", gb, "draft")
    assert sm["basis"] == ["보안2026-0042(2026. 1. 22. : 정보보호 계획)"] and sm["sections"][0]["items"][0]["rows"][1] == ["공고", "●", ""], sm
    assert convert("report", sm, "gian")["basis"][0] == {"mark": "가", "docNo": "보안2026-0042", "title": "정보보호 계획", "date": "2026-01-22"}
    assert _eul("계획") == "을" and _eul("보고서") == "를"
    print("convert selftest OK")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if "--selftest" in sys.argv:
        return _selftest()
    src, target, dst = sys.argv[1:4]
    data = json.loads(open(src, encoding="utf-8").read())
    out = convert("gian" if data.get("schema") == "gian/1.0" else "report", data, target)
    open(dst, "w", encoding="utf-8").write(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    print(f"OK {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
