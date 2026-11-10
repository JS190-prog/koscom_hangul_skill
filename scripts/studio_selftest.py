#!/usr/bin/env python3
"""스튜디오 자체 검사: 서버를 띄워 보안(토큰·Host·경로), 양식 올리기→채우기→최종본(_ver1·_ver2, 빈 칸 거부),
보고서(Java 렌더러 + 문체 검사), 기안문(기안문 스킬이 있으면), 이전 판 보관을 확인한다.
한글은 쓰지 않는다(쪽 그림 생략). Java 가 없으면 보고서, 기안문 스킬이 없으면 기안문 항목은 건너뛴다.

  python studio_selftest.py
"""
import json
import os
import shutil
import sys
import tempfile
import threading
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

TMP = Path(tempfile.mkdtemp(prefix="koscom-studio-selftest-"))
os.environ["KOSCOM_HWP_FORMS"] = str(TMP / "forms")
os.environ["KOSCOM_STUDIO_NO_PREVIEW"] = "1"
os.environ["KOSCOM_STUDIO_DRY_OPEN"] = "1"  # 「한글로 열기」가 실제로 프로그램을 띄우지 않게
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import studio  # noqa: E402

PASS = TOTAL = 0


def ok(name, cond, detail=""):
    global PASS, TOTAL
    TOTAL += 1
    PASS += bool(cond)
    print(f"{'PASS' if cond else 'FAIL'} {TOTAL}. {name}" + ("" if cond else f" — {str(detail)[:600]}"), flush=True)


def call(base, path, body=None, token=None, raw=None, host=None, headers=None):
    h = dict(headers or {})
    if token:
        h["X-Studio-Token"] = token
    if host:
        h["Host"] = host
    data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
    if data is not None and "Content-Type" not in h:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=data, headers=h, method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def j(resp):
    return json.loads(resp[1].decode("utf-8"))


def tiny_form():
    sec = ('<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><hs:sec xmlns:hs="urn:s" xmlns:hp="urn:p">'
           '<hp:p><hp:run><hp:t>성명: {{성명}} / 부서: {{부서}}</hp:t></hp:run></hp:p>'
           '<hp:p><hp:run><hp:t>사유: {{사유}}</hp:t></hp:run></hp:p></hs:sec>')
    f = TMP / "휴가신청서.hwpx"
    with zipfile.ZipFile(f, "w") as z:
        z.writestr("mimetype", "application/hwp+zip")
        z.writestr("Contents/section0.xml", sec)
    return f


def main():
    proj = TMP / "proj"
    httpd = studio.serve(proj, 0, False, "form")
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"
    token = call(base, "/api/token")[1].decode()
    try:
        # 1) 보안
        st = j(call(base, "/api/state"))
        ok("상태 읽기·처음 펼칠 버튼", st["hint"] == "form" and st["doc"] is None, st)
        if shutil.which("node"):  # 화면 스크립트 문법(편집 중 따옴표·줄바꿈이 깨지면 화면 전체가 멈춘다)
            import re
            import subprocess
            js = TMP / "page.js"
            js.write_text(re.search(r"<script>(.*)</script>", call(base, "/")[1].decode("utf-8"), re.S).group(1), encoding="utf-8")
            nc = subprocess.run(["node", "--check", str(js)], capture_output=True)
            ok("화면 스크립트 문법", nc.returncode == 0, nc.stderr.decode("utf-8", "replace")[-300:])
        try:
            studio.StudioServer(("127.0.0.1", httpd.server_address[1]), studio.Handler).server_close()
            ok("같은 포트에 두 번째 서버가 붙지 못함(포트 독점)", False, "두 번째 서버가 붙었음")
        except OSError:
            ok("같은 포트에 두 번째 서버가 붙지 못함(포트 독점)", True)
        import subprocess
        import time
        p2 = TMP / "proj2"
        old = subprocess.Popen([sys.executable, str(HERE / "studio.py"), str(p2), "--no-browser"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for _ in range(50):
            if (p2 / "studio/server.json").exists():
                break
            time.sleep(0.2)
        time.sleep(0.5)
        stopped = studio.stop_previous(p2)
        try:
            old.wait(timeout=10)
        except subprocess.TimeoutExpired:
            old.kill()
        ok("같은 프로젝트를 다시 띄우면 이전 스튜디오를 닫음", stopped == old.pid and old.returncode is not None, [stopped, old.pid, old.returncode])
        with studio.LOCK:  # 만드는 중
            busy = call(base, "/api/shutdown", {}, token)
        ok("만드는 중에는 「스튜디오 종료」 거부", busy[0] == 400 and j(busy).get("ok") is False, busy)
        p3 = TMP / "proj3"
        s3 = subprocess.Popen([sys.executable, str(HERE / "studio.py"), str(p3), "--no-browser"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for _ in range(50):
            if (p3 / "studio/server.json").exists():
                break
            time.sleep(0.2)
        time.sleep(0.5)
        b3 = json.loads((p3 / "studio/server.json").read_text(encoding="utf-8"))["url"].rstrip("/")
        no_tok = call(b3, "/api/shutdown", {})[0]
        bye = j(call(b3, "/api/shutdown", {}, call(b3, "/api/token")[1].decode()))
        try:
            s3.wait(timeout=10)
        except subprocess.TimeoutExpired:
            s3.kill()
        ok("「스튜디오 종료」: 토큰 없으면 거부, 누르면 서버 프로세스가 끝남", no_tok == 403 and bye.get("ok") and s3.returncode == 0
           and b"studio_closed" in s3.stdout.read(), [no_tok, bye, s3.returncode])
        ok("토큰 없는 POST 거부", call(base, "/api/choose", {"kind": "report"})[0] == 403)
        ok("다른 Host 거부", call(base, "/api/state", host="evil.example")[0] == 403)
        ok("폴더 밖 경로 거부", call(base, "/preview/../choice.json")[0] == 404 and call(base, "/exports/..%2F..%2Fx")[0] == 404
           and call(base, "/forms/..%2F..%2F/x")[0] == 404 and call(base, "/forms/x/..%2F..%2Fform.json")[0] == 404)
        man = json.loads(call(base, "/ui/samples/samples.json")[1])
        miss = [p for k in ("report", "gian", "invest", "form") for s in man.get(k, [{"pages": ["(없음)"]}]) for p in s["pages"]
                if call(base, "/ui/samples/" + p)[0] != 200]
        ok("버튼 넷 모두 샘플 양식 그림이 있음", not miss and all(man.get(k) for k in ("report", "gian", "invest", "form")), miss or man.keys())

        # 2) 양식: 올리기 → 고르기 → 빈 칸 거부 → 채우기 → 최종본 ver1·ver2
        f = tiny_form()
        from urllib.parse import quote
        up = j(call(base, "/api/form/upload", raw=f.read_bytes(), token=token, headers={
            "X-File-Name": quote(f.name), "X-Form-Name": quote("휴가 신청서"), "X-Form-Desc": quote("시험"), "Content-Type": "application/octet-stream"}))
        ok("양식 올리기(항목 3개)", up.get("ok") and up["form"]["fields"] == ["성명", "부서", "사유"], up)
        bad = j(call(base, "/api/form/upload", raw=b"not a zip", token=token, headers={"X-File-Name": "x.hwpx", "X-Form-Name": quote("깨진 양식")}))
        ok("깨진 파일 등록 거부", bad.get("ok") is False and "hwpx" in bad.get("error", ""), bad)
        c = j(call(base, "/api/choose", {"kind": "form", "form": "휴가 신청서", "title": "휴가 신청서(홍길동)"}, token))
        r = c["state"]["render"]
        ok("양식 고르면 바로 작업본", c.get("ok") and r["ok"] and r["file"] == "작업본.hwpx" and r["left"] == ["성명", "부서", "사유"], c)
        fin = j(call(base, "/api/finalize", {}, token))
        ok("빈 칸이 있으면 최종본 거부", fin.get("ok") is False and any("채우지 않은 항목" in p for p in fin.get("problems", [])), fin)
        sv = j(call(base, "/api/save", {"data": {"title": "휴가 신청서(홍길동)", "values": {"성명": "홍길동", "부서": "경영지원부", "사유": "가족 행사\n(11. 20)"}}}, token))
        ok("고친 값 저장 → 다시 만들기·이전 판 보관", sv.get("ok") and sv["render"]["left"] == [] and Path(sv["backup"]).is_file(), sv)
        vals = {"title": "휴가 신청서(홍길동)", "values": {"성명": "홍길동", "부서": "경영지원부", "사유": "가족 행사\n(11. 20)"}}
        f1 = j(call(base, "/api/finalize", {}, token))
        st1 = f1.get("state") or {}
        ok("최종본 저장 = 문서 하나 끝: 작업 칸 비움(history 로 옮김, 최종본은 남음)",
           f1.get("ok") and st1.get("doc") is None and st1.get("choice") is None and st1.get("render") is None
           and Path(f1.get("cleared") or ".").joinpath("form.json").is_file() and st1.get("exports") == ["휴가 신청서(홍길동)_ver1.hwpx"], f1)
        again = j(call(base, "/api/choose", {"kind": "form", "form": "휴가 신청서"}, token))  # 같은 양식을 다시 요청 → ver2
        ra, da = again["state"]["render"], again["state"]["doc"]["data"]["values"]
        ok("다시 채울 때 사람·부서 칸은 지난번 최종본 값으로 먼저 채움(원천에도 남김)", ra["auto"] == {"성명": "지난번 값", "부서": "지난번 값"}
           and da.get("성명") == "홍길동" and da.get("부서") == "경영지원부" and ra["left"] == ["사유"], [ra, da])
        j(call(base, "/api/save", {"data": vals}, token))
        f2 = j(call(base, "/api/finalize", {}, token))
        ex = proj / "exports"
        ok("다시 요청한 문서는 ver2(+검사 기록)", f2.get("version") == 2 and (ex / "휴가 신청서(홍길동)_ver1.hwpx").is_file()
           and (ex / "휴가 신청서(홍길동)_ver2.json").is_file(), f2)
        op = j(call(base, "/api/open", {"file": "휴가 신청서(홍길동)_ver1.hwpx", "how": "open"}, token))
        bad1 = j(call(base, "/api/open", {"file": "../studio/choice.json"}, token))
        bad2 = j(call(base, "/api/open", {"file": "휴가 신청서(홍길동)_ver1.json"}, token))
        ok("최종본 한글로 열기(exports 안 한글 파일만)", op.get("ok") and op["file"].endswith("_ver1.hwpx") and bad1.get("ok") is False and bad2.get("ok") is False,
           [op, bad1, bad2])
        with zipfile.ZipFile(ex / "휴가 신청서(홍길동)_ver1.hwpx") as z:
            x = z.read("Contents/section0.xml").decode("utf-8")
        ok("최종본에 값이 들어감(줄바꿈 포함)", "홍길동" in x and "가족 행사<hp:lineBreak/>(11. 20)" in x and "{{" not in x, x[:300])
        dl = call(base, "/exports/" + quote("휴가 신청서(홍길동)_ver1.hwpx"))
        ok("최종본 내려받기", dl[0] == 200 and dl[1][:2] == b"PK")

        # 3) 자연어 양식 요청은 에이전트에게 넘긴다(choice 만 남음)
        nl = j(call(base, "/api/choose", {"kind": "form-new", "name": "출장 신청서", "description": "출장자·기간·경비 표"}, token))
        ch = json.loads((proj / "studio/choice.json").read_text(encoding="utf-8"))
        ok("말로 새 양식 → choice.json(form-new)", nl.get("ok") and ch["kind"] == "form-new" and ch["description"] == "출장자·기간·경비 표"
           and ch["by"] == "user", ch)
        draft = TMP / "draft.json"
        draft.write_text(json.dumps({"kind": "report", "title": "AI 초안 제목", "memo": "□ 배경\n□ 내용", "basis": "근거: 첨부 2건",
                                     "options": {"dept": "경영지원부"}}, ensure_ascii=False), encoding="utf-8")
        import subprocess
        cr = subprocess.run([sys.executable, str(HERE / "studio.py"), str(proj), "--choose", str(draft)], capture_output=True)
        ag = json.loads(call(base, "/api/state")[1])["choice"]
        ok("에이전트 초안 기록(--choose → by=agent·근거, 화면 상태에 보임)", cr.returncode == 0 and ag["by"] == "agent"
           and ag["basis"] == "근거: 첨부 2건" and ag["options"]["dept"] == "경영지원부" and ag["memo"].startswith("□ 배경"), [cr.stdout[-300:], ag])
        api = j(call(base, "/api/choose", {"kind": "report", "basis": "가짜 근거", "by": "agent"}, token))["choice"]
        ok("브라우저 선택은 항상 by=user(근거 칸 무시)", api["by"] == "user" and api["basis"] == "", api)

        # 4) 보고서(Java 렌더러 + 문체 검사)
        java = os.environ.get("JAVA_HOME") or (Path.home() / ".agents/vendor/jdk-17.0.21+9-jre").exists() or shutil.which("java")
        if java:
            c = j(call(base, "/api/choose", {"kind": "report", "title": "시험 보고"}, token))
            hist = list((proj / "studio/history").glob("doc-*"))
            ok("다른 종류를 고르면 이전 doc/ 은 history 로", c.get("ok") and hist and not (proj / "doc").exists(), hist)
            (proj / "doc").mkdir()
            shutil.copy(studio.SKILL / "references/examples/summary_sample.json", proj / "doc/report.json")
            rr = j(call(base, "/api/render", {}, token))["render"]
            ok("보고서 원천 → 작업본.hwp", rr["ok"] and rr["file"] == "작업본.hwp" and rr["kind"] == "report", rr)
            fr = j(call(base, "/api/finalize", {}, token))
            ok("보고서 최종본(문체 검사 실행)", fr.get("ok") and fr["gate"] and fr["files"][0].endswith("_ver1.hwp"), fr)
            # 자주 쓰는 양식: 누르면 바로 등록 → 채우기(오늘 날짜) → 합계 자동 계산
            pre = j(call(base, "/api/form/preset", {"name": "국내 출장 신청서"}, token))
            ok("자주 쓰는 양식 바로 등록(목록에서 등록됨 표시)", pre.get("ok") and "합계" in pre["form"]["fields"]
               and any(p["name"] == "국내 출장 신청서" and p["registered"] for p in pre["presets"]), pre)
            ch = j(call(base, "/api/choose", {"kind": "form", "form": pre["form"]["slug"]}, token))["state"]
            ok("자주 쓰는 양식 채우기: 신청일은 오늘, 합계 칸은 더할 칸을 화면에 알림", ch["render"]["ok"] and ch["render"]["auto"].get("신청일") == "오늘 날짜"
               and ch["form"]["sums"] == {"합계": ["교통비", "숙박비"]}, ch["render"])
            sv = j(call(base, "/api/save", {"data": {"title": "출장", "values": {**ch["doc"]["data"]["values"], "교통비": "3,100", "숙박비": "-"}}}, token))
            ok("합계 자동 계산(「-」는 0)", sv.get("ok") and sv["state"]["doc"]["data"]["values"]["합계"] == "3,100" and sv["render"]["auto"].get("합계") == "자동 계산", sv.get("render"))
        else:
            print("SKIP 보고서: Java 없음")

        # 5) 기안문(결재란까지): 기안문 스킬이 있을 때
        gs = studio.gian_skill()
        if gs:
            j(call(base, "/api/choose", {"kind": "gian", "options": {"mode": "full"}}, token))
            (proj / "doc").mkdir()
            shutil.copy(gs / "examples/edr/source.json", proj / "doc/source.json")
            gr = j(call(base, "/api/render", {}, token))["render"]
            ok("기안문 원천 → 작업본.hwpx(기안문 스킬 build)", gr["ok"] and gr["file"] == "작업본.hwpx" and gr["check_ok"], gr)
            bad = j(call(base, "/api/save", {"data": {"schema": "x"}}, token))
            ok("기안문 원천 형식 오류 거부", bad.get("ok") is False, bad)
        else:
            print("SKIP 기안문: koscom-gian-from-attachments 없음")
    finally:
        httpd.shutdown()
    print(f"STUDIO SELFTEST {'PASS' if PASS == TOTAL else 'FAIL'} {PASS}/{TOTAL}  (작업 폴더 {TMP})")
    return 0 if PASS == TOTAL else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
