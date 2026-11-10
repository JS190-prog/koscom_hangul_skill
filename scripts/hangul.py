#!/usr/bin/env python3
"""한컴오피스 한글(COM)로 문서를 열어 다른 형식으로 저장하거나 쪽 그림(PNG)을 만든다.
매번 새 한글 프로세스를 띄우고 끝나면 닫는다(사용자가 열어 둔 한글 창은 건드리지 않는다).

  python hangul.py convert <원본.hwp|.hwpx> <대상.hwpx|.hwp|.pdf>
  python hangul.py pages <문서> <PNG 폴더> [--dpi 80]     → 폴더의 page-NN.png 를 새로 만든다
마지막 줄은 JSON 한 줄이다. 필요: Windows, 한글, pywin32, PyMuPDF(pages 만)
"""
import argparse
import json
import pathlib
import sys
import tempfile

FMT = {".hwp": "HWP", ".hwpx": "HWPX", ".pdf": "PDF"}


def available():
    try:
        import win32com.client  # noqa: F401
        import winreg
        winreg.OpenKey(winreg.HKEY_CLASSES_ROOT, "HWPFrame.HwpObject").Close()
        return True
    except Exception:
        return False


def _open(path):
    import pythoncom
    import win32com.client as win32
    pythoncom.CoInitialize()
    hwp = win32.gencache.EnsureDispatch("HWPFrame.HwpObject")
    hwp.RegisterModule("FilePathCheckDLL", "FilePathCheckerModuleExample")  # 파일 접근 확인 창을 띄우지 않는다
    try:
        hwp.XHwpWindows.Item(0).Visible = False
    except Exception:
        pass
    if not hwp.Open(str(path), FMT[path.suffix.lower()], "forceopen:true"):
        hwp.Quit()
        raise RuntimeError(f"한글이 열지 못했습니다: {path.name}")
    return hwp


def convert(src, dst):
    src, dst = pathlib.Path(src).resolve(), pathlib.Path(dst).resolve()
    dst.parent.mkdir(parents=True, exist_ok=True)
    hwp = _open(src)
    try:
        if not hwp.SaveAs(str(dst), FMT[dst.suffix.lower()], ""):
            raise RuntimeError(f"한글이 저장하지 못했습니다: {dst.name}")
        hwp.Clear(1)
    finally:
        hwp.Quit()
    return dst


def pages(src, outdir, dpi=80):
    try:
        import pymupdf
    except ImportError:  # 예전 PyMuPDF
        import fitz as pymupdf
    outdir = pathlib.Path(outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    for old in outdir.glob("page-*.png"):
        old.unlink()
    with tempfile.TemporaryDirectory() as td:
        pdf = convert(src, pathlib.Path(td) / "doc.pdf")
        doc = pymupdf.open(str(pdf))
        names = []
        for i, page in enumerate(doc, 1):
            names.append(f"page-{i:02d}.png")
            page.get_pixmap(dpi=dpi).save(str(outdir / names[-1]))
        doc.close()
    return names


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["convert", "pages"])
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--dpi", type=int, default=80)
    a = ap.parse_args()
    try:
        if a.cmd == "convert":
            out = {"ok": True, "file": str(convert(a.src, a.dst))}
        else:
            out = {"ok": True, "pages": pages(a.src, a.dst, a.dpi)}
    except Exception as e:  # noqa: BLE001 — 호출한 쪽(studio)이 메시지를 그대로 보여 준다
        out = {"ok": False, "error": f"{type(e).__name__}: {e}"}
    print(json.dumps(out, ensure_ascii=False))
    return 0 if out["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
