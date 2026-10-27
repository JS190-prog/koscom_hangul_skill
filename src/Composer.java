import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * base 템플릿의 블록 원형을 복제·채워서 새 문서를 조립한다.
 * 블록 위치는 TemplateBuilder가 만든 카탈로그(.properties)를 따른다.
 */
public class Composer extends HwpOps {
    final HWPFile hwp;
    final Properties cat = new Properties();
    final Paragraph[] base;
    final List<Paragraph> out = new ArrayList<>();

    public Composer(String basePath, String catalogPath) throws Exception {
        hwp = HWPReader.fromFile(basePath);
        try (InputStreamReader r = new InputStreamReader(Files.newInputStream(Paths.get(catalogPath)), StandardCharsets.UTF_8)) {
            cat.load(r);
        }
        base = hwp.getBodyText().getSectionList().get(0).getParagraphs().clone();
    }

    int index(String block) {
        String v = cat.getProperty("block." + block);
        if (v == null) throw new IllegalArgumentException("unknown block " + block);
        return Integer.parseInt(v.split("-")[0]);
    }

    public int num(String key) { return Integer.parseInt(cat.getProperty(key)); }

    /** 단일 문단 블록의 복제본. */
    public Paragraph proto(String block) { return base[index(block)].clone(); }

    /** 여러 문단 블록(표지 등)의 복제본. */
    public List<Paragraph> protoRange(String block) {
        String[] r = cat.getProperty("block." + block).split("-");
        List<Paragraph> l = new ArrayList<>();
        for (int i = Integer.parseInt(r[0]); i <= Integer.parseInt(r[r.length - 1]); i++) l.add(base[i].clone());
        return l;
    }

    /** 블록을 복제해 placeholder를 채우고 문서 끝에 붙인다. */
    public Paragraph add(String block, String... kv) throws Exception {
        Paragraph p = proto(block);
        if (kv.length > 0) fill(p, vals(kv));
        out.add(p);
        return p;
    }

    public void addPara(Paragraph p) { out.add(p); }

    /** 표 안의 모든 문단에 값을 채운다(placeholder가 없는 문단은 그대로). */
    public static void fillTable(ControlTable t, Map<String, String> v) throws Exception {
        for (Row r : t.getRowList())
            for (Cell c : r.getCellList())
                for (Paragraph p : c.getParagraphList())
                    if (plainText(p).contains("{{")) fill(p, v);
    }

    /** 셀 문단 목록을 원형 문단 복제본들로 다시 채운다. */
    public static void setCellParagraphs(Cell c, List<Paragraph> ps) {
        ParagraphList pl = c.getParagraphList();
        pl.deleteAllParagraphs();
        for (int i = 0; i < ps.size(); i++) {
            ps.get(i).getHeader().setLastInList(i == ps.size() - 1);
            pl.insertParagraph(i, ps.get(i));
        }
        c.getListHeader().setParaCount(ps.size());
    }

    /**
     * 데이터 표 채우기: rows[0]은 머리행, 나머지는 본문. 원형의 중간행을 복제하고 마지막 행은 마지막행 원형을 쓴다.
     * (열 개수는 원형과 같아야 함 — 열 변경은 P3)
     */
    public void fillDataTable(ControlTable t, String[][] rows) throws Exception {
        List<Row> protoRows = t.getRowList();
        Row head = protoRows.get(num("table.ROW_HEAD")), mid = protoRows.get(num("table.ROW_MIDDLE")),
                last = protoRows.get(num("table.ROW_LAST"));
        List<Row> built = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            Row src = i == 0 ? head : (i == rows.length - 1 ? last : mid);
            Row r = src.clone();
            for (int k = 0; k < r.getCellList().size(); k++) {
                Cell c = r.getCellList().get(k);
                c.getListHeader().setRowIndex(i);
                fill(c.getParagraphList().getParagraph(0), vals(i == 0 ? "HEAD" : "CELL", k < rows[i].length ? rows[i][k] : " "));
            }
            built.add(r);
        }
        long h = 0;
        for (Row r : built) h += r.getCellList().get(0).getListHeader().getHeight();
        t.getRowList().clear();
        t.getRowList().addAll(built);
        t.getTable().setRowCount(built.size());
        t.getTable().getCellCountOfRowList().clear();
        for (Row r : built) t.getTable().addCellCountOfRow(r.getCellList().size());
        t.getHeader().setHeight(h);
    }

    /** 조립한 문단으로 본문을 바꿔 저장한다. 구역 정의 컨트롤은 첫 문단 앞으로 옮긴다(빈 줄 방지). */
    public void save(String path) throws Exception {
        Paragraph sec = base[index("SECTION")].clone();
        moveControlsToFront(sec, out.get(0));
        Section s = hwp.getBodyText().getSectionList().get(0);
        s.deleteAllParagraphs();
        for (int i = 0; i < out.size(); i++) {
            out.get(i).getHeader().setLastInList(i == out.size() - 1);
            s.insertParagraph(i, out.get(i));
        }
        HWPWriter.toFile(hwp, path);
    }
}
