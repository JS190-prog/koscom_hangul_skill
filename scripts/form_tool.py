#!/usr/bin/env python3
"""내 양식(.hwpx 안의 {{항목}} 자리표시)을 등록하고 채운다. 자연어로 만든 양식 사양(report.json)도 양식으로 만든다.

  python form_tool.py list                                        등록된 양식
  python form_tool.py fields <양식.hwpx>                           {{항목}} 목록
  python form_tool.py register <양식.hwpx|.hwp> --name 이름 [--desc 설명] [--replace]
  python form_tool.py make <사양.json> --name 이름 [--desc 설명] [--replace]
        사양은 report.json(summary·detail·draft)이고 채울 자리에 {{항목}}을 쓴다(자연어 요청을 에이전트가 사양으로 정리)
  python form_tool.py fill <등록 이름|양식.hwpx> <값.json> <결과 경로(확장자 없이)>   값.json = {"항목": "값", ...}
  python form_tool.py preset [이름]                                자주 쓰는 양식 목록, 이름을 주면 바로 등록(references/forms)
  python form_tool.py --selftest

등록 위치: %USERPROFILE%\\.koscom-hwp\\forms\\<이름>\\ (KOSCOM_HWP_FORMS 로 바꿀 수 있다)
  파일로 올린 양식: form.hwpx + form.json → 채우면 .hwpx(자리표시 글자만 바꾼다, 표준 라이브러리)
  사양으로 만든 양식: spec.json + form.hwp(빈 양식) + form.json → 채우면 값을 넣은 사양을 렌더러로 그린 .hwp
                     (렌더러가 자간·줄·쪽을 값 길이에 맞춘다. Java 필요, 한글 불필요)
값이 없거나 빈 항목은 {{항목}} 그대로 남는다(최종본 검사가 잡는다). .hwp 양식을 올릴 때만 한글이 필요하다(hangul.py).
"""
import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path
from xml.dom import minidom

HERE = Path(__file__).resolve().parent
SKILL = HERE.parent
LIB = Path(os.environ.get("KOSCOM_HWP_FORMS") or Path.home() / ".koscom-hwp" / "forms")
PH = re.compile(r"\{\{\s*([^{}\n]{1,40}?)\s*\}\}")
SEC = re.compile(r"Contents/section\d+\.xml")
PRESETS = SKILL / "references" / "forms" / "presets.json"  # 자주 쓰는 양식(누르면 에이전트 없이 바로 등록)
# 빈 칸 자동 채우기. ponytail: 항목 이름으로 고른다 — 맞지 않는 양식이 나오면 사양에 칸 종류를 적는 쪽으로
PROFILE = re.compile(r"담당|팀장|부서장|부장|본부장|부서|소속|본부|성명|이름|신청자|작성자|요청자|출장자|직위|직급|연락처|내선|사번|이메일")
TODAY = re.compile(r"(신청|작성|제출|요청|기안)일(자)?$")
SUM_ROW = {"합계", "계", "총계", "총액"}


def safe_name(s, n=60):
    s = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', "_", str(s)).strip(" ._")
    return s[:n]


def run_hangul(cmd, src, dst, *extra):
    """hangul.py 를 따로 띄운다(한글 COM 이 멈추거나 죽어도 호출한 프로세스는 안전하다)."""
    r = subprocess.run([sys.executable, str(HERE / "hangul.py"), cmd, str(src), str(dst), *extra], capture_output=True, timeout=300)
    try:
        out = json.loads(r.stdout.decode("utf-8", "replace").strip().splitlines()[-1])
    except (IndexError, ValueError):
        out = {"ok": False, "error": (r.stderr or r.stdout).decode("utf-8", "replace")[-400:] or "한글 실행 실패"}
    if not out.get("ok"):
        raise RuntimeError(out.get("error") or "한글 실행 실패")
    return out


# ---------------------------------------------------------------- 항목 찾기·채우기
def fields(path):
    """문서에 나오는 순서대로 {{항목}} 이름(중복 없이)."""
    names = []
    with zipfile.ZipFile(path) as z:
        for n in sorted(x for x in z.namelist() if SEC.fullmatch(x)):
            text = re.sub(r"<[^>]+>", "", z.read(n).decode("utf-8"))
            for m in PH.finditer(text):
                if m.group(1) not in names:
                    names.append(m.group(1))
    return names


def _owner_p(node):
    node = node.parentNode
    while node is not None and getattr(node, "tagName", None) != "hp:p":
        node = node.parentNode
    return node


def _text(t):
    return "".join(c.data if c.nodeType == c.TEXT_NODE else "\n" if c.nodeName == "hp:lineBreak" else "" for c in t.childNodes)


def _put(dom, t, before, text):
    """text 를 before 앞에 넣는다. 줄바꿈은 <hp:lineBreak/> 로."""
    for i, part in enumerate(str(text).split("\n")):
        if i:
            t.insertBefore(dom.createElement("hp:lineBreak"), before)
        if part:
            t.insertBefore(dom.createTextNode(part), before)


def fill_xml(data, values):
    """구역 XML 의 {{항목}}을 values 로 바꾼다. 값이 없는 항목은 그대로 둔다. 바꾼 문단은 줄 배치 캐시를 지운다."""
    dom = minidom.parseString(data)
    sub = lambda s: PH.sub(lambda m: str(values[m.group(1)]) if m.group(1) in values else m.group(0), s)  # noqa: E731
    for p in dom.getElementsByTagName("hp:p"):
        ts = [t for t in p.getElementsByTagName("hp:t") if _owner_p(t) is p]
        if "{{" not in "".join(_text(t) for t in ts):
            continue
        changed = False
        for t in ts:
            for node in list(t.childNodes):
                if node.nodeType == node.TEXT_NODE and PH.search(node.data) and sub(node.data) != node.data:
                    _put(dom, t, node, sub(node.data))
                    t.removeChild(node)
                    changed = True
        joined = "".join(_text(t) for t in ts)
        if any(m.group(1) in values for m in PH.finditer(joined)):  # 자리표시가 여러 글자 묶음에 걸쳐 쪼개진 경우: 첫 묶음에 모은다
            for t in ts:
                while t.firstChild:
                    t.removeChild(t.firstChild)
            _put(dom, ts[0], None, sub(joined))
            changed = True
        if changed:
            for c in list(p.childNodes):
                if c.nodeName == "hp:linesegarray":
                    p.removeChild(c)
    return dom.toxml(encoding="UTF-8")


def fill(src, values, dst):
    """양식 .hwpx 를 채워 dst 로 쓴다. 돌려주는 값: 남은 자리표시 이름 목록."""
    values = {str(k): "" if v is None else str(v) for k, v in values.items()}
    buf = io.BytesIO()
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        names = zin.namelist()
        if "mimetype" in names:  # HWPX(OCF) 규칙: mimetype 은 맨 앞, 압축하지 않음
            zout.writestr(zipfile.ZipInfo("mimetype"), zin.read("mimetype"), compress_type=zipfile.ZIP_STORED)
        for n in names:
            if n == "mimetype":
                continue
            data = zin.read(n)
            if SEC.fullmatch(n):
                data = fill_xml(data, values)
            elif n == "Preview/PrvText.txt":
                data = PH.sub(lambda m: values.get(m.group(1), m.group(0)), data.decode("utf-8", "replace")).encode("utf-8")
            zout.writestr(zin.getinfo(n), data)
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    Path(dst).write_bytes(buf.getvalue())
    return fields(dst)


# ---------------------------------------------------------------- 등록
def list_forms(lib=None):
    lib = Path(lib or LIB)
    out = []
    for f in sorted(lib.glob("*/form.json")) if lib.is_dir() else []:
        if ".이전-" in f.parent.name:
            continue
        try:
            info = json.loads(f.read_text(encoding="utf-8"))
        except ValueError:
            continue
        if (f.parent / "form.hwpx").is_file() or (f.parent / "spec.json").is_file():
            out.append({**info, "slug": f.parent.name, "preview": sorted(p.name for p in (f.parent / "preview").glob("page-*.png"))})
    return out


def _preview(dest, file):
    """등록한 양식의 빈 양식 쪽 그림(dest/preview). 한글이 없거나 실패하면 그림 없이 넘어간다."""
    import hangul
    if os.environ.get("KOSCOM_STUDIO_NO_PREVIEW") or not hangul.available():
        return
    try:
        run_hangul("pages", file, dest / "preview")
    except RuntimeError:
        pass


def form_dir(slug, lib=None):
    d = Path(lib or LIB) / safe_name(slug, 40)
    if not (d / "form.json").is_file():
        raise ValueError(f"등록된 양식이 없습니다: {slug}")
    return d


def _target(name, replace, lib):
    name = str(name or "").strip()
    slug = safe_name(name, 40)
    if not slug:
        raise ValueError("양식 이름을 적어 주세요")
    dest = Path(lib or LIB) / slug
    if dest.exists() and not replace:
        raise ValueError(f"같은 이름의 양식이 이미 있습니다: {name}")
    return name, slug, dest


def _save_info(dest, slug, name, desc, names, source, made_from):
    info = {"name": name, "description": str(desc or "").strip(), "fields": names, "source": source, "made_from": made_from,
            "created_at": datetime.now().astimezone().replace(microsecond=0).isoformat()}
    (dest / "form.json").write_text(json.dumps(info, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return {**info, "slug": slug}


def _fresh(dest):
    """같은 이름을 바꿔 등록할 때 이전 판은 지우지 않고 옆에 옮겨 둔다."""
    if dest.exists():
        dest.rename(dest.with_name(f"{dest.name}.이전-{datetime.now():%Y%m%d-%H%M%S}"))
    dest.mkdir(parents=True)


def register(src, name, desc="", replace=False, lib=None):
    """{{항목}} 자리표시를 넣은 한글 파일을 양식으로 등록한다(.hwp 는 한글로 .hwpx 로 바꿔 둔다)."""
    src = Path(src)
    name, slug, dest = _target(name, replace, lib)
    if src.suffix.lower() not in (".hwpx", ".hwp"):
        raise ValueError("한글 파일(.hwpx 또는 .hwp)만 등록할 수 있습니다")
    with tempfile.TemporaryDirectory() as td:
        hwpx = src
        if src.suffix.lower() == ".hwp":
            hwpx = Path(td) / "form.hwpx"
            run_hangul("convert", src, hwpx)
        try:
            names = fields(hwpx)
        except zipfile.BadZipFile:
            raise ValueError("한글 .hwpx 파일이 아닙니다(손상됐거나 다른 형식)")
        if not names:
            raise ValueError("양식에 {{항목}} 자리표시가 없습니다. 채울 자리에 {{성명}}처럼 적어 저장한 뒤 올리세요")
        _fresh(dest)
        shutil.copyfile(hwpx, dest / "form.hwpx")
    info = _save_info(dest, slug, name, desc, names, src.name, "file")
    _preview(dest, dest / "form.hwpx")
    return info


def spec_fields(spec):
    names = []
    for m in PH.finditer(json.dumps(spec, ensure_ascii=False)):
        if m.group(1) not in names:
            names.append(m.group(1))
    return names


def _subst(v, values):
    if isinstance(v, str):
        return PH.sub(lambda m: values[m.group(1)] if values.get(m.group(1)) else m.group(0), v)
    if isinstance(v, list):
        return [_subst(x, values) for x in v]
    if isinstance(v, dict):
        return {k: _subst(x, values) for k, x in v.items()}
    return v


def make(spec, name, desc="", replace=False, lib=None):
    """자연어 요청을 정리한 양식 사양(report.json, 채울 자리는 {{항목}})을 렌더러로 그려 보고 양식으로 등록한다."""
    spec = Path(spec).resolve()
    s = json.loads(spec.read_text(encoding="utf-8"))
    names = spec_fields(s)
    if not names:
        raise ValueError("사양에 {{항목}} 자리표시가 없습니다")
    name, slug, dest = _target(name, replace, lib)
    with tempfile.TemporaryDirectory() as td:
        hwp = Path(td) / "form.hwp"
        r = render_report(spec, hwp)
        if not r["ok"]:
            raise ValueError("렌더러 오류: " + " / ".join(r["errors"] or r["warns"][-3:] or ["출력 없음"]))
        _fresh(dest)
        shutil.copyfile(hwp, dest / "form.hwp")
    (dest / "spec.json").write_text(json.dumps(s, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    info = _save_info(dest, slug, name, desc, names, spec.name, "spec")
    _preview(dest, dest / "form.hwp")
    return {**info, "warns": r["warns"]}


def produce(slug, values, out_base, lib=None):
    """등록한 양식을 values 로 채워 out_base(.hwp 또는 .hwpx)로 만든다. 빈 값은 {{항목}}으로 남긴다."""
    d = form_dir(slug, lib)
    values = {str(k): str(v) for k, v in (values or {}).items() if v is not None and str(v).strip()}
    out_base = Path(out_base)
    out_base.parent.mkdir(parents=True, exist_ok=True)
    if (d / "spec.json").is_file():
        filled = _subst(json.loads((d / "spec.json").read_text(encoding="utf-8")), values)
        tmp = out_base.with_name(out_base.name + ".spec.json")
        tmp.write_text(json.dumps(filled, ensure_ascii=False, indent=1), encoding="utf-8")
        out = out_base.with_suffix(".hwp")
        r = render_report(tmp, out)
        return {**r, "file": str(out), "left": spec_fields(filled)}
    out = out_base.with_suffix(".hwpx")
    return {"ok": True, "warns": [], "errors": [], "file": str(out), "left": fill(d / "form.hwpx", values, out)}


# ---------------------------------------------------------------- 사람이 덜 치게: 자동 채우기·합계·자주 쓰는 양식
def sums(spec):
    """사양 표에서 첫 칸이 「합계」인 행의 {{항목}} = 같은 열 위쪽 {{항목}}들의 합. 예: {"합계": ["교통비", "숙박비"]}"""
    out = {}
    if isinstance(spec, dict):
        rows = spec.get("rows") if spec.get("type") == "table" else None
        for i, r in enumerate(rows if isinstance(rows, list) else []):
            if not (isinstance(r, list) and r and str(r[0]).replace(" ", "") in SUM_ROW):
                continue
            for c, cell in enumerate(r):
                m = PH.fullmatch(str(cell))
                if not m:
                    continue
                ops = [PH.fullmatch(str(x[c])).group(1) for x in rows[int(spec.get("header_rows") or 0):i]
                       if isinstance(x, list) and c < len(x) and PH.fullmatch(str(x[c]))]
                if ops:
                    out[m.group(1)] = ops
        for v in spec.values():
            out.update(sums(v))
    elif isinstance(spec, list):
        for v in spec:
            out.update(sums(v))
    return out


def form_sums(d):
    """등록한 양식의 합계 칸(말로 만든 양식만 표 구조를 안다). ponytail: 올린 .hwpx 양식은 표를 읽지 않아 합계 자동 계산이 없다"""
    spec = Path(d) / "spec.json"
    return sums(json.loads(spec.read_text(encoding="utf-8"))) if spec.is_file() else {}


def _num(s):
    s = str(s or "").strip()
    if s in ("", "-"):
        return 0
    t = s.replace(",", "").replace(" ", "").removesuffix("원")
    return int(t) if re.fullmatch(r"-?\d+", t) else None


def profile_path(lib=None):
    return Path(lib or LIB) / "_profile.json"  # 양식 목록은 */form.json 만 보므로 섞이지 않는다


def remember(values, lib=None):
    """최종본에 쓴 사람·부서 칸 값을 기억한다(다음 양식의 같은 이름 칸에 먼저 채운다)."""
    p = profile_path(lib)
    prof = json.loads(p.read_text(encoding="utf-8")) if p.is_file() else {}
    for k, v in (values or {}).items():
        v = str(v).strip()
        if PROFILE.search(str(k)) and v and v != "-" and "{{" not in v:
            prof[str(k)] = v
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(prof, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return prof


def autofill(slug, values, lib=None, today=None):
    """빈 칸을 채운다: 신청일·작성일 같은 칸은 오늘, 사람·부서 칸은 지난번 최종본 값, 합계 칸은 위 금액의 합.
    합계는 더할 칸이 모두 숫자(빈 칸·「-」는 0)일 때만 다시 계산한다. 돌려주는 값: (values, {항목: 채운 까닭})"""
    d = form_dir(slug, lib)
    info = json.loads((d / "form.json").read_text(encoding="utf-8"))
    p = profile_path(lib)
    prof = json.loads(p.read_text(encoding="utf-8")) if p.is_file() else {}
    t = today or datetime.now()
    vals, auto = {str(k): "" if v is None else str(v) for k, v in (values or {}).items()}, {}
    for k in info.get("fields", []):
        if vals.get(k, "").strip():
            continue
        if TODAY.search(k):
            vals[k], auto[k] = f"{t.year}. {t.month}. {t.day}", "오늘 날짜"
        elif PROFILE.search(k) and prof.get(k):
            vals[k], auto[k] = prof[k], "지난번 값"
    for k, ops in form_sums(d).items():
        for o in ops:  # 금액 칸 자릿수 쉼표(90000 → 90,000)
            if re.fullmatch(r"\d{4,}", vals.get(o, "").strip()):
                vals[o] = f"{int(vals[o]):,}"
        ns = [_num(vals.get(o)) for o in ops]
        if None in ns or all(vals.get(o, "").strip() in ("", "-") for o in ops):
            continue
        total = f"{sum(ns):,}"
        if vals.get(k) != total:
            vals[k], auto[k] = total, "자동 계산"
    return vals, auto


def presets(lib=None):
    items = json.loads(PRESETS.read_text(encoding="utf-8")) if PRESETS.is_file() else []
    return [{"name": p["name"], "desc": p["desc"], "registered": (Path(lib or LIB) / safe_name(p["name"], 40) / "form.json").is_file()}
            for p in items]


def add_preset(name, lib=None):
    """자주 쓰는 양식을 등록한다(이미 있으면 그것을 돌려준다)."""
    p = next((x for x in (json.loads(PRESETS.read_text(encoding="utf-8")) if PRESETS.is_file() else []) if x["name"] == name), None)
    if not p:
        raise ValueError(f"자주 쓰는 양식에 없습니다: {name}")
    d = Path(lib or LIB) / safe_name(name, 40)
    if (d / "form.json").is_file():
        return {**json.loads((d / "form.json").read_text(encoding="utf-8")), "slug": d.name}
    return make(PRESETS.parent / p["spec"], p["name"], p["desc"], lib=lib)


def render_report(json_path, out, cwd=None):
    """report.json → .hwp (scripts/render.ps1). 그림 상대 경로는 cwd(기본: json 폴더) 기준. 돌려주는 값: ok·warns·errors."""
    env = dict(os.environ)
    vendor = Path.home() / ".agents" / "vendor" / "jdk-17.0.21+9-jre"  # 이 PC의 JRE(PATH 에 java 가 없을 때)
    if not env.get("JAVA_HOME") and (vendor / "bin" / "java.exe").exists():
        env["JAVA_HOME"] = str(vendor)
    r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / "render.ps1"),
                        "-Json", str(json_path), "-Out", str(out)], capture_output=True, timeout=300, env=env,
                       cwd=str(cwd or Path(json_path).parent))
    lines = [l.strip() for l in r.stdout.decode("utf-8", "replace").splitlines() if l.strip()]
    errs = [l for l in lines if l.startswith("ERROR")] + ([r.stderr.decode("utf-8", "replace")[-300:]] if r.returncode and not lines else [])
    return {"ok": r.returncode == 0 and bool(lines) and lines[-1].startswith("OK"), "warns": [l for l in lines if l.startswith("WARN")], "errors": errs}


# ---------------------------------------------------------------- 자체 검사
def _selftest():
    os.environ["KOSCOM_STUDIO_NO_PREVIEW"] = "1"  # 가짜 .hwpx 라 한글 미리보기는 하지 않는다
    sec = ('<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><hs:sec xmlns:hs="urn:s" xmlns:hp="urn:p">'
           '<hp:p id="1"><hp:run><hp:t>성명: {{성명}}</hp:t></hp:run><hp:linesegarray><hp:lineseg/></hp:linesegarray></hp:p>'
           '<hp:p id="2"><hp:run><hp:t>{{기</hp:t></hp:run><hp:run><hp:t>간}} 까지</hp:t></hp:run></hp:p>'
           '<hp:p id="3"><hp:run><hp:t>{{비고}}</hp:t></hp:run></hp:p>'
           '<hp:p id="4"><hp:run><hp:tbl><hp:tc><hp:subList><hp:p id="5"><hp:run><hp:t>{{ 칸 }}</hp:t></hp:run>'
           '<hp:linesegarray/></hp:p></hp:subList></hp:tc></hp:tbl></hp:run><hp:linesegarray/></hp:p></hs:sec>')
    with tempfile.TemporaryDirectory() as td:
        src, dst = Path(td) / "f.hwpx", Path(td) / "o.hwpx"
        with zipfile.ZipFile(src, "w") as z:
            z.writestr("mimetype", "application/hwp+zip")
            z.writestr("Contents/section0.xml", sec)
            z.writestr("Preview/PrvText.txt", "성명: {{성명}}")
        assert fields(src) == ["성명", "기간", "비고", "칸"], fields(src)
        left = fill(src, {"성명": "홍길동 & <팀>", "기간": "11. 3\n11. 5", "칸": "값"}, dst)
        assert left == ["비고"], left
        with zipfile.ZipFile(dst) as z:
            assert z.namelist()[0] == "mimetype" and z.getinfo("mimetype").compress_type == zipfile.ZIP_STORED
            x = z.read("Contents/section0.xml").decode("utf-8")
            assert z.read("Preview/PrvText.txt").decode("utf-8") == "성명: 홍길동 & <팀>"
        assert "성명: 홍길동 &amp; &lt;팀&gt;" in x and "11. 3<hp:lineBreak/>11. 5 까지" in x and ">값<" in x, x
        assert "{{비고}}" in x and x.count("<hp:linesegarray") == 1, x  # 바꾼 문단(1·2·5)만 캐시 삭제, 바깥 표 문단(4)은 유지
        lib = Path(td) / "lib"
        info = register(src, "시험 양식", "설명", lib=lib)
        assert info["slug"] == "시험 양식" and list_forms(lib)[0]["fields"] == ["성명", "기간", "비고", "칸"]
        try:
            register(src, "시험 양식", lib=lib)
            raise AssertionError("같은 이름 등록이 막히지 않음")
        except ValueError:
            pass
        register(src, "시험 양식", replace=True, lib=lib)  # 이전 판은 옆에 남고 목록에는 하나만
        assert [f["slug"] for f in list_forms(lib)] == ["시험 양식"] and len(list(lib.iterdir())) == 2
        r = produce("시험 양식", {"성명": "김", "기간": "  ", "칸": "x"}, Path(td) / "out" / "결과", lib=lib)
        assert r["file"].endswith("결과.hwpx") and r["left"] == ["기간", "비고"], r  # 빈 값은 자리표시로 남는다
    spec = {"title": "{{제목}} 신청서", "approval": {"담당": "{{담당}}"}, "rows": [["{{칸}}", "^"]]}
    assert spec_fields(spec) == ["제목", "담당", "칸"]
    assert _subst(spec, {"제목": "출장", "칸": "", "담당": "홍(1)"}) == {"title": "출장 신청서", "approval": {"담당": "홍(1)"}, "rows": [["{{칸}}", "^"]]}
    # 자동 채우기: 합계 행 찾기 → 오늘 날짜·지난번 값·합계
    sample = json.loads((SKILL / "references/examples/form_sample.json").read_text(encoding="utf-8"))
    assert sums(sample) == {"합계": ["교통비", "숙박비"]}, sums(sample)
    for p in json.loads(PRESETS.read_text(encoding="utf-8")):  # 자주 쓰는 양식 사양이 있고 항목이 있다
        assert spec_fields(json.loads((PRESETS.parent / p["spec"]).read_text(encoding="utf-8"))), p
    assert sums(json.loads((PRESETS.parent / "물품_구매_요청서.json").read_text(encoding="utf-8"))) == {"합계": ["금액 1", "금액 2", "금액 3"]}
    with tempfile.TemporaryDirectory() as td:
        lib = Path(td)
        d = lib / "출장"
        d.mkdir()
        (d / "spec.json").write_text(json.dumps(sample, ensure_ascii=False), encoding="utf-8")
        (d / "form.json").write_text(json.dumps({"name": "출장", "fields": spec_fields(sample)}, ensure_ascii=False), encoding="utf-8")
        t = datetime(2026, 11, 10)
        v, a = autofill("출장", {"교통비": "3,100", "숙박비": "-"}, lib, t)
        assert v["신청일"] == "2026. 11. 10" and v["합계"] == "3,100" and a == {"신청일": "오늘 날짜", "합계": "자동 계산"} and "담당" not in v, (v, a)
        remember({"담당": "홍길동(1234)", "부서": "경영지원부", "목적": "점검", "팀장": "-"}, lib)
        v, a = autofill("출장", {"부서": "정보보호부", "교통비": "119,600원", "숙박비": "90000", "합계": "1"}, lib, t)
        assert v["담당"] == "홍길동(1234)" and a["담당"] == "지난번 값" and v["부서"] == "정보보호부" and "팀장" not in a and "목적" not in v, (v, a)
        assert v["합계"] == "209,600" and v["숙박비"] == "90,000", v  # 금액을 고치면 합계도 다시, 금액엔 자릿수 쉼표
        v, a = autofill("출장", {"교통비": "법인카드", "합계": "직접 적은 값"}, lib, t)
        assert v["합계"] == "직접 적은 값" and "합계" not in a, v  # 숫자가 아니면 사람이 적은 합계를 둔다
    print("form_tool selftest OK")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if "--selftest" in sys.argv:
        return _selftest()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["list", "fields", "register", "make", "fill", "preset"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--name")
    ap.add_argument("--desc", default="")
    ap.add_argument("--replace", action="store_true")
    a = ap.parse_args()
    try:
        if a.cmd == "list":
            out = list_forms()
        elif a.cmd == "fields":
            out = fields(a.args[0])
        elif a.cmd == "register":
            out = register(a.args[0], a.name, a.desc, a.replace)
        elif a.cmd == "make":
            out = make(a.args[0], a.name, a.desc, a.replace)
        elif a.cmd == "preset":
            out = add_preset(a.args[0]) if a.args else presets()
        else:
            values = json.loads(Path(a.args[1]).read_text(encoding="utf-8"))
            if a.args[0].lower().endswith(".hwpx"):
                dst = Path(a.args[2]).with_suffix(".hwpx")
                out = {"ok": True, "file": str(dst), "left": fill(a.args[0], values, dst)}
            else:
                out = produce(a.args[0], values, a.args[2])
    except (ValueError, RuntimeError, IndexError, OSError) as e:
        print(json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False))
        return 1
    print(json.dumps(out, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
