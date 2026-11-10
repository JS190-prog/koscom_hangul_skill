#!/usr/bin/env python3
"""voice_tool 자체검사.  실행: python scripts/voice/selftest.py   (Java 필요: PATH 또는 JAVA_HOME)
입력은 references/examples/voice 의 합성 보고서 3건(.hwpx)과 references/examples/*.json 이다.
  1. 3건 build -> 문체 소표본, 한 건 빼고 검증 통과, style-guide.md 생성
  2. 2건으로 만든 밴드가 나머지 1건 통과 (모든 조합)
  3. AI투 합쇼체·대화체는 불합격
  4. 금지 상투구·이모지는 즉시 불합격
  5. 금액을 바꾸면 fidelity 가 잡는다
  6. 1건만 넣으면 시험용 라벨
  7. --human 없이는 거부하고 아무것도 쓰지 않는다
  8. 문체 분석: 끝맺음 분류('~ 필요'는 명사형), 가이드 내용이 대표 문서에서 잰 값과 원문 예문인지
  9. report.json 읽기: 표·제목 칸은 문체 검사에서 빠지고 숫자 대조에는 들어간다
 10. 전체 흐름: AI투 report.json 불합격 -> 대표 문서 문체로 다시 쓴 report.json 합격 -> 렌더링 OK
     -> 렌더링한 .hwp 를 다시 읽으면 report.json 과 같은 문장 줄이 나오고 검사도 합격 (요약·상세·기안문)
     -> 렌더링한 .hwp 에 문단 끝 문자가 빠진 문단(Inspect NOEND, 한글이 손상 문서로 거부)이 없다
 11. 공용 양식: templates/source 의 원본 양식 3종에서 다시 만든 원형·목록이 templates/ 와 같고,
     양식별 기본 문체(templates/voice)로 각 양식 예시가 검사를 통과한다
"""
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKILL = HERE.parents[1]
sys.path.insert(0, str(HERE))
import voice_tool as vt  # noqa: E402

DOCS = [SKILL / "references" / "examples" / "voice" / f"보고서_{i}.hwpx" for i in (3, 4, 5)]
AI = "\n".join(["이번 보고서에서는 본사 소방배관 교체의 필요성에 대해 살펴보겠습니다.",
                "먼저 현재 배관은 인허가 후 37년이 경과하여 노후화가 심각한 상황입니다.",
                "또한 누수가 지속적으로 발생하고 있어 안전사고의 우려가 있습니다.",
                "따라서 배관 교체 공사를 추진하는 것이 바람직하다고 판단됩니다.",
                "결론적으로 예산 26.04억원을 확보하여 조속히 공사를 시행해야 합니다.",
                "이를 통해 안정적인 건물 운영이 가능할 것으로 기대됩니다."])
CHAT = "\n".join(["오늘 배관 얘기를 해볼게요 정말로.", "솔직히 37년이면 진짜 오래된 거죠 그렇죠.", "물이 새면 큰일이라서요 정말요.",
                  "그래서 이번에 싹 바꾸자는 거예요 다같이.", "돈은 26억 정도 들어요 대략적으로요.", "저는 빨리 하는 게 좋다고 봐요 진짜로요."])
FACTS = ("요청: 옥상 배수구 막힘 보수 검토보고서\n"
         "작성: 시설관리팀 / 담당 홍길동(1234), 팀장 김철수(2345), 부서장 이영희(3456) / 2026. 11. 09\n"
         "근거: 옥상 배수구 4곳 막힘, 우천 시 물고임, 보수비 480만원(부가세 별도), 공사 기간 3일\n")
HEAD = {"type": "summary", "title": "옥상 배수구 막힘 보수 검토",
        "approval": {"담당": "홍길동(1234)", "팀장": "김철수(2345)", "부서장": "이영희(3456)"},
        "date": "2026. 11. 9", "dept": "시설관리팀"}
BAD = {**HEAD, "sections": [{"heading": "검토 내용", "items": [
    {"text": "이번 보고서에서는 옥상 배수구 막힘 현상에 대해 살펴보겠습니다."},
    {"text": "현재 옥상 배수구 5곳이 막혀 있어 우천 시 물이 고이는 상황입니다."},
    {"text": "방치할 경우 누수가 발생할 우려가 있어 보수가 필요하다고 판단됩니다."},
    {"text": "보수비 480만원을 들여 3일 동안 공사를 시행하는 것이 바람직합니다."},
    {"text": "결론적으로 조속한 보수를 통해 안정적인 건물 운영이 가능할 것입니다."}]}]}
GOOD = {**HEAD, "sections": [
    {"heading": "개요", "items": [
        {"text": "본사 옥상 배수구 4곳이 막혀 우천 시 물고임이 발생하여 보수 조치가 필요함", "children": [
            {"type": "dash", "text": "막힘 방치 시 누수 및 방수층 손상 우려"}]}]},
    {"heading": "현황", "items": [
        {"text": "옥상 배수구 4곳에서 낙엽·토사 퇴적으로 인한 막힘 확인"},
        {"text": "우천 시 옥상 바닥 물고임 지속 발생", "children": [
            {"type": "dash", "text": "부분 청소만으로는 근본적 해결이 불가한 상황임"}]}]},
    {"heading": "보수 계획 및 소요 예산", "items": [
        {"text": "배수구 4곳 청소 및 배수관 막힘 제거 후 거름망 교체"},
        {"text": "공사 기간은 3일 소요 예상"},
        {"text": "보수비 480만원(부가세 별도) 사업 예산 반영 필요"}]},
    {"heading": "건의", "items": [{"text": "옥상 배수구 보수 시행 승인 요청"}]}]}


def run(*a):
    r = subprocess.run([sys.executable, str(HERE / "voice_tool.py"), *map(str, a)], capture_output=True)
    out = r.stdout.decode("utf-8").strip()
    return r.returncode, (json.loads(out) if out.startswith("{") else {"stderr": r.stderr.decode("utf-8", "replace")})


def main():
    assert all(d.exists() for d in DOCS), f"샘플 없음: {DOCS}"
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        # 1) 3건 build
        rc, r = run("build", *DOCS, "--out", td / "v3", "--type", "보고서", "--human")
        assert rc == 0 and r["tier"] == "small" and r["status"] == "verified", r
        v3 = td / "v3"
        assert (v3 / "manifest.jsonl").exists() and len(list((v3 / "corpus").glob("*.md"))) == 3
        slim = json.loads((v3 / "fingerprint-slim.json").read_text(encoding="utf-8"))
        assert slim["meta"]["label"] == "문체 소표본" and slim["meta"]["n_points"] >= 8
        print("1) 3건 build OK:", r["tier"], r["status"], "점", r["n_points"], "한건빼고", [x["pass"] for x in r["leave_one_out"]])

        # 2) 2건 build -> 나머지 1건 검사 (모든 조합)
        for held in range(3):
            rc, r = run("build", *[d for i, d in enumerate(DOCS) if i != held], "--out", td / f"v2_{held}", "--human")
            assert rc == 0 and r["tier"] == "small", r
            rc, res = run("check", "--voice", td / f"v2_{held}", DOCS[held])
            assert rc == 0, (held, res)
        print("2) 2건으로 만든 밴드가 나머지 1건 통과: 3/3")

        # 3) AI투·대화체 불합격
        for name, txt in (("AI투", AI), ("대화체", CHAT)):
            p = td / f"{name}.txt"
            p.write_text(txt, encoding="utf-8")
            rc, res = run("check", "--voice", v3, p)
            assert rc == 3 and not res["pass"], (name, res)
            print(f"3) {name} 불합격 OK: 벗어난 지표 {[x['metric'] for x in res['out_of_band']]} 금지 {[h['msg'] for h in res['hard']]}")

        # 4) 즉시 불합격
        base = vt.clean_lines(vt.read_text(DOCS[0]))
        for name, extra in (("금지 상투구", "다음 내용을 알아보겠습니다 오늘은"), ("이모지", "공사를 진행함 \U0001F525\U0001F525 지금")):
            p = td / f"{name}.txt"
            p.write_text("\n".join(base + [extra]), encoding="utf-8")
            rc, res = run("check", "--voice", v3, p)
            assert rc == 3 and res["hard"], (name, res)
        print("4) 금지 상투구·이모지 즉시 불합격 OK")

        # 5) 금액 변조 탐지
        src = vt.read_text(DOCS[0])
        assert "26.04" in src
        s, bad = td / "src.txt", td / "bad.txt"
        s.write_text(src, encoding="utf-8")
        bad.write_text(src.replace("26.04", "260.4"), encoding="utf-8")
        assert run("fidelity", s, s)[0] == 0
        rc, res = run("fidelity", s, bad)
        assert rc == 1 and "26.04" in res["missing"] and "260.4" in res["invented"], res
        # 날짜 '2026. 11. 10'의 '11.'·'10.'은 번호 머리가 아니라 숫자다 (Codex 시험에서 찾은 결함)
        assert vt.fidelity_of("작성일 2026. 11. 10. 교체비 2,400만원", "| 2026. 11. 10 |\nㅇ 교체비 2,400만원")["pass"]
        assert vt.fidelity_of("교체비 480만원", "1. 개요\n  2. 계획 교체비 480만원")["pass"], "줄 머리 번호는 숫자가 아니다"
        assert not vt.fidelity_of("작성일 2026. 11. 10", "| 2026. 11. 11 |")["pass"]
        assert vt.fidelity_of("작성 2026년 3월", "| 2026.3 |")["pass"], "상세보고 표지 날짜 '2026.3'"
        assert vt.fidelity_of("2026년 상반기 착공", "’26년 상반기 착공")["pass"], "가이드의 두 자리 연도 표기"
        assert not vt.fidelity_of("교체비 2,400만원", "교체비 0.24억원")["pass"], "단위 환산은 막는다"
        assert vt.fidelity_of("예산 26.04억원", "예산 26.04억원")["pass"] and not vt.fidelity_of("예산 26.04억원", "예산 26.40억원")["pass"]
        print("5) 금액 변조 탐지 OK: 사라진", res["missing"], "생긴", res["invented"], "/ 날짜·번호 머리 구분 OK")

        # 6) 1건 -> 시험용
        rc, r = run("build", DOCS[0], "--out", td / "v1", "--human")
        assert rc == 0 and r["tier"] == "trial" and r["label"] == "문체 시험용(1건)", r
        assert run("check", "--voice", td / "v1", DOCS[1])[1]["reference_only"] is True
        print("6) 1건은 시험용 라벨 OK")

        # 7) --human 없이는 거부, 아무것도 쓰지 않음
        rc, _ = run("build", DOCS[0], "--out", td / "x")
        assert rc != 0 and not (td / "x").exists()
        print("7) 사람이 쓴 문서 확인 없이는 거부 OK (폴더도 만들지 않음)")

        # 8) 문체 분석
        assert vt.end_type("소방대책 수립(소화기 추가 배치 등) 필요") == "end_other"
        assert vt.end_type("검토를 완료하였습니다") == "end_polite" and vt.end_type("해외 출장 실시 예정") == "end_other"
        assert vt.end_type("근본적 해결이 불가한 상황임") == "end_m" and vt.end_type("공사를 시행하였다") == "end_da"
        prof = json.loads((v3 / "style-profile.json").read_text(encoding="utf-8"))
        a = prof["analysis"]
        corpus = {l for f in (v3 / "corpus").glob("*.md") for l in f.read_text(encoding="utf-8").splitlines()}
        assert a["dominant"] == "end_other" and a["endings"]["end_polite"] == 0, a["endings"]
        assert a["examples"] and set(a["examples"]) <= corpus, "예문은 대표 문서 원문이어야 한다"
        assert a["period_end_ratio"] < 0.1 and a["label_rate"] < 0.1
        assert ("억원", 3) in [tuple(x) for x in a["money"]] and a["dates"][0][0] == "’26년", (a["money"], a["dates"])
        assert not any(bg in ("개 요", "첨 부") for bg, _ in a["phrases"]), a["phrases"]
        guide = (v3 / "style-guide.md").read_text(encoding="utf-8")
        for need in ["## 1. 이렇게 쓴다", "## 2. 끝맺음", "## 7. 예문", "## 8. 검사 기준", "줄 끝에 마침표를 찍지 않는다",
                     "label 을 비워 둔다", a["examples"][0]]:
            assert need in guide, need
        print("8) 문체 분석 OK: 명사형", a["endings"]["end_other"], "끝말", [w for w, _ in a["top_endings"][:4]],
              "예문", len(a["examples"]), "건")

        # 9) report.json 읽기
        flat = vt.read_text(SKILL / "references" / "examples" / "summary_sample.json")
        lines = vt.clean_lines(flat)
        assert any(l.startswith("(활동 기간) ") for l in lines) and not any(l.startswith("|") for l in lines)
        assert any(x.startswith("| ") for x in flat.splitlines()), "표 칸은 숫자 대조용 원문에 남아야 한다"
        assert vt.clean_lines(vt.read_text(SKILL / "references" / "examples" / "detail_sample.json"))
        long = "표 안에 들어간 본문 문장으로 충분히 긴 줄"
        assert long in vt._pick(["제목"], [long] * 6), "본문이 표 안에만 있으면 표 안을 읽는다"
        assert long not in vt._pick(["본문 문장으로 충분히 긴 첫째 줄"] * 3, [long]), "본문이 3줄 이상이면 표 안은 읽지 않는다"
        print("9) report.json 읽기 OK: 문장", len(lines), "줄 (표·제목 칸은 숫자 대조에만), 표 안 본문 대체 경로 OK")

        # 10) 전체 흐름
        facts = td / "facts.txt"
        facts.write_text(FACTS, encoding="utf-8")
        bj, gj = td / "bad.json", td / "good.json"
        bj.write_text(json.dumps(BAD, ensure_ascii=False), encoding="utf-8")
        gj.write_text(json.dumps(GOOD, ensure_ascii=False), encoding="utf-8")
        rc, g = run("gate", "--voice", v3, "--facts", facts, bj)
        tags = {x.split("]")[0] + "]" for x in g["rework"]}
        assert rc == 3 and not g["fidelity_pass"] and {"[금지]", "[문체]", "[숫자]"} <= tags, g
        rc, g2 = run("gate", "--voice", v3, "--facts", facts, gj)
        assert rc == 0 and g2["pass"] and g2["rework"] == [], g2
        cp = ";".join([str(SKILL / "lib" / "hwplib-1.1.9.jar"), str(SKILL / "scripts" / "koscom-hwp.jar")])

        def render(src, dst):    # 상세보고 예시의 그림 경로는 스킬 폴더 기준이라 cwd 를 스킬 폴더로 둔다
            r = subprocess.run([vt.java_exe(), "-Dfile.encoding=UTF-8", "-cp", cp, "Renderer", str(src), str(dst),
                                str(SKILL / "templates")], capture_output=True, cwd=SKILL)
            last = r.stdout.decode("utf-8", "replace").strip().splitlines()[-1]
            assert r.returncode == 0 and last.startswith("OK"), r.stdout.decode("utf-8", "replace")
            return last

        hwp = td / "good.hwp"
        last = render(gj, hwp)
        rc, res = run("check", "--voice", v3, hwp)
        assert rc == 0, res
        # 렌더링한 .hwp 를 다시 읽으면 report.json 과 같은 문장 줄이 나와야 한다 (요약·상세·박스·참고 모두)
        boxref = td / "boxref.json"
        boxref.write_text(json.dumps({**HEAD, "sections": [{"heading": "본문", "items": [
            {"text": "본문 항목 첫째 줄로 충분히 긴 문장임"},
            {"type": "box", "title": "강조 박스", "items": [{"label": "박스", "text": "강조 박스 안의 항목 문장으로 충분히 김"}]},
            {"type": "dash", "text": "본문 하위 항목 문장으로 충분히 긴 줄임"}]}],
            "attachments": [{"kind": "참고", "title": "참고", "sections": [{"heading": "참고 묶음", "items": [{"text": "참고 점선 박스 안의 문장으로 충분히 긴 줄임"}]}]},
                            {"kind": "첨부", "title": "첨부", "sections": [{"heading": "첨부 묶음", "items": [{"text": "첨부 본문 문장으로 충분히 긴 줄이 됨"}]}]}]},
            ensure_ascii=False), encoding="utf-8")
        ex = SKILL / "references" / "examples"
        dump = lambda p: subprocess.run([vt.java_exe(), "-Dfile.encoding=UTF-8", "-cp", cp, "Inspect", str(p)], capture_output=True).stdout
        for src in (gj, boxref, ex / "summary_sample.json", ex / "detail_sample.json", ex / "draft_sample.json"):
            out = td / (src.stem + "_rt.hwp")
            render(src, out)
            assert vt.clean_lines(vt.read_text(out)) == vt.clean_lines(vt.read_text(src)), f"다시 읽기 불일치: {src.name}"
            assert b"NOEND" not in dump(out), f"문단 끝 문자 빠짐(한글이 손상 문서로 거부): {src.name}"
        # 기안문 머리(수신자 · 제목)와 첨부 목록: 주면 양식의 「시스템 자동생성 영역」 대신 채운다
        dj = td / "draft_head.json"
        dj.write_text(json.dumps({**json.loads((ex / "draft_sample.json").read_text(encoding="utf-8")),
                                  "recipient": "경영지원부장", "title": "본사 회의실 화상회의 장비 교체 추진 및 계약 체결 의뢰",
                                  "attachments": ["장비 교체 추진 방안 1부", "견적서 1부"]}, ensure_ascii=False), encoding="utf-8")
        render(dj, td / "draft_head.hwp")
        d = dump(td / "draft_head.hwp").decode("utf-8", "replace")
        for want in ('"경영지원부장"', '"본사 회의실 화상회의 장비 교체 추진 및 계약 체결 의뢰"', '"첨부 1. 장비 교체 추진 방안 1부"', '"     2. 견적서 1부. 끝."'):
            assert want in d, f"기안문 머리 · 첨부 목록 빠짐: {want}"
        assert "시스템 자동생성 영역" not in d and "NOEND" not in d, "기안문 머리 · 첨부 안내 문구가 남음"
        # 첨부가 없으면 꼬리의 안내 줄 대신 본문 마지막 줄 끝에 「끝.」. 결재 칸 표 앞 양식 빈 줄은 한 줄만
        # (양식의 빈 줄 27개가 결재 칸을 밀어 본문이 길면 결재 칸만 다음 쪽으로 넘어갔다 — 표는 쪽 아래에 붙인다)
        d0 = dump(td / "draft_sample_rt.hwp").decode("utf-8", "replace")
        assert "첨부∨" not in d0 and re.search(r'  끝\."\s*$', d0, re.M), "첨부 없는 기안문의 끝 표시"
        # 예산 표 합계 행: 기안문 양식처럼 앞 두 칸 병합 · 회색 바탕(bf22) · 「합계」는 머리행 글자모양(굵게)
        tot = re.search(r'cell\(\d+,0\) bf(\d+) span1x2\s+p0 \S+ \S+ cs\[0:(\d+)\][^\n]*"합계"', d0)
        head = re.search(r'cell\(0,0\) bf\d+ span1x1\s+p0 \S+ \S+ cs\[0:(\d+)\][^\n]*"배정부서"', d0)
        assert tot and head and tot.group(1) == "22" and tot.group(2) == head.group(1), "기안문 표 합계 행 모양(양식과 다름)"

        def blanks_before_tail(dd):
            tops, tail = [], None
            for ln in dd.splitlines():
                if re.match(r"#\d+ ", ln):
                    tops.append(ln.rstrip().endswith('""'))
                elif "TABLE 15x30" in ln and tail is None:
                    tail = len(tops) - 1
            n = 0
            while tail - 1 - n >= 0 and tops[tail - 1 - n]:
                n += 1
            return n
        assert blanks_before_tail(d) <= 2 and blanks_before_tail(d0) <= 2, "결재 칸 앞 빈 줄이 남음"   # 본문 끝 빈 줄 + 양식 빈 줄 1(전에는 27)
        print("10) 전체 흐름 OK: AI투 report.json 불합격", sorted(tags), "-> 다시 쓴 report.json 합격 -> 렌더링", last.split()[0],
              "-> .hwp 검사 합격, 다시 읽기 일치·문단 끝 문자 5종(요약·박스/참고·요약 예시·상세 예시·기안문 예시)")

        # 11) 공용 양식: 원본 양식 -> 원형 재생성이 templates/ 와 같은지, 기본 문체로 예시가 통과하는지
        src = SKILL / "templates" / "source"
        regen = td / "regen"
        regen.mkdir()
        r = subprocess.run([vt.java_exe(), "-Dfile.encoding=UTF-8", "-cp", cp, "TemplateBuilder", str(src / "보고서_템플릿.hwp"),
                            str(src / "투자계획서_템플릿.hwp"), str(src / "기안문_템플릿.hwp"), str(regen)], capture_output=True)
        assert r.returncode == 0, r.stderr.decode("utf-8", "replace")
        for k in ("summary", "detail", "draft"):
            cat = lambda d: (d / f"{k}_catalog.properties").read_text(encoding="utf-8").replace("\r", "")
            assert cat(regen) == cat(SKILL / "templates"), f"{k} 목록이 원본 양식과 다름 -> build.ps1 머리 주석대로 재생성"
            assert dump(regen / f"{k}_base.hwp") == dump(SKILL / "templates" / f"{k}_base.hwp"), f"{k} 원형이 원본 양식과 다름"
        for form, ex, label in (("보고서", "summary_sample.json", "문체 시험용(1건)"), ("투자계획서", "detail_sample.json", "문체 시험용(1건)"),
                                ("기안문", "draft_sample.json", "문체 소표본")):
            rc, g = run("gate", "--voice", SKILL / "templates" / "voice" / form, SKILL / "references" / "examples" / ex)
            assert rc == 0 and g["label"] == label, (form, g)
        # 기안문 고정 줄('- 아 래 -', 첨부 목록, 양식 꼬리)은 문장으로 세지 않는다
        assert vt.clean_lines("-  아    래  -\n첨부 1. 견적서 1부\n     2. 추진 방안 1부. 끝.\n첨부∨//.∨끝. (시스템 자동생성 영역)") == []
        print("11) 공용 양식 OK: 원본 양식에서 원형·목록 재생성 일치(보고서·투자계획서·기안문), 기본 문체로 예시 통과")
    print("SELFTEST PASS")


if __name__ == "__main__":
    main()
