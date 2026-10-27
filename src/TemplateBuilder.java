import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControl;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * 원본 코스콤 템플릿(.hwp)에서 안내 주석·스테이플러·예시 박스를 걷어내고
 * 블록별 원형 하나씩만 남긴 base 템플릿과 블록 카탈로그(.properties)를 만든다.
 *
 * 사용: java TemplateBuilder <요약 Template.hwp> <상세 Template.hwp> <출력 폴더>
 *
 * 원형 문단의 텍스트에는 {{이름}} placeholder가 들어가며, 렌더러는 구간별 글자모양을 유지한 채 값을 채운다.
 */
public class TemplateBuilder extends HwpOps {
    static final String BOX = ""; // 템플릿의 □ 기호(PUA)

    // 단계별 내어쓰기(파일 저장값 = HWPUNIT×2). "  ㅇ " 같은 머리 기호 폭만큼 둘째 줄을 들여 맞춘다.
    static final int IND_O = -7500, IND_DASH = -9000, IND_DOT = -11500, IND_NOTE = -9800, IND_REF = -9000;

    HWPFile hwp;
    Paragraph[] src;
    final Map<String, Integer> csCache = new HashMap<>();
    final List<Paragraph> order = new ArrayList<>();
    final Map<String, String> catalog = new LinkedHashMap<>();
    final List<Integer> pageBreaks = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String out = args[2];
        new TemplateBuilder().summary(args[0], out);
        new TemplateBuilder().detail(args[1], out);
    }

    // ================================================================= 요약보고

    void summary(String path, String outDir) throws Exception {
        load(path);
        Paragraph sec = src[0];
        removeControl(sec, firstTable(sec));
        add("SECTION", sec);

        Paragraph title = src[2];
        for (Control c : new ArrayList<>(title.getControlList()))
            if (c instanceof GsoControl) removeControl(title, c);
        setRuns(title, new ArrayList<>());
        ControlTable tt = firstTable(title);
        runs(cell(tt, 1, 0).getParagraphList().getParagraph(0), run("{{TITLE}}", 44));
        runs(cell(tt, 3, 0).getParagraphList().getParagraph(0), run("담당: {{담당}} / 팀장: {{팀장}} / 부서장: {{부서장}}", 12));
        runs(cell(tt, 3, 1).getParagraphList().getParagraph(0), run("{{DATE}}, {{DEPT}}", 24));
        add("TITLE_BOX", title);

        body(3, 5, 11, 13, 7, 15, 1, 9, 11, 19, 10);

        Paragraph box = src[21];
        ParagraphList bp = firstTable(box).getRowList().get(0).getCellList().get(0).getParagraphList();
        box(box, bp, 23, 25, 23);

        tables(src[25], src[28], 32, 33);
        attach(src[30], src[34], src[35], 2);
        write(outDir + "/summary_base.hwp", outDir + "/summary_catalog.properties");
    }

    // ================================================================= 상세보고

    void detail(String path, String outDir) throws Exception {
        load(path);
        Paragraph sec = src[0];
        removeControl(sec, firstTable(sec));
        add("SECTION", sec);

        // 정식 표지: 로고 + 제목 + 날짜 + 본부/부서 + 담당자 (원본 #14~#37)
        for (int i : new int[]{14, 15})
            for (Control c : new ArrayList<>(src[i].getControlList()))
                if (c instanceof GsoControl && !(c instanceof ControlPicture)) removeControl(src[i], c);
        cover(18, 28, 32, 37);
        addRange("COVER_FULL", 14, 37);

        // 상세 목차(장 + 절)
        Paragraph toc = src[38];
        ParagraphList tl = cell(firstTable(toc), 2, 0).getParagraphList();
        runs(tl.getParagraph(1), run("{{ROMAN}}. {{TEXT}}\t {{PAGE}}", 14));
        runs(tl.getParagraph(5), run("  {{NUM}}. {{TEXT}}\t {{PAGE}}", 39));
        runs(tl.getParagraph(20), run("<별첨> {{TEXT}}\t{{PAGE}}", 17));
        keepParagraphs(tl, 0, 1, 3, 5, 20, 21);
        fixCellParaCount(cell(firstTable(toc), 2, 0));
        catalog.put("toc.TOP_GAP", "0");
        catalog.put("toc.CHAPTER", "1");
        catalog.put("toc.CHAPTER_GAP", "2");
        catalog.put("toc.SECTION", "3");
        catalog.put("toc.APPENDIX", "4");
        catalog.put("toc.BOTTOM_GAP", "5");
        add("TOC_FULL", toc);
        pageBreaks.add(order.size() - 1);

        // 간략 표지: 제목 + 장 목차 + 날짜 + 본부/부서 + 담당자 한 쪽 (원본 #3~#13)
        cover(3, 9, 11, 13);
        ParagraphList cl = cell(firstTable(src[6]), 2, 0).getParagraphList();
        runs(cl.getParagraph(0), run("{{ROMAN}}", 59), run(". {{TEXT}}\t {{PAGE}}", 14));
        keepParagraphs(cl, 0, 1);
        fixCellParaCount(cell(firstTable(src[6]), 2, 0));
        catalog.put("compact_toc.CHAPTER", "0");
        catalog.put("compact_toc.CHAPTER_GAP", "1");
        int compactStart = order.size();
        addRange("COVER_COMPACT", 3, 13);
        pageBreaks.add(compactStart);

        // 장 제목 박스 / 절 번호 박스
        runs(firstTable(src[40]).getRowList().get(0).getCellList().get(0).getParagraphList().getParagraph(0),
                run("{{ROMAN}}. {{TEXT}}", 29));
        add("CHAPTER", src[40]);
        pageBreaks.add(order.size() - 1);
        ControlTable part = firstTable(src[42]);
        runs(cell(part, 0, 0).getParagraphList().getParagraph(0), run("{{NUM}}", 45));
        runs(cell(part, 0, 2).getParagraphList().getParagraph(0), run(" ", 29), run("{{TEXT}}", 70));
        add("PART", src[42]);

        body(44, 46, 52, 54, 48, 56, 1, 9, 11, 21, 12);

        Paragraph box = src[62];
        ParagraphList bp = firstTable(box).getRowList().get(0).getCellList().get(0).getParagraphList();
        box(box, bp, 22, 23, 22);

        tables(src[66], src[69], 51, 52);
        attach(src[70], src[74], src[75], 14);
        write(outDir + "/detail_base.hwp", outDir + "/detail_catalog.properties");
    }

    // ================================================================= 공통 블록

    /** 본문 단계 블록(H1, ㅇ, ①, -, ·, *, ※)과 간격 문단을 만든다. */
    void body(int iH1, int iO, int iDash, int iDot, int iNote, int iRef, int iEmpty,
              int csBody, int csBold, int csNote, int csGapBase) throws Exception {
        int ps0 = 0;
        runs(src[iH1], run("{{MARK}} {{TEXT}}", 10));
        src[iH1].getHeader().setParaShapeId(ps0);
        add("H1", src[iH1]);

        int psO = addParaShape(hwp, ps0, 0, IND_O);
        Paragraph num = src[iO].clone();
        runs(src[iO], run("  ㅇ ", csBody), run("({{LABEL}}) ", csBold), run("{{TEXT}}", csBody));
        src[iO].getHeader().setParaShapeId(psO);
        add("O", src[iO]);
        runs(num, run("  {{NUM}} {{TEXT}}", csBold));
        num.getHeader().setParaShapeId(psO);
        add("NUM", num);

        int psDash = addParaShape(hwp, ps0, 0, IND_DASH);
        runs(src[iDash], run("    - ", csBody), run("({{LABEL}}) ", csBold), run("{{TEXT}}", csBody));
        src[iDash].getHeader().setParaShapeId(psDash);
        add("DASH", src[iDash]);

        int psDot = addParaShape(hwp, ps0, 0, IND_DOT);
        runs(src[iDot], run("      · {{TEXT}}", csBody));
        src[iDot].getHeader().setParaShapeId(psDot);
        add("DOT", src[iDot]);

        int psNote = addParaShape(hwp, ps0, 0, IND_NOTE);
        runs(src[iNote], run("      * {{TEXT}}", csNote));
        src[iNote].getHeader().setParaShapeId(psNote);
        add("NOTE", src[iNote]);

        int psRef = addParaShape(hwp, ps0, 0, IND_REF);
        runs(src[iRef], run("     ※ {{TEXT}}", csNote));
        src[iRef].getHeader().setParaShapeId(psRef);
        add("REF", src[iRef]);

        for (int pt : new int[]{4, 6, 8, 10, 12}) {
            Paragraph g = src[iEmpty].clone();
            setEmpty(g, ensureCharShape(hwp, csCache, csGapBase, pt, false));
            g.getHeader().setParaShapeId(ps0);
            add("GAP" + pt, g);
        }
        catalog.put("cs.BODY", "" + csBody);
        catalog.put("cs.BOLD", "" + csBold);
        catalog.put("cs.NOTE", "" + csNote);
        catalog.put("ps.O", "" + psO);
        catalog.put("ps.DASH", "" + psDash);
        catalog.put("ps.DOT", "" + psDot);
        catalog.put("ps.NOTE", "" + psNote);
        catalog.put("ps.REF", "" + psRef);
    }

    /** 노란 강조 박스: 제목, ㅇ, -, ※ 원형과 박스 안 간격 문단만 남긴다. */
    void box(Paragraph box, ParagraphList bp, int csTitle, int csText, int csRef) throws Exception {
        runs(bp.getParagraph(0), run("<{{TITLE}}>", csTitle));
        runs(bp.getParagraph(2), run("ㅇ {{TEXT}}", csText));
        runs(bp.getParagraph(10), run("  - {{TEXT}}", csText));
        runs(bp.getParagraph(14), run("※ {{TEXT}}", csRef));
        keepParagraphs(bp, 0, 1, 2, 3, 9, 10, 14);
        fixCellParaCount(firstTable(box).getRowList().get(0).getCellList().get(0));
        catalog.put("box.TITLE", "0");
        catalog.put("box.TITLE_GAP", "1");
        catalog.put("box.O", "2");
        catalog.put("box.GAP", "3");
        catalog.put("box.DASH_GAP", "4");
        catalog.put("box.DASH", "5");
        catalog.put("box.REF", "6");
        shrink(firstTable(box), false);
        firstTable(box).getTable().getProperty().setDivideAtPageBoundary(
                kr.dogfoot.hwplib.object.bodytext.control.table.DivideAtPageBoundary.NoDivide); // 박스는 쪽 경계에서 쪼개지 않음
        add("BOX", box);
    }

    /** 삽입 표 Type 1(격자)·Type 2(좌우 개방): 머리행·중간행·마지막행 원형. */
    void tables(Paragraph t1, Paragraph t2, int csHead, int csCell) throws Exception {
        for (Paragraph p : new Paragraph[]{t1, t2}) {
            // 쪽이 넘어가면 행 단위로 나누고 머리행을 반복
            firstTable(p).getTable().getProperty().setDivideAtPageBoundary(
                    kr.dogfoot.hwplib.object.bodytext.control.table.DivideAtPageBoundary.DivideByCell);
            firstTable(p).getTable().getProperty().setAutoRepeatTitleRow(true);
            for (Row r : firstTable(p).getRowList())
                for (Cell c : r.getCellList()) {
                    boolean head = c.getListHeader().getRowIndex() == 0;
                    runs(c.getParagraphList().getParagraph(0), run(head ? "{{HEAD}}" : "{{CELL}}", head ? csHead : csCell));
                }
        }
        add("TABLE1", t1);
        add("TABLE2", t2);
        catalog.put("table.ROW_HEAD", "0");
        catalog.put("table.ROW_MIDDLE", "1");
        catalog.put("table.ROW_LAST", "2");
    }

    /** 첨부/참고 라벨 박스와 참고용 점선 박스. */
    void attach(Paragraph att, Paragraph ref, Paragraph dot, int csTitle) throws Exception {
        runs(cell(firstTable(att), 0, 2).getParagraphList().getParagraph(0), run("{{TEXT}}", csTitle));
        runs(cell(firstTable(ref), 0, 2).getParagraphList().getParagraph(0), run("{{TEXT}}", csTitle));
        add("ATTACH", att);
        pageBreaks.add(order.size() - 1);
        add("REFBOX", ref);
        pageBreaks.add(order.size() - 1);
        Cell dc = firstTable(dot).getRowList().get(0).getCellList().get(0);
        keepParagraphs(dc.getParagraphList(), 0);
        runs(dc.getParagraphList().getParagraph(0), run("{{CONTENT}}", 9));
        dc.getParagraphList().getParagraph(0).getHeader().setParaShapeId(8);
        fixCellParaCount(dc);
        shrink(firstTable(dot), true);
        add("DOTBOX", dot);
    }

    /** 1×1 박스의 고정 높이를 없애 내용만큼만 커지게 한다(top=true면 글자를 위쪽에 붙임). */
    static void shrink(ControlTable t, boolean top) {
        Cell c = t.getRowList().get(0).getCellList().get(0);
        c.getListHeader().setHeight(1000);
        if (top) c.getListHeader().getProperty().setTextVerticalAlignment(
                kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.TextVerticalAlignment.Top);
        t.getHeader().setHeight(1000);
    }

    /** 표지의 제목·날짜·본부/부서·담당자 칸을 placeholder로 바꾼다. */
    void cover(int iTitle, int iDate, int iDept, int iMan) throws Exception {
        runs(cell(firstTable(src[iTitle]), 1, 0).getParagraphList().getParagraph(0), run("{{TITLE}}", 28));
        runs(src[iDate], run("{{DATE}}", 36));
        ControlTable dept = firstTable(src[iDept]);
        runs(cell(dept, 0, 0).getParagraphList().getParagraph(0), run("{{HQ}}", 30));
        runs(cell(dept, 1, 0).getParagraphList().getParagraph(0), run("{{DEPT}}", 30));
        runs(firstTable(src[iMan]).getRowList().get(0).getCellList().get(0).getParagraphList().getParagraph(0),
                run("담당자: ", 41), run("{{담당}}", 0), run(" / 팀장: ", 41), run("{{팀장}}", 0),
                run(" / 부장: ", 41), run("{{부장}}", 0));
    }

    // ================================================================= 입출력

    void load(String path) throws Exception {
        hwp = HWPReader.fromFile(path);
        src = hwp.getBodyText().getSectionList().get(0).getParagraphs().clone();
    }

    static void runs(Paragraph p, HwpOps.Run... rs) throws Exception {
        List<HwpOps.Run> l = new ArrayList<>();
        for (HwpOps.Run r : rs) l.add(r);
        setRuns(p, l);
    }

    void add(String name, Paragraph p) {
        catalog.put("block." + name, "" + order.size());
        order.add(p);
    }

    void addRange(String name, int from, int to) {
        catalog.put("block." + name, order.size() + "-" + (order.size() + to - from));
        for (int i = from; i <= to; i++) order.add(src[i]);
    }

    void write(String hwpPath, String catalogPath) throws Exception {
        Section s = hwp.getBodyText().getSectionList().get(0);
        s.deleteAllParagraphs();
        for (int i = 0; i < order.size(); i++) {
            Paragraph p = order.get(i);
            p.getHeader().getDivideSort().setDividePage(pageBreaks.contains(i));
            p.getHeader().setLastInList(i == order.size() - 1);
            s.insertParagraph(i, p);
        }
        HWPWriter.toFile(hwp, hwpPath);
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(Paths.get(catalogPath)), StandardCharsets.UTF_8))) {
            w.println("# " + Paths.get(hwpPath).getFileName() + " 블록 카탈로그 (TemplateBuilder 생성, 손으로 고치지 말 것)");
            w.println("# block.이름=문단 번호(범위는 a-b) / cs.*=글자모양 번호 / ps.*=문단모양 번호 / box.*, toc.*, table.*=표 안 원형 위치");
            for (Map.Entry<String, String> e : catalog.entrySet()) w.println(e.getKey() + "=" + e.getValue());
        }
        System.out.println("written " + hwpPath + " (" + order.size() + " paragraphs)");
    }
}
