import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.object.docinfo.parashape.Alignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 삽입 표 원형(3행×4열: 머리행/중간행/마지막행 × 첫 열/중간 열/끝 열)으로 임의 크기의 표를 만든다.
 *
 * 칸 값 규칙
 *  - "^" : 바로 위 칸과 병합(세로) / "<" : 바로 왼쪽 칸과 병합(가로)
 *  - "\n": 칸 안 줄바꿈(문단을 나눔)
 * 병합 칸의 테두리는 그 칸이 끝나는 행(마지막행이면 마지막행 원형)과 걸친 열(첫/끝 열 포함 여부)을 따른다.
 */
public class TableBuilder extends HwpOps {
    public static final String UP = "^", LEFT = "<";

    final HWPFile hwp;
    final Map<Integer, Integer> leftAligned = new HashMap<>();

    public TableBuilder(HWPFile hwp) { this.hwp = hwp; }

    /** 병합 칸 하나: 시작 위치와 크기, 텍스트. */
    static class Span {
        int r, c, rows = 1, cols = 1;
        String text;
    }

    /**
     * @param t          원형 표(복제본). 이 객체를 직접 바꾼다.
     * @param grid       행별 칸 값(짧은 행은 빈칸으로 채움)
     * @param headerRows 머리행 수(1 이상)
     * @param ratios     열 너비 비율(null이면 내용 길이로 자동)
     * @param align      열별 본문 정렬 "center"/"left"(null이면 모두 가운데)
     * @param maxTotal   0보다 크면 표 전체 폭 상한(HWPUNIT). 원형 폭이 더 넓으면 이 폭으로 줄인다(점선 박스 안 등)
     */
    public void build(ControlTable t, List<List<String>> grid, int headerRows, double[] ratios, String[] align, long maxTotal) {
        int R = grid.size(), C = 0;
        for (List<String> row : grid) C = Math.max(C, row.size());
        if (R < headerRows + 1) throw new IllegalArgumentException("표는 머리행 " + headerRows + "개 + 본문 1행 이상 필요");
        String[][] g = new String[R][C];
        for (int r = 0; r < R; r++)
            for (int c = 0; c < C; c++) {
                String v = c < grid.get(r).size() ? grid.get(r).get(c) : "";
                g[r][c] = v == null ? "" : v;
            }

        Span[][] owner = spans(g, R, C);
        long[] colW = widths(t, g, owner, R, C, ratios, maxTotal);

        // 원형 칸: [행 역할][열 역할]
        List<Row> pr = t.getRowList();
        Cell[][] proto = new Cell[3][3];
        int lastProtoCol = t.getTable().getColumnCount() - 1;
        for (int rr = 0; rr < 3; rr++) {
            proto[rr][0] = cell(t, rr, 0);
            proto[rr][1] = cell(t, rr, Math.min(1, lastProtoCol));
            proto[rr][2] = cell(t, rr, lastProtoCol);
        }
        Row rowProto = pr.get(0).clone();
        rowProto.getCellList().clear();

        List<Row> rows = new ArrayList<>();
        for (int r = 0; r < R; r++) {
            Row row = rowProto.clone();
            for (int c = 0; c < C; c++) {
                Span s = owner[r][c];
                if (s.r != r || s.c != c) continue;
                int bottom = s.r + s.rows - 1;
                int rowRole = bottom < headerRows ? 0 : (bottom == R - 1 ? 2 : 1);
                int colRole = s.c == 0 ? 0 : (s.c + s.cols - 1 == C - 1 ? 2 : 1);
                Cell cell = proto[rowRole][colRole].clone();
                if (s.r >= headerRows) {
                    // 위쪽 선: 첫 본문 행은 원형 중간행의 위쪽(Type 2는 이중선), 그 아래 행은 원형 중간행의 아래쪽(가는 선)
                    int mid = proto[1][colRole].getListHeader().getBorderFillId();
                    cell.getListHeader().setBorderFillId(withTop(cell.getListHeader().getBorderFillId(), mid, s.r > headerRows));
                }
                cell.getListHeader().setRowIndex(s.r);
                cell.getListHeader().setColIndex(s.c);
                cell.getListHeader().setRowSpan(s.rows);
                cell.getListHeader().setColSpan(s.cols);
                long w = 0;
                for (int k = s.c; k < s.c + s.cols; k++) w += colW[k];
                cell.getListHeader().setWidth(w);
                boolean head = s.r < headerRows;
                Paragraph para = cell.getParagraphList().getParagraph(0);
                List<Paragraph> ps = new ArrayList<>();
                for (String line : splitLines(s.text)) {
                    Paragraph p = para.clone();
                    try {
                        fill(p, vals(head ? "HEAD" : "CELL", line.isEmpty() ? null : line));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if (!head && align != null && s.c < align.length && "left".equals(align[s.c]))
                        p.getHeader().setParaShapeId(leftOf(p.getHeader().getParaShapeId()));
                    ps.add(p);
                }
                Composer.setCellParagraphs(cell, ps);
                if (!head && align != null && s.c < align.length && "left".equals(align[s.c])) {
                    // 왼쪽 정렬 칸은 글자가 테두리에 붙지 않게 칸 자체 안쪽 여백(좌우 1.8mm)을 쓴다
                    cell.getListHeader().getProperty().setApplyInnerMagin(true);
                    cell.getListHeader().setLeftMargin(510);
                    cell.getListHeader().setRightMargin(510);
                    cell.getListHeader().setTopMargin(t.getTable().getTopInnerMargin());
                    cell.getListHeader().setBottomMargin(t.getTable().getBottomInnerMargin());
                }
                row.getCellList().add(cell);
            }
            rows.add(row);
        }

        t.getRowList().clear();
        t.getRowList().addAll(rows);
        t.getTable().setRowCount(R);
        t.getTable().setColumnCount(C);
        t.getTable().getCellCountOfRowList().clear();
        for (Row row : rows) t.getTable().addCellCountOfRow(row.getCellList().size());
        long total = 0;
        for (long w : colW) total += w;
        t.getHeader().setWidth(total);
    }

    /** "^"/"<" 표시를 풀어 칸마다 소유 병합 칸을 정하고, 직사각형인지 확인한다. */
    static Span[][] spans(String[][] g, int R, int C) {
        Span[][] o = new Span[R][C];
        for (int r = 0; r < R; r++)
            for (int c = 0; c < C; c++) {
                String v = g[r][c].trim();
                if (v.equals(UP) && r > 0) o[r][c] = o[r - 1][c];
                else if (v.equals(LEFT) && c > 0) o[r][c] = o[r][c - 1];
                else {
                    Span s = new Span();
                    s.r = r; s.c = c; s.text = g[r][c];
                    o[r][c] = s;
                }
                Span s = o[r][c];
                s.rows = Math.max(s.rows, r - s.r + 1);
                s.cols = Math.max(s.cols, c - s.c + 1);
            }
        for (int r = 0; r < R; r++)
            for (int c = 0; c < C; c++) {
                Span s = o[r][c];
                for (int rr = s.r; rr < s.r + s.rows; rr++)
                    for (int cc = s.c; cc < s.c + s.cols; cc++)
                        if (o[rr][cc] != s)
                            throw new IllegalArgumentException("병합 범위가 직사각형이 아님: (" + (s.r + 1) + "행 " + (s.c + 1) + "열) '" + s.text + "'");
            }
        return o;
    }

    /** 열 너비(HWPUNIT). 표 전체 너비는 원형과 같게 유지한다. */
    long[] widths(ControlTable t, String[][] g, Span[][] owner, int R, int C, double[] ratios, long maxTotal) {
        long total = 0;
        for (Cell c : t.getRowList().get(0).getCellList()) total += c.getListHeader().getWidth();
        if (Renderer.DEBUG) System.out.println("DEBUG table protoTotal=" + total + " max=" + maxTotal);
        if (maxTotal > 0 && total > maxTotal) total = maxTotal;
        double[] wt = new double[C];
        if (ratios != null && ratios.length >= C) {
            for (int c = 0; c < C; c++) wt[c] = Math.max(0.01, ratios[c]);
        } else {
            // 각 열에 "가장 긴 줄이 한 줄에 들어가는 폭"을 주고, 표 폭을 넘으면 넓은 열부터 깎는다(물 채우기).
            // 좁은 열(구분·번호 등)은 그대로 두고, 남는 폭은 비율대로 나눈다.
            double totalPt = total / 100.0, pad = 1.3 * 13;
            double[] nat = new double[C];
            for (int c = 0; c < C; c++) {
                double m = 2 * 13;
                for (int r = 0; r < R; r++) {
                    Span s = owner[r][c];
                    if (s.r != r || s.c != c || s.cols > 1) continue;
                    boolean bold = s.r < 1 || s.text.contains("**");
                    for (String line : s.text.replace("**", "").split("\n"))
                        m = Math.max(m, textWidth(line, 13) * (bold ? Layout.BOLD : 1));
                }
                nat[c] = m + pad;
            }
            double sumNat = 0;
            for (double v : nat) sumNat += v;
            if (sumNat <= totalPt) {
                for (int c = 0; c < C; c++) wt[c] = nat[c];
            } else {
                double lo = 0, hi = 0;
                for (double v : nat) hi = Math.max(hi, v);
                for (int it = 0; it < 60; it++) { // sum(min(nat, cap)) == totalPt 인 cap 찾기
                    double cap = (lo + hi) / 2, s = 0;
                    for (double v : nat) s += Math.min(v, cap);
                    if (s > totalPt) hi = cap; else lo = cap;
                }
                for (int c = 0; c < C; c++) wt[c] = Math.min(nat[c], lo);
            }
        }
        double sum = 0;
        for (double w : wt) sum += w;
        long[] out = new long[C];
        long acc = 0;
        for (int c = 0; c < C; c++) {
            out[c] = c == C - 1 ? total - acc : Math.round(total * wt[c] / sum);
            acc += out[c];
        }
        return out;
    }

    /** 칸 텍스트를 줄로 나눈다. **굵게**가 줄을 넘어가면 각 줄에서 닫고 다시 연다. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        boolean open = false;
        for (String line : text.split("\n", -1)) {
            String l = open ? "**" + line : line;
            int n = 0, i = 0;
            while ((i = l.indexOf("**", i)) >= 0) { n++; i += 2; }
            open = n % 2 == 1;
            out.add(open ? l + "**" : l);
        }
        return out;
    }

    static double textWidth(String s, double size) {
        double w = 0;
        for (char ch : s.toCharArray()) w += Layout.em(ch) * size;
        return w;
    }

    final Map<String, Integer> borderCache = new HashMap<>();

    /**
     * bf의 위쪽 선만 src의 위쪽(useBottom=false) 또는 아래쪽(useBottom=true) 선으로 바꾼 테두리/배경을 돌려준다.
     * 테두리/배경 번호는 1부터 센다(목록 위치 + 1).
     */
    int withTop(int bf, int src, boolean useBottom) {
        String key = bf + "/" + src + "/" + useBottom;
        if (borderCache.containsKey(key)) return borderCache.get(key);
        List<kr.dogfoot.hwplib.object.docinfo.BorderFill> l = hwp.getDocInfo().getBorderFillList();
        kr.dogfoot.hwplib.object.docinfo.BorderFill s = l.get(src - 1);
        kr.dogfoot.hwplib.object.docinfo.borderfill.EachBorder want = useBottom ? s.getBottomBorder() : s.getTopBorder();
        kr.dogfoot.hwplib.object.docinfo.BorderFill b = l.get(bf - 1);
        if (b.getTopBorder().getType() == want.getType() && b.getTopBorder().getThickness() == want.getThickness()) {
            borderCache.put(key, bf);
            return bf;
        }
        kr.dogfoot.hwplib.object.docinfo.BorderFill n = b.clone();
        n.getTopBorder().copy(want);
        l.add(n);
        borderCache.put(key, l.size());
        return l.size();
    }

    /** 가운데 정렬 문단모양의 왼쪽 정렬 변형. */
    int leftOf(int ps) {
        if (leftAligned.containsKey(ps)) return leftAligned.get(ps);
        ParaShape n = hwp.getDocInfo().getParaShapeList().get(ps).clone();
        n.getProperty1().setAlignment(Alignment.Left);
        hwp.getDocInfo().getParaShapeList().add(n);
        int id = hwp.getDocInfo().getParaShapeList().size() - 1;
        leftAligned.put(ps, id);
        return id;
    }
}
