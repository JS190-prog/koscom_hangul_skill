#!/usr/bin/env python3
"""한글 문서 스튜디오 — 스킬을 부르면 브라우저에서 만들 문서(보고서·기안문·투자계획서·양식)와 서식을 고르고,
만든 문서를 쪽 그림으로 미리 보며 고쳐 다시 만들고, 검사를 통과한 판만 최종본(_verN)으로 남긴다.
koscom-webslide 스튜디오(deck-design-pack 확인 페이지 흐름: 브라우저에서 고름 → 결과 파일을 에이전트가 읽음)와 같은 구조다.

  python studio.py <프로젝트> [--kind report|gian|invest|form]  서버를 띄우고 브라우저를 연다(에이전트는 배경으로 실행)
  python studio.py <프로젝트> --wait        브라우저에서 고를 때까지 기다렸다가 studio/choice.json 을 출력한다
  python studio.py <프로젝트> --choose 초안.json   자료가 있을 때 에이전트가 서식 칸(kind·title·options·memo·basis)을
                                            먼저 채워 기록한다. 화면에는 「AI가 먼저 작성한 값」으로 채워져 보인다
  python studio.py <프로젝트> --render      doc/ 의 원천으로 작업본과 쪽 그림을 만든다(에이전트가 원천을 쓴 뒤)
  python studio.py <프로젝트> --form <이름> 등록한 양식을 이 프로젝트에서 채우기 시작한다
  python studio.py <프로젝트> --finalize    최종본 만들기(브라우저의 「최종본 저장」과 같다)
  python studio.py --samples                버튼마다 보여 주는 샘플 양식 쪽 그림(studio/samples)을 다시 만든다.
                                            예시(references/examples)를 고쳤을 때만. Java·한글·Pillow·기안문 스킬 필요

프로젝트 폴더
  doc/report.json      보고서·투자계획서·기안문 본문(이 스킬의 렌더러)        → doc/작업본.hwp
  doc/source.json      결재란까지 갖춘 기안문(koscom-gian-from-attachments) → doc/작업본.hwpx
  doc/form.json        {"form": 양식 이름, "title": …, "values": {…}}      → doc/작업본.hwp|.hwpx
  facts.txt · voice/   (있으면) 최종본 검사에서 숫자 근거 · 문체 기준으로 쓴다(report.json)
  studio/choice.json   고른 문서·서식   studio/render.json 마지막 결과   studio/preview/ 쪽 그림   studio/history/ 이전 판
  exports/<제목>_verN.hwp|.hwpx (+ .json 검사 기록)
보안: 127.0.0.1 에만 열고, 바꾸는 요청(POST)은 이 서버가 낸 토큰 헤더와 Host 가 맞아야 받는다.
"""
import argparse
import hashlib
import json
import mimetypes
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlparse
import urllib.request

HERE = Path(__file__).resolve().parent
SKILL = HERE.parent
UI = SKILL / "studio"
sys.path.insert(0, str(HERE))
import form_tool  # noqa: E402
import hangul  # noqa: E402

MAX_BODY = 40 * 1024 * 1024
KINDS = {"report": "보고서", "gian": "기안문", "invest": "투자계획서", "form": "양식", "form-new": "새 양식"}
VOICE = {"summary": "보고서", "detail": "투자계획서", "draft": "기안문"}
LOCK = threading.Lock()
SAMPLES = [  # (버튼, 이름, 설명, 원천) — 원천 앞 "gian:" 은 기안문 스킬 폴더 기준, "fill:" 은 사양|값
    ("report", "요약보고 샘플", "원본 보고서 양식의 Sample(결과보고)을 옮긴 예 — 제목 박스·결재라인·□ ㅇ - ··표·참고 박스",
     "references/examples/summary_sample.json"),
    ("invest", "상세보고 샘플", "원본 투자계획서 양식의 Sample — 표지·목차·장(I, II)과 절 박스·그림",
     "references/examples/detail_sample.json"),
    ("gian", "결재란까지 갖춘 기안문", "첨부 4건(근거 공문·보고서·투자계획서·견적서)으로 만든 시연용 기안 — 수신·결재·협조·시행 칸까지",
     "gian:examples/edr/source.json"),
    ("gian", "기안문 본문만", "전자결재 본문 칸에 붙이는 관련근거 → 요청 문장 → 아래 → 번호 항목",
     "references/examples/draft_sample.json"),
    ("form", "말로 만든 양식: 국내 출장 신청서", "「결재라인, 출장자·기간 표, 경비 표, 특이 사항」이라고 설명해 만든 빈 양식",
     "references/examples/form_sample.json"),
    ("form", "같은 양식을 채운 모습", "값을 넣으면 렌더러가 칸 너비·자간·줄을 값 길이에 맞춰 다시 그린다",
     "fill:references/examples/form_sample.json|references/examples/form_sample_values.json"),
]
SAMPLE_PAGES = 6  # 샘플마다 보여 줄 최대 쪽 수


def now():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def stamp():
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def _text(v, n):
    return str(v or "").strip()[:n]


def read_json(p, default=None):
    try:
        return json.loads(Path(p).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def write_json(p, obj):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + ".saving")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    os.replace(tmp, p)


def gian_skill():
    """결재란까지 채우는 기안문 스킬(있으면 기안문 버튼이 그것을 쓴다)."""
    home = Path.home()
    for p in (os.environ.get("KOSCOM_GIAN_SKILL"), home / ".agents/skills/koscom-gian-from-attachments",
              home / ".claude/skills/koscom-gian-from-attachments", SKILL.parent / "koscom_gian_skill"):
        if p and (Path(p) / "scripts/build.js").is_file() and (Path(p) / "node_modules").is_dir() and shutil.which("node"):
            return Path(p)
    return None


def doc_source(proj):
    d = proj / "doc"
    for name, kind in (("form.json", "form"), ("source.json", "gian"), ("report.json", "report")):
        if (d / name).is_file():
            return d / name, kind
    return None, None


def doc_title(kind, data):
    if kind == "gian":
        return _text((data.get("header") or {}).get("title"), 80)
    if kind == "form":
        return _text(data.get("title"), 80) or _text(data.get("form"), 80)
    return _text(data.get("title"), 80).replace("\n", " ")


def archive_doc(proj):
    """doc/ 을 studio/history/doc-<시각> 으로 옮긴다(지우지 않는다)."""
    d = proj / "doc"
    if d.is_dir() and any(d.iterdir()):
        hist = proj / "studio" / "history"
        hist.mkdir(parents=True, exist_ok=True)
        dest = hist / f"doc-{stamp()}"
        shutil.move(str(d), str(dest))
        return dest
    return None


def clear_work(proj):
    """작업 칸을 비운다(한 번에 문서 하나): doc/ 과 고른 값·결과 기록·쪽 그림을 studio/history/doc-<시각>/ 으로 옮긴다.
    지우지 않으며 최종본(exports/)은 그대로 둔다."""
    dest = archive_doc(proj)
    st = proj / "studio"
    extra = [p for p in (st / "choice.json", st / "render.json", st / "preview") if p.exists()]
    if extra:
        if dest is None:
            dest = st / "history" / f"doc-{stamp()}"
            dest.mkdir(parents=True, exist_ok=True)
        for p in extra:
            shutil.move(str(p), str(dest / f"studio-{p.name}"))
    return dest


# ---------------------------------------------------------------- 만들기(작업본 + 쪽 그림)
def _lines(r):
    return [l.strip() for l in (r.stdout + b"\n" + r.stderr).decode("utf-8", "replace").splitlines() if l.strip()]


def _render(proj):
    src, kind = doc_source(proj)
    res = {"ok": False, "kind": kind, "source": src.name if src else None, "file": None, "title": "", "warns": [],
           "errors": [], "check_ok": True, "left": [], "auto": {}, "pages": [], "preview_error": None, "at": now()}
    if not src:
        res["errors"].append("아직 원천이 없습니다. 서식을 고르면 에이전트가 doc/ 에 원천을 씁니다")
        return res
    data = read_json(src)
    if not isinstance(data, dict):
        res["errors"].append(f"{src.name} 를 읽지 못했습니다(JSON 형식 오류)")
        return res
    res["title"] = doc_title(kind, data)
    d, out = proj / "doc", None
    if kind == "report":
        out = d / "작업본.hwp"
        r = form_tool.render_report(src, out)
        res.update(ok=r["ok"], warns=r["warns"], errors=r["errors"])
    elif kind == "gian":
        gs = gian_skill()
        if not gs:
            res["errors"].append("기안문 스킬(koscom-gian-from-attachments)이나 node 가 없습니다. 본문만 만들려면 report.json(type draft)을 쓰세요")
            return res
        out = d / "작업본.hwpx"
        c = subprocess.run(["node", str(gs / "scripts/check.js"), str(src)], capture_output=True, timeout=120)
        cl = _lines(c)
        res["warns"] += [l for l in cl if l.startswith("WARN")]
        res["check_ok"] = c.returncode == 0
        res["errors"] += ["[검사] " + l for l in cl if l.startswith("ERROR")]
        b = subprocess.run(["node", str(gs / "scripts/build.js"), str(src), str(out)], capture_output=True, timeout=180)
        bl = _lines(b)
        res["ok"] = b.returncode == 0
        res["errors"] += [l for l in bl if l.startswith("ERROR")] or ([] if res["ok"] else bl[-3:])
    else:
        try:  # 빈 날짜·사람·부서 칸과 합계는 채워서 원천에도 남긴다(화면 칸에 그대로 보이게)
            vals, res["auto"] = form_tool.autofill(data.get("form"), data.get("values"))
            if vals != data.get("values"):
                data["values"] = vals
                write_json(src, data)
            r = form_tool.produce(data.get("form"), vals, d / "작업본")
        except ValueError as e:
            res["errors"].append(str(e))
            return res
        out = Path(r["file"])
        res.update(ok=r["ok"], warns=r["warns"], errors=r["errors"], left=r["left"])
    if not res["ok"]:
        return res
    for old in d.glob("작업본.*"):  # 형식이 바뀌었으면 다른 확장자의 이전 작업본은 치운다
        if old.suffix.lower() in (".hwp", ".hwpx") and old != out:
            old.unlink()
    res["file"] = out.name
    if os.environ.get("KOSCOM_STUDIO_NO_PREVIEW") or not hangul.available():
        res["preview_error"] = "이 PC에 한글이 없어 쪽 그림을 만들지 못했습니다(파일은 만들어졌습니다)"
    else:
        try:
            res["pages"] = form_tool.run_hangul("pages", out, proj / "studio" / "preview")["pages"]
        except RuntimeError as e:
            res["preview_error"] = f"한글이 작업본을 열지 못했습니다: {e}"
    return res


def render(proj):
    with LOCK:
        res = _render(proj)
        write_json(proj / "studio" / "render.json", res)
        return res


# ---------------------------------------------------------------- 상태·선택·저장
def state(proj, hint=None):
    src, kind = doc_source(proj)
    data = read_json(src) if src else None
    form = None
    if kind == "form" and isinstance(data, dict):
        try:
            fd = form_tool.form_dir(data.get("form"))
            form = {**read_json(fd / "form.json", {}), "slug": data.get("form"), "sums": form_tool.form_sums(fd)}
        except ValueError:
            form = None
    exports = proj / "exports"
    return {"project": str(proj), "hint": hint, "choice": read_json(proj / "studio" / "choice.json"),
            "doc": {"kind": kind, "name": src.name, "data": data} if src else None, "form": form,
            "render": read_json(proj / "studio" / "render.json"), "forms": form_tool.list_forms(), "presets": form_tool.presets(),
            "exports": sorted((p.name for p in exports.iterdir() if p.suffix.lower() in (".hwp", ".hwpx")),
                              key=lambda n: (n.rsplit("_ver", 1)[0], len(n), n)) if exports.is_dir() else [],
            "gian": bool(gian_skill()), "hangul": hangul.available(), "busy": LOCK.locked()}


def use_form(proj, slug, title=""):
    info = read_json(form_tool.form_dir(slug) / "form.json", {})
    src, _ = doc_source(proj)
    if src:
        archive_doc(proj)
    write_json(proj / "doc" / "form.json", {"form": slug, "title": _text(title, 80) or info.get("name", slug), "values": {}})
    return render(proj)


def choose(proj, req, by="user"):
    """고른 문서·서식을 choice.json 에 남긴다. by=agent 는 에이전트가 자료로 먼저 채운 초안(화면에 그 값이 채워져 보인다)."""
    kind = req.get("kind")
    if kind not in KINDS:
        raise ValueError("만들 문서를 고르세요(보고서·기안문·투자계획서·양식)")
    choice = {"kind": kind, "label": KINDS[kind], "title": _text(req.get("title"), 80), "memo": _text(req.get("memo"), 20000),
              "options": {str(k)[:40]: _text(v, 300) for k, v in (req.get("options") or {}).items()},
              "by": by, "basis": _text(req.get("basis"), 300) if by == "agent" else "", "created_at": now()}
    if kind == "form":
        choice["form"] = _text(req.get("form"), 60)
        form_tool.form_dir(choice["form"])  # 없으면 ValueError
    if kind == "form-new":
        choice["name"], choice["description"] = _text(req.get("name"), 40), _text(req.get("description"), 5000)
        if not choice["name"] or not choice["description"]:
            raise ValueError("새 양식의 이름과 설명을 적어 주세요")
    if kind == "gian" and choice["options"].get("mode") != "body" and not gian_skill():
        choice["options"]["mode"] = "body"  # 기안문 스킬이 없으면 본문만
    moved = clear_work(proj) if kind != "form-new" else None  # 한 번에 문서 하나: 새로 고르면 지금 작업 칸을 비운다
    choice["previous_doc"] = str(moved) if moved else None
    write_json(proj / "studio" / "choice.json", choice)
    if kind == "form":
        use_form(proj, choice["form"], choice["title"])
    return {"ok": True, "choice": choice, "state": state(proj)}


def open_export(proj, req):
    """최종본을 이 PC의 한글로 열거나(open) 탐색기에서 보여 준다(folder). exports 안의 한글 파일만."""
    ex = (proj / "exports").resolve()
    p = (ex / str(req.get("file") or "")).resolve()
    if p.parent != ex or p.suffix.lower() not in (".hwp", ".hwpx") or not p.is_file():
        raise ValueError("최종본 파일을 찾지 못했습니다")
    how = req.get("how")
    if os.environ.get("KOSCOM_STUDIO_DRY_OPEN"):  # 자체 검사: 실제로 프로그램을 띄우지 않는다
        return {"ok": True, "file": str(p), "how": how}
    if how == "folder":
        subprocess.Popen(["explorer", "/select,", str(p)])
    else:
        os.startfile(str(p))  # 기본 프로그램(한글)
    return {"ok": True, "file": str(p), "how": how}


def save_doc(proj, req):
    """브라우저에서 고친 원천을 저장하고 다시 만든다. 바꾸기 전 원천은 studio/history 에 남긴다."""
    src, kind = doc_source(proj)
    if not src:
        raise ValueError("고칠 원천이 없습니다. 먼저 서식을 고르세요")
    data = req.get("data")
    if not isinstance(data, dict):
        raise ValueError("원천은 JSON 객체여야 합니다")
    if kind == "form":
        old = read_json(src, {})
        vals = data.get("values") or {}
        if not isinstance(vals, dict):
            raise ValueError("values 는 {항목: 값} 이어야 합니다")
        data = {"form": old.get("form"), "title": _text(data.get("title"), 80) or old.get("title", ""),
                "values": {str(k): str(v) for k, v in vals.items()}}
    elif kind == "gian" and data.get("schema") != "gian/1.0":
        raise ValueError('기안문 원천의 schema 는 "gian/1.0" 이어야 합니다')
    hist = proj / "studio" / "history"
    hist.mkdir(parents=True, exist_ok=True)
    backup = hist / f"{src.stem}-{stamp()}.json"
    shutil.copy2(src, backup)
    write_json(src, data)
    return {"ok": True, "backup": str(backup), "render": render(proj), "state": state(proj)}


# ---------------------------------------------------------------- 최종본
def safe_name(title):
    return form_tool.safe_name(title) or "문서"


def next_version(exports, name):
    nums = [int(m.group(1)) for p in exports.glob(f"{glob_escape(name)}_ver*")
            if (m := re.fullmatch(re.escape(name) + r"_ver(\d+)\.(hwp|hwpx|json)", p.name))]
    return max(nums, default=0) + 1


def glob_escape(s):
    return re.sub(r"([\[\]*?])", r"[\1]", s)


def gate(proj, src):
    """report.json 문체·숫자 검사. 기본 문체가 표본 1건이면 [문체]는 참고만 한다(SKILL.md C)."""
    data = read_json(src, {})
    voice = proj / "voice" if (proj / "voice" / "fingerprint-slim.json").exists() else SKILL / "templates" / "voice" / VOICE.get(data.get("type", "summary"), "보고서")
    cmd = [sys.executable, str(HERE / "voice" / "voice_tool.py"), "gate", "--voice", str(voice), str(src)]
    if (proj / "facts.txt").is_file():
        cmd[5:5] = ["--facts", str(proj / "facts.txt")]
    r = subprocess.run(cmd, capture_output=True, timeout=300)
    try:
        g = json.loads(r.stdout.decode("utf-8"))
    except ValueError:
        return {"pass": False, "blocking": ["문체 검사를 실행하지 못했습니다: " + r.stderr.decode("utf-8", "replace")[-300:]], "rework": []}
    blocking = [x for x in g.get("rework", []) if not (g.get("reference_only") and x.startswith("[문체]"))]
    return {"pass": not blocking, "label": g.get("label"), "facts": (proj / "facts.txt").is_file(), "voice": voice.name,
            "blocking": blocking, "rework": g.get("rework", [])}


def finalize(proj):
    r = render(proj)
    if not r["ok"]:
        return {"ok": False, "stage": "만들기", "problems": r["errors"] or ["작업본을 만들지 못했습니다"]}
    src, kind = doc_source(proj)
    problems, g = [], None
    if not r["check_ok"]:
        problems += [e for e in r["errors"] if e.startswith("[검사]")]
    if r["left"]:
        problems.append("채우지 않은 항목: " + ", ".join(r["left"]) + " (비워 둘 칸은 - 를 적으세요)")
    if r["preview_error"] and hangul.available() and not os.environ.get("KOSCOM_STUDIO_NO_PREVIEW"):
        problems.append(r["preview_error"])
    if kind == "report":
        g = gate(proj, src)
        problems += g["blocking"]
    if problems:
        return {"ok": False, "stage": "검사", "problems": problems, "gate": g, "warns": r["warns"]}
    work = proj / "doc" / r["file"]
    name = safe_name(r["title"] or KINDS.get(kind, "문서"))
    exports = proj / "exports"
    exports.mkdir(exist_ok=True)
    n = next_version(exports, name)
    final = exports / f"{name}_ver{n}{work.suffix}"
    shutil.copy2(work, final)
    files, notes = [final.name], []
    if work.suffix.lower() == ".hwpx" and hangul.available() and not os.environ.get("KOSCOM_STUDIO_NO_PREVIEW"):
        try:  # 전자결재·메일용 .hwp 도 함께
            form_tool.run_hangul("convert", final, final.with_suffix(".hwp"))
            files.append(final.with_suffix(".hwp").name)
        except RuntimeError as e:
            notes.append(f".hwp 로 바꾸지 못했습니다: {e}")
    rec = {"version": n, "files": files, "title": r["title"], "kind": KINDS.get(kind), "built_at": now(),
           "source_sha256": hashlib.sha256(src.read_bytes()).hexdigest(), "warns": r["warns"], "gate": g,
           "screen_checked": bool(r["pages"]), "notes": notes}
    write_json(final.with_suffix(".json"), rec)
    if kind == "form":  # 사람·부서 칸 값은 다음 양식에 먼저 채운다
        form_tool.remember(read_json(src, {}).get("values"))
    moved = clear_work(proj)  # 문서 하나를 마쳤다: 작업 칸을 비우고 최종본만 남긴다(다음 문서는 다시 요청)
    return {"ok": True, "version": n, "files": files, "warns": r["warns"], "gate": g, "notes": notes,
            "screen_checked": bool(r["pages"]), "cleared": str(moved) if moved else None}


def upload_form(headers, body):
    name = unquote(headers.get("X-Form-Name") or "")
    desc = unquote(headers.get("X-Form-Desc") or "")
    fname = unquote(headers.get("X-File-Name") or "")
    suffix = Path(fname).suffix.lower()
    if suffix not in (".hwpx", ".hwp"):
        raise ValueError("한글 파일(.hwpx 또는 .hwp)만 올릴 수 있습니다")
    with tempfile.TemporaryDirectory() as td:
        f = Path(td) / ("upload" + suffix)
        f.write_bytes(body)
        info = form_tool.register(f, name or Path(fname).stem, desc)
    return {"ok": True, "form": info, "forms": form_tool.list_forms()}


# ---------------------------------------------------------------- 서버
class Handler(BaseHTTPRequestHandler):
    proj = token = port = hint = None  # serve() 가 채운다

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

    def _file(self, base, rel, download=False):
        base = base.resolve()
        p = (base / rel).resolve()
        if p == base or base not in p.parents or not p.is_file():  # ../ 로 밖으로 나가는 경로 포함
            return self._json(404, {"error": "not found"})
        ctype = mimetypes.guess_type(p.name)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json"):
            ctype += "; charset=utf-8"
        extra = {"Content-Disposition": f"attachment; filename*=UTF-8''{quote(p.name)}"} if download else None
        self._send(200, p.read_bytes(), ctype, extra)

    def _host_ok(self):
        return self.headers.get("Host") in (f"127.0.0.1:{self.port}", f"localhost:{self.port}")

    def do_GET(self):
        if not self._host_ok():
            return self._json(403, {"error": "host"})
        path = unquote(urlparse(self.path).path)
        if path in ("/", "/index.html"):
            return self._file(UI, "index.html")
        if path.startswith("/ui/"):
            return self._file(UI, path[4:])
        if path == "/api/token":  # 같은 출처 페이지만 응답을 읽을 수 있다
            return self._send(200, self.token.encode(), "text/plain; charset=utf-8")
        if path == "/api/state":
            return self._json(200, state(self.proj, self.hint))
        if path.startswith("/preview/"):
            return self._file(self.proj / "studio" / "preview", path[len("/preview/"):])
        if path.startswith("/exports/"):
            return self._file(self.proj / "exports", path[len("/exports/"):], download=True)
        if path.startswith("/forms/"):  # 등록한 양식의 빈 양식 쪽 그림
            slug, _, rest = path[len("/forms/"):].partition("/")
            return self._file(form_tool.LIB / form_tool.safe_name(slug, 40) / "preview", rest)
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._host_ok() or self.headers.get("X-Studio-Token") != self.token:
            return self._json(403, {"ok": False, "error": "스튜디오 토큰이 맞지 않습니다. 스튜디오 페이지를 새로 고침하세요"})
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY:
            return self._json(413, {"ok": False, "error": "40MB 를 넘는 파일은 받지 않습니다"})
        body = self.rfile.read(n)
        path = urlparse(self.path).path
        try:
            if path == "/api/form/upload":
                return self._json(200, upload_form(self.headers, body))
            req = json.loads(body or b"{}")
            if path == "/api/choose":
                return self._json(200, choose(self.proj, req))
            if path == "/api/form/preset":  # 자주 쓰는 양식 바로 등록(에이전트 없이)
                info = form_tool.add_preset(_text(req.get("name"), 40))
                return self._json(200, {"ok": True, "form": info, "forms": form_tool.list_forms(), "presets": form_tool.presets()})
            if path == "/api/save":
                return self._json(200, save_doc(self.proj, req))
            if path == "/api/render":
                return self._json(200, {"ok": True, "render": render(self.proj), "state": state(self.proj)})
            if path == "/api/finalize":
                return self._json(200, {**finalize(self.proj), "state": state(self.proj)})
            if path == "/api/open":
                return self._json(200, open_export(self.proj, req))
            if path == "/api/shutdown":  # 화면의 「스튜디오 종료」
                if not LOCK.acquire(blocking=False):  # 만드는 중에 끊으면 작업본이 반쯤 써진 채 남는다
                    raise ValueError("문서를 만드는 중입니다. 끝난 뒤 다시 누르세요")
                self._json(200, {"ok": True})
                threading.Thread(target=self.server.shutdown, daemon=True).start()  # 잠금은 쥔 채 끝낸다
                return
        except (ValueError, RuntimeError, OSError) as e:
            return self._json(400, {"ok": False, "error": str(e)})
        self._json(404, {"ok": False, "error": "not found"})


class StudioServer(ThreadingHTTPServer):
    # Windows 에서 SO_REUSEADDR(파이썬 기본값)는 이미 쓰는 포트에 두 번째 서버가 소리 없이 붙게 한다.
    # 그러면 새로 띄운 서버 대신 옛 서버가 요청을 받는다(실제로 새 단추가 옛 서버에서 "not found"). 포트는 독점으로 잡는다.
    allow_reuse_address = False
    daemon_threads = True

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def stop_previous(proj):
    """이 프로젝트를 띄운 이전 스튜디오가 살아 있으면 닫는다(새 코드로 다시 띄울 때). 닫은 pid 를 돌려준다."""
    info = read_json(proj / "studio" / "server.json") or {}
    if not info.get("url") or not info.get("pid"):
        return None
    try:
        with urllib.request.urlopen(info["url"] + "api/state", timeout=2) as r:
            if Path(json.loads(r.read().decode("utf-8"))["project"]).resolve() != proj.resolve():
                return None
    except (OSError, ValueError, KeyError):
        return None  # 응답이 없으면 이미 닫힌 것
    try:
        os.kill(int(info["pid"]), signal.SIGTERM)  # Windows: TerminateProcess
    except OSError:
        return None
    for _ in range(30):
        try:
            urllib.request.urlopen(info["url"] + "api/token", timeout=0.3)
            time.sleep(0.2)
        except OSError:
            break
    return info["pid"]


def serve(proj, port=0, browser=True, hint=None):
    proj.mkdir(parents=True, exist_ok=True)
    old = stop_previous(proj)
    if old:
        print(json.dumps({"stopped_previous": old}), flush=True)
    try:
        httpd = StudioServer(("127.0.0.1", port), Handler)
    except OSError as e:
        raise SystemExit(f"포트 {port} 를 다른 프로그램이 쓰고 있습니다({e}). 그 프로그램을 닫거나 --port 0(빈 포트)으로 띄우세요")
    Handler.proj, Handler.token, Handler.port, Handler.hint = proj, secrets.token_urlsafe(24), httpd.server_address[1], hint
    url = f"http://127.0.0.1:{Handler.port}/"
    write_json(proj / "studio" / "server.json", {"url": url, "pid": os.getpid()})
    print(json.dumps({"studio": url, "project": str(proj)}, ensure_ascii=False), flush=True)
    if browser:
        webbrowser.open(url)
    return httpd


def wait_choice(proj, timeout):
    """이 명령을 실행한 뒤에 고른 것(choice.json)이 생길 때까지 기다린다."""
    f = proj / "studio" / "choice.json"
    start, deadline = time.time(), time.time() + timeout
    while time.time() < deadline:
        if f.exists() and f.stat().st_mtime >= start - 1:
            print(f.read_text(encoding="utf-8"))
            return 0
        time.sleep(1)
    print(json.dumps({"waiting": True, "msg": "아직 고르지 않았습니다. 페이지는 열려 있으니 --wait 를 다시 실행하세요"}, ensure_ascii=False))
    return 2


def make_samples():
    """SAMPLES 를 그려 studio/samples/<버튼>-<번호>-page-NN.webp 와 samples.json 을 새로 만든다."""
    from PIL import Image
    out = UI / "samples"
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.webp"):
        old.unlink()
    manifest = {}
    with tempfile.TemporaryDirectory() as td:
        for i, (kind, name, desc, src) in enumerate(SAMPLES, 1):
            w = Path(td) / f"s{i}"
            w.mkdir()
            if src.startswith("gian:"):
                gs = gian_skill()
                if not gs:
                    print(f"SKIP {name}: 기안문 스킬 없음")
                    continue
                doc = w / "sample.hwpx"
                r = subprocess.run(["node", str(gs / "scripts/build.js"), str(gs / src[5:]), str(doc)], capture_output=True, timeout=180)
                if r.returncode:
                    raise RuntimeError(f"{name}: {_lines(r)[-2:]}")
            else:
                spec = src[5:].split("|")[0] if src.startswith("fill:") else src
                j = SKILL / spec
                if src.startswith("fill:"):
                    vals = read_json(SKILL / src.split("|")[1], {})
                    j = w / "filled.json"
                    write_json(j, form_tool._subst(read_json(SKILL / spec), vals))
                doc = w / "sample.hwp"
                r = form_tool.render_report(j, doc, cwd=SKILL)
                if not r["ok"]:
                    raise RuntimeError(f"{name}: {r['errors'] or r['warns'][-2:]}")
            pages = form_tool.run_hangul("pages", doc, w / "pages", "--dpi", "100")["pages"]
            names = []
            for p in pages[:SAMPLE_PAGES]:
                n = f"{kind}-{i}-{Path(p).stem}.webp"
                Image.open(w / "pages" / p).convert("RGB").save(out / n, "WEBP", quality=82)
                names.append(n)
            manifest.setdefault(kind, []).append({"name": name, "desc": desc, "pages": names, "total": len(pages)})
            print(f"{name}: {len(pages)}쪽 중 {len(names)}쪽")
    write_json(out / "samples.json", manifest)
    print(f"샘플 -> {out}")
    return 0


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if "--samples" in sys.argv:
        return make_samples()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("project")
    ap.add_argument("--kind", choices=["report", "gian", "invest", "form"], help="처음 펼칠 버튼")
    ap.add_argument("--port", type=int, default=0, help="0 이면 빈 포트")
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--wait", action="store_true", help="고를 때까지 기다린다(서버는 따로 떠 있어야 한다)")
    ap.add_argument("--timeout", type=int, default=540, help="--wait 최대 초(기본 540, 에이전트 도구 한도 아래)")
    ap.add_argument("--render", action="store_true")
    ap.add_argument("--form", metavar="이름")
    ap.add_argument("--finalize", action="store_true")
    ap.add_argument("--choose", metavar="초안.json")
    a = ap.parse_args()
    proj = Path(a.project).resolve()
    if a.wait:
        return wait_choice(proj, a.timeout)
    if a.choose:
        try:
            out = {"ok": True, "choice": choose(proj, read_json(a.choose, {}), by="agent")["choice"]}
        except ValueError as e:
            out = {"ok": False, "error": str(e)}
        print(json.dumps(out, ensure_ascii=False, indent=1))
        return 0 if out["ok"] else 1
    if a.render or a.form or a.finalize:
        try:
            out = use_form(proj, a.form) if a.form else finalize(proj) if a.finalize else render(proj)
        except ValueError as e:
            out = {"ok": False, "error": str(e)}
        print(json.dumps(out, ensure_ascii=False, indent=1))
        return 0 if out.get("ok") else 1
    httpd = serve(proj, a.port, not a.no_browser, a.kind)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    print(json.dumps({"studio_closed": str(proj)}, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
