import kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlNewNumber;
import kr.dogfoot.hwplib.object.bodytext.control.ControlPageHide;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataCompress;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataState;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataType;
import kr.dogfoot.hwplib.object.docinfo.parashape.Alignment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 상세보고: 표지(정식/간략) → 목차 → 장(Ⅰ. …) → 절(1 …) → 대제목·본문(요약 렌더러 재사용) → 첨부/참고.
 * 쪽번호: 표지·목차는 감추고, 첫 장에서 1쪽으로 새로 시작한다. 목차 쪽번호는 문단 높이 합산으로 추정한다.
 */
public class DetailRenderer extends Renderer {
    static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"};

    /** 목차 항목: level 1=장, 2=절, 3=별첨. 시작 문단으로 쪽을 찾는다. */
    static class Toc {
        final int level; final String num, text; final Paragraph at;
        Toc(int level, String num, String text, Paragraph at) { this.level = level; this.num = num; this.text = text; this.at = at; }
    }

    final List<Toc> toc = new ArrayList<>();
    int centerPs = -1;

    DetailRenderer(String templates) throws Exception {
        super(templates, "detail");
    }

    void detail(Map<String, Object> r) throws Exception {
        Map<String, Object> ap = map(r, "approval");
        boolean compact = str(r, "cover", "full").equals("compact");
        String title = str(r, "title", "");
        Map<String, String> cv = vals("TITLE", title, "DATE", str(r, "date", ""), "HQ", str(r, "hq", ""),
                "DEPT", str(r, "dept", ""), "담당", str(ap, "담당", ""), "팀장", str(ap, "팀장", ""), "부장", str(ap, "부장", ""));

        // 표지
        List<Paragraph> cover = c.protoRange(compact ? "COVER_COMPACT" : "COVER_FULL");
        Paragraph compactToc = null;
        for (Paragraph p : cover) {
            ControlTable t = firstTable(p);
            if (t != null) {
                if (t.getTable().getRowCount() == 3 && t.getTable().getColumnCount() == 13) compactToc = p;
                else Composer.fillTable(t, cv);
            } else if (plainText(p).contains("{{")) fill(p, cv);
        }
        fitCoverTitle(cover, title);
        insertControlFront(cover.get(0), pageHide(), 21, "pghd");
        c.out.addAll(cover);

        // 목차(정식 표지일 때 별도 쪽)
        Paragraph tocPara = null;
        if (!compact && !Boolean.FALSE.equals(r.get("toc"))) {
            tocPara = c.proto("TOC_FULL");
            insertControlFront(tocPara, pageHide(), 21, "pghd");
            c.out.add(tocPara);
        }

        // 본문
        int bodyStart = c.out.size();
        String style = str(r, "heading_style", "number");
        List<Object> chapters = list(r, "chapters");
        for (int i = 0; i < chapters.size(); i++) {
            @SuppressWarnings("unchecked") Map<String, Object> ch = (Map<String, Object>) chapters.get(i);
            Paragraph cp = c.proto("CHAPTER");
            Composer.fillTable(firstTable(cp), vals("ROMAN", ROMAN[i], "TEXT", str(ch, "title", "")));
            if (i == 0) insertControlFront(cp, newNumber(), 21, "nwno");
            keepNext.add(cp);
            c.out.add(cp);
            toc.add(new Toc(1, ROMAN[i], str(ch, "title", ""), cp));
            if (!list(ch, "sections").isEmpty()) sections(c.out, list(ch, "sections"), style, "GAP8");
            List<Object> parts = list(ch, "parts");
            for (int j = 0; j < parts.size(); j++) {
                @SuppressWarnings("unchecked") Map<String, Object> pt = (Map<String, Object>) parts.get(j);
                c.out.add(c.proto(j == 0 && list(ch, "sections").isEmpty() ? "GAP4" : "GAP12"));
                Paragraph pp = c.proto("PART");
                Composer.fillTable(firstTable(pp), vals("NUM", String.valueOf(j + 1), "TEXT", str(pt, "title", "")));
                if (Boolean.TRUE.equals(pt.get("page_break"))) pp.getHeader().getDivideSort().setDividePage(true);
                keepNext.add(pp);
                c.out.add(pp);
                toc.add(new Toc(2, String.valueOf(j + 1), str(pt, "title", ""), pp));
                sections(c.out, list(pt, "sections"), style, "GAP8");
            }
        }
        int before = c.out.size();
        attachments(c.out, list(r, "attachments"), style);
        for (int k = before; k < c.out.size(); k++) {
            Paragraph p = c.out.get(k);
            String label = attachmentTitle(p);
            if (label != null) toc.add(new Toc(3, "", label, p));
        }

        // 목차 채우기(쪽번호 추정)
        Map<Paragraph, Integer> pages = paginate(c.out.subList(bodyStart, c.out.size()));
        if (tocPara != null) fillFullToc(tocPara, pages);
        if (compactToc != null) fillCompactToc(compactToc, pages);
        spillWarnings();
        warnings.add("목차 쪽번호는 문단 높이로 추정한 값 — 한글에서 열어 확인 필요(본문 " + maxPage(pages) + "쪽 추정)");
    }

    /** 첨부/참고 라벨 박스 문단이면 제목, 아니면 null. */
    String attachmentTitle(Paragraph p) {
        ControlTable t = firstTable(p);
        if (t == null || t.getTable().getRowCount() != 1 || t.getTable().getColumnCount() != 3) return null;
        String label = plainText(cell(t, 0, 0).getParagraphList().getParagraph(0)).trim();
        if (!label.equals("첨부") && !label.equals("참고")) return null;
        return plainText(cell(t, 0, 2).getParagraphList().getParagraph(0)).trim();
    }

    // ================================================================= 표지

    /** 표지 제목이 여러 줄이면 그만큼 제목 아래 빈 문단을 덜어 표지가 한 쪽에 들어가게 한다. */
    void fitCoverTitle(List<Paragraph> cover, String title) {
        for (int i = 0; i < cover.size(); i++) {
            ControlTable t = firstTable(cover.get(i));
            if (t == null || t.getTable().getRowCount() != 3 || t.getTable().getColumnCount() != 1) continue;
            Cell cell = cell(t, 1, 0);
            List<Run> rs = getRuns(cell.getParagraphList().getParagraph(0));
            if (rs.isEmpty()) return;
            double avail = (cell.getListHeader().getWidth() - 1020) / 100.0;
            int lines = Layout.lines(segs(rs), avail, avail, true).size();
            if (lines > 2) warnings.add("[표지 제목] " + lines + "줄 — 2줄 이내로 줄이는 것을 권장: " + abbrev(title));
            int remove = 2 * (lines - 1);
            for (int k = i + 1; k < cover.size() && remove > 0; ) {
                Paragraph p = cover.get(k);
                if (p.getText() == null && (p.getControlList() == null || p.getControlList().isEmpty())) { cover.remove(k); remove--; }
                else k++;
            }
            return;
        }
    }

    ControlPageHide pageHide() {
        ControlPageHide ph = new ControlPageHide();
        ph.getHeader().getProperty().setValue(1 << 5); // 쪽 번호 감추기
        return ph;
    }

    /** 간략 표지 제목에 있는 "새 번호(쪽=1)" 컨트롤을 복제한다. */
    ControlNewNumber newNumber() {
        for (Paragraph p : c.protoRange("COVER_COMPACT")) {
            ControlTable t = firstTable(p);
            if (t == null) continue;
            for (Row r : t.getRowList())
                for (Cell cell : r.getCellList())
                    for (Paragraph cp : cell.getParagraphList())
                        if (cp.getControlList() != null)
                            for (Control ct : cp.getControlList())
                                if (ct instanceof ControlNewNumber) {
                                    ControlNewNumber n = (ControlNewNumber) ct.clone();
                                    n.getHeader().setNumber(1);
                                    return n;
                                }
        }
        throw new IllegalStateException("base 템플릿에 새 번호 컨트롤이 없음");
    }

    // ================================================================= 목차

    void fillFullToc(Paragraph tocPara, Map<Paragraph, Integer> pages) throws Exception {
        Cell tc = cell(firstTable(tocPara), 2, 0);
        List<Paragraph> proto = new ArrayList<>();
        for (Paragraph p : tc.getParagraphList()) proto.add(p);
        List<Paragraph> ps = new ArrayList<>();
        ps.add(proto.get(c.num("toc.TOP_GAP")).clone());
        boolean first = true;
        for (Toc e : toc) {
            String page = String.valueOf(pages.getOrDefault(e.at, 1));
            if (e.level == 1 || e.level == 3) {
                if (!first) ps.add(proto.get(c.num("toc.CHAPTER_GAP")).clone());
                first = false;
            }
            if (e.level == 1) ps.add(fill(proto.get(c.num("toc.CHAPTER")).clone(), vals("ROMAN", e.num, "TEXT", e.text, "PAGE", page)));
            else if (e.level == 2) ps.add(fill(proto.get(c.num("toc.SECTION")).clone(), vals("NUM", e.num, "TEXT", e.text, "PAGE", page)));
            else ps.add(fill(proto.get(c.num("toc.APPENDIX")).clone(), vals("TEXT", e.text, "PAGE", page)));
        }
        ps.add(proto.get(c.num("toc.BOTTOM_GAP")).clone());
        Composer.setCellParagraphs(tc, ps);
    }

    void fillCompactToc(Paragraph tocPara, Map<Paragraph, Integer> pages) throws Exception {
        Cell tc = cell(firstTable(tocPara), 2, 0);
        List<Paragraph> proto = new ArrayList<>();
        for (Paragraph p : tc.getParagraphList()) proto.add(p);
        List<Paragraph> ps = new ArrayList<>();
        for (Toc e : toc) {
            if (e.level != 1) continue;
            if (!ps.isEmpty()) ps.add(proto.get(c.num("compact_toc.CHAPTER_GAP")).clone());
            ps.add(fill(proto.get(c.num("compact_toc.CHAPTER")).clone(),
                    vals("ROMAN", e.num, "TEXT", e.text, "PAGE", String.valueOf(pages.getOrDefault(e.at, 1)))));
        }
        Composer.setCellParagraphs(tc, ps);
    }

    // ================================================================= 그림

    /**
     * {"type":"figure", "path":"그림.png", "width":150(mm, 선택), "caption":"<그림 1> …"(선택)}
     * 표지 로고 그림 컨트롤을 복제해 이미지·크기만 바꾸고, 글자처럼 취급해 가운데 정렬 문단에 넣는다.
     */
    @Override
    List<Paragraph> figure(Map<String, Object> it) throws Exception {
        File f = new File(str(it, "path", ""));
        if (!f.isFile()) throw new IllegalArgumentException("그림 파일 없음: " + f);
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IllegalArgumentException("그림 형식을 읽을 수 없음(png/jpg/bmp/gif): " + f);
        String ext = f.getName().substring(f.getName().lastIndexOf('.') + 1).toLowerCase();
        if (ext.equals("jpeg")) ext = "jpg";
        double maxMm = bodyWidthPt / 72 * 25.4 - 10;
        double mm = Math.min(maxMm, num(it, "width", Math.min(maxMm, img.getWidth() * 25.4 / 96)));
        long w = Math.round(mm / 25.4 * 7200), h = Math.round(w * (double) img.getHeight() / img.getWidth());

        int bin = registerBin(Files.readAllBytes(f.toPath()), ext);
        ControlPicture pic = (ControlPicture) logo().clone();
        pic.getHeader().getProperty().setLikeWord(true);
        pic.getHeader().setWidth(w);
        pic.getHeader().setHeight(h);
        pic.getHeader().setxOffset(0);
        pic.getHeader().setyOffset(0);
        kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.ShapeComponent sc = pic.getShapeComponent();
        sc.setOffsetX(0);
        sc.setOffsetY(0);
        sc.setWidthAtCreate((int) w);
        sc.setHeightAtCreate((int) h);
        sc.setWidthAtCurrent((int) w);
        sc.setHeightAtCurrent((int) h);
        sc.setRotateXCenter((int) (w / 2));
        sc.setRotateYCenter((int) (h / 2));
        sc.setMatrixsNormal();
        kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponenteach.ShapeComponentPicture sp = pic.getShapeComponentPicture();
        sp.getLeftTop().setX(0); sp.getLeftTop().setY(0);
        sp.getRightTop().setX(w); sp.getRightTop().setY(0);
        sp.getLeftBottom().setX(0); sp.getLeftBottom().setY(h);
        sp.getRightBottom().setX(w); sp.getRightBottom().setY(h);
        sp.setLeftAfterCutting(0);
        sp.setTopAfterCutting(0);
        sp.setRightAfterCutting((int) w);
        sp.setBottomAfterCutting((int) h);
        sp.setImageWidth(w);
        sp.setImageHeight(h);
        sp.getPictureInfo().setBinItemID(bin);

        List<Paragraph> out = new ArrayList<>();
        Paragraph p = c.proto("GAP10");
        p.getHeader().setParaShapeId(center());
        insertControlFront(p, pic, 11, "gso ");
        out.add(p);
        String cap = str(it, "caption", "");
        if (!cap.isEmpty()) {
            out.add(c.proto("GAP4"));
            Paragraph cp = c.proto("NOTE");
            setText(cp, cap, c.num("cs.NOTE"));
            cp.getHeader().setParaShapeId(center());
            out.add(cp);
        }
        return out;
    }

    ControlPicture logo() {
        for (Paragraph p : c.protoRange("COVER_FULL")) {
            ControlPicture pic = picture(p);
            if (pic != null) return pic;
        }
        throw new IllegalStateException("base 템플릿에 그림 원형(로고)이 없음");
    }

    /** 그림 파일을 문서에 넣고(BinData) 그림 번호를 돌려준다. */
    int registerBin(byte[] data, String ext) {
        int id = c.hwp.getDocInfo().getBinDataList().size() + 1;
        BinData bd = c.hwp.getDocInfo().addNewBinData();
        bd.getProperty().setType(BinDataType.Embedding);
        bd.getProperty().setCompress(BinDataCompress.ByStorageDefault);
        bd.getProperty().setState(BinDataState.NotAccess);
        bd.setBinDataID(id);
        bd.setExtensionForEmbedding(ext);
        c.hwp.getBinData().addNewEmbeddedBinaryData(String.format("BIN%04X.%s", id, ext), data, BinDataCompress.ByStorageDefault);
        return id;
    }

    int center() {
        if (centerPs >= 0) return centerPs;
        ParaShape ps = c.hwp.getDocInfo().getParaShapeList().get(0).clone();
        ps.getProperty1().setAlignment(Alignment.Center);
        ps.setIndent(0);
        ps.setLeftMargin(0);
        c.hwp.getDocInfo().getParaShapeList().add(ps);
        centerPs = c.hwp.getDocInfo().getParaShapeList().size() - 1;
        return centerPs;
    }
}
