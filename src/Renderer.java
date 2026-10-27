import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlSectionDefine;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PageDef;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * report.json → 코스콤 서식 .hwp
 *
 * 사용: java Renderer <report.json> <출력.hwp> <templates 폴더>
 * 표준출력: 경고는 "WARN ..." 줄, 마지막 줄은 "OK <출력 경로>". 오류면 "ERROR ..."를 찍고 종료 코드 1.
 *
 * 간격·번호·굵은 라벨은 자동으로 붙인다. 본문의 **굵게**는 굵은 글자로 바꾼다.
 */
public class Renderer extends HwpOps {
    static final String BOX_MARK = ""; // □ (템플릿 원형의 PUA 기호)
    static final String[] CIRCLED = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"};
    static final int MAX_LINES = 3;
    /** 원형마다 텍스트 앞에 붙는 머리 기호(자간 조정에서 제외할 부분). num은 "  " + 원문자 + " ". */
    static final Map<String, String> PREFIX = new HashMap<>();
    static {
        PREFIX.put("o", "  ㅇ ");
        PREFIX.put("num", "  ");
        PREFIX.put("dash", "    - ");
        PREFIX.put("dot", "      · ");
        PREFIX.put("note", "      * ");
        PREFIX.put("ref", "     ※ ");
    }

    final Composer c;
    final Map<String, Integer> csCache = new HashMap<>();
    final List<String> warnings = new ArrayList<>();
    final double bodyWidthPt;

    final TableBuilder tables;
    final Map<String, Integer> boxPs = new HashMap<>();
    /** 0보다 크면 표 전체 폭 상한(HWPUNIT). 점선 박스 안을 그릴 때만 쓴다. */
    long tableMaxWidth = 0;
    /** 쪽 끝에 혼자 남지 않게 다음 문단과 함께 둘 제목 문단(대제목, ①, 장, 절). */
    final java.util.Set<Paragraph> keepNext = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    final Map<Integer, Integer> togetherPs = new HashMap<>();

    /** 제목 문단과 그 뒤에 이어지는 빈 간격 문단에 "다음 문단과 함께" 속성을 준다. */
    void applyKeepNext(List<Paragraph> out) {
        for (int i = 0; i < out.size(); i++) {
            if (!keepNext.contains(out.get(i))) continue;
            for (int k = i; k < out.size() && (k == i || out.get(k).getText() == null && out.get(k).getControlList() == null); k++) {
                Paragraph p = out.get(k);
                int ps = p.getHeader().getParaShapeId();
                if (!togetherPs.containsKey(ps)) {
                    kr.dogfoot.hwplib.object.docinfo.ParaShape n = c.hwp.getDocInfo().getParaShapeList().get(ps).clone();
                    n.getProperty1().setTogetherNextPara(true);
                    c.hwp.getDocInfo().getParaShapeList().add(n);
                    togetherPs.put(ps, c.hwp.getDocInfo().getParaShapeList().size() - 1);
                }
                p.getHeader().setParaShapeId(togetherPs.get(ps));
            }
        }
    }

    /** 대제목(□ / 1.)이 두 줄로 넘어갈 때 기호 다음 글자에 맞추는 내어쓰기 문단모양. */
    final int psH1;

    Renderer(String templates, String kind) throws Exception {
        c = new Composer(templates + "/" + kind + "_base.hwp", templates + "/" + kind + "_catalog.properties");
        bodyWidthPt = bodyWidth();
        tables = new TableBuilder(c.hwp);
        psH1 = addParaShape(c.hwp, c.proto("H1").getHeader().getParaShapeId(), 0, -4800);
    }

    public static void main(String[] args) {
        try {
            String json = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) Json.parse(json);
            String type = str(r, "type", "summary");
            Renderer rd;
            if (type.equals("detail")) {
                DetailRenderer d = new DetailRenderer(args[2]);
                d.detail(r);
                rd = d;
            } else if (type.equals("summary")) {
                rd = new Renderer(args[2], "summary");
                rd.summary(r);
            } else throw new IllegalArgumentException("알 수 없는 type '" + type + "'(summary 또는 detail)");
            rd.applyKeepNext(rd.c.out);
            rd.c.save(args[1]);
            for (String w : rd.warnings) System.out.println("WARN " + w);
            System.out.println("OK " + args[1]);
        } catch (Exception e) {
            System.out.println("ERROR " + e.getMessage());
            System.exit(1);
        }
    }

    // ================================================================= 요약보고

    void summary(Map<String, Object> r) throws Exception {
        Map<String, Object> ap = map(r, "approval");
        Paragraph title = c.proto("TITLE_BOX");
        ControlTable tt = firstTable(title);
        Composer.fillTable(tt, vals("TITLE", str(r, "title", ""), "담당", str(ap, "담당", ""), "팀장", str(ap, "팀장", ""),
                "부서장", str(ap, "부서장", ""), "DATE", str(r, "date", ""), "DEPT", str(r, "dept", "")));
        checkCellFits(cell(tt, 1, 0), str(r, "title", ""), "제목");
        fitCell(cell(tt, 3, 1), "날짜·부서");
        fitCell(cell(tt, 3, 0), "결재라인");
        c.out.add(title);

        String style = str(r, "heading_style", "box");
        sections(c.out, list(r, "sections"), style);
        attachments(c.out, list(r, "attachments"), style);
        paginate(c.out);
        spillWarnings();
    }

    /** 첨부(본문 블록) / 참고(점선 박스 안 본문). 각각 새 쪽에서 시작한다. */
    void attachments(List<Paragraph> out, List<Object> atts, String style) throws Exception {
        for (Object o : atts) {
            @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) o;
            String kind = str(a, "kind", "첨부");
            if (kind.equals("참고")) {
                Paragraph rb = c.proto("REFBOX");
                Composer.fillTable(firstTable(rb), vals("TEXT", str(a, "title", "")));
                out.add(rb);
                Paragraph dot = c.proto("DOTBOX");
                Cell dc = firstTable(dot).getRowList().get(0).getCellList().get(0);
                List<Paragraph> inner = new ArrayList<>();
                inner.add(c.proto("GAP4"));
                // 점선 박스 안의 표는 박스 안쪽 폭에 맞춘다(원형 표 폭 그대로면 박스 테두리를 넘침)
                long lm = dc.getListHeader().getLeftMargin(), rm = dc.getListHeader().getRightMargin();
                if (lm == 0) lm = firstTable(dot).getTable().getLeftInnerMargin();
                if (rm == 0) rm = firstTable(dot).getTable().getRightInnerMargin();
                // 표는 칸 안쪽 여백 + 표 바깥 여백만큼 오른쪽에 놓인다. 화면에서 좌우 여백이 같아지도록 맞춘 값(약 1.2mm씩)
                tableMaxWidth = dc.getListHeader().getWidth() - 2 * lm - rm - 1200;
                if (DEBUG) System.out.println("DEBUG dotbox cellW=" + dc.getListHeader().getWidth() + " lm=" + lm + " rm=" + rm + " max=" + tableMaxWidth);
                sections(inner, list(a, "sections"), style);
                tableMaxWidth = 0;
                inner.add(c.proto("GAP4"));
                Composer.setCellParagraphs(dc, inner);
                out.add(dot);
            } else {
                Paragraph at = c.proto("ATTACH");
                Composer.fillTable(firstTable(at), vals("TEXT", str(a, "title", "")));
                out.add(at);
                sections(out, list(a, "sections"), style);
            }
        }
    }

    /** 대제목 묶음들: 첫 묶음 앞은 10pt, 묶음 사이는 12pt 간격. */
    void sections(List<Paragraph> out, List<Object> secs, String style) throws Exception {
        sections(out, secs, style, "GAP10");
    }

    /**
     * firstGap: 첫 대제목 앞 간격 블록. 묶음마다 "style"(box/number)로 대제목 모양을 바꿀 수 있고,
     * "page_break": true면 그 대제목에서 새 쪽을 시작한다. 번호(1. 2.)는 번호형 묶음끼리 차례로 센다.
     */
    void sections(List<Paragraph> out, List<Object> secs, String style, String firstGap) throws Exception {
        int n = 0;
        for (int i = 0; i < secs.size(); i++) {
            @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) secs.get(i);
            Paragraph gap = c.proto(i == 0 ? firstGap : "GAP12");
            boolean pb = Boolean.TRUE.equals(s.get("page_break"));
            if (!pb) out.add(gap);
            String st = str(s, "style", style);
            String mark = st.equals("number") ? (++n) + "." : BOX_MARK;
            Paragraph h = superscriptStars(bold(fill(c.proto("H1"), vals("MARK", mark, "TEXT", str(s, "heading", "")))));
            h.getHeader().setParaShapeId(psH1);
            h.getHeader().getDivideSort().setDividePage(pb);
            checkLines(h, "H1", str(s, "heading", ""));
            keepNext.add(h);
            out.add(h);
            items(out, list(s, "items"), "h1");
        }
    }

    /** 그림 항목(상세보고 렌더러가 구현). */
    List<Paragraph> figure(Map<String, Object> it) throws Exception {
        throw new IllegalArgumentException("그림(figure)은 상세보고(type: detail)에서 지원");
    }

    /** 항목 목록을 그리고 마지막 항목 종류를 돌려준다(다음 간격 결정용). */
    String items(List<Paragraph> out, List<Object> items, String prev) throws Exception {
        int numCount = 0;
        for (Object o : items) {
            @SuppressWarnings("unchecked") Map<String, Object> it = (Map<String, Object>) o;
            String type = str(it, "type", "o");
            String gap = gapBefore(prev, type);
            if (gap != null) out.add(c.proto(gap));
            switch (type) {
                case "o": case "dash": case "dot": case "note": case "ref": case "num": {
                    String block = type.equals("o") ? "O" : type.toUpperCase();
                    String num = type.equals("num") ? str(it, "num", CIRCLED[Math.min(numCount++, CIRCLED.length - 1)]) : "";
                    String text = clean(str(it, "text", ""));
                    String label = str(it, "label", "");
                    Paragraph p = bold(fill(c.proto(block), vals("LABEL", label, "TEXT", text, "NUM", num)));
                    // 자간 조정·위첨자는 본문(함초롬바탕 15) 단계에만: 각주·참고(함초롬돋움 12)는 폭 보정 대상이 아님
                    if (!type.equals("note") && !type.equals("ref")) {
                        superscriptStars(p);
                        track(p, PREFIX.get(type).length() + (type.equals("num") ? num.length() + 1 : 0));
                    }
                    checkLines(p, block, text);
                    if (type.equals("num") && it.containsKey("children")) keepNext.add(p);
                    out.add(p);
                    break;
                }
                case "table":
                    out.add(table(it));
                    break;
                case "box":
                    out.add(box(it));
                    break;
                case "figure":
                    out.addAll(figure(it));
                    break;
                default:
                    throw new IllegalArgumentException("알 수 없는 항목 type '" + type + "'");
            }
            prev = type;
            if (it.containsKey("children")) prev = items(out, list(it, "children"), type);
        }
        return prev;
    }

    /** 템플릿 안내 주석의 간격 규칙: ㅇ 앞 8pt, - 앞 6pt, 가운뎃점·각주·참고 앞 4pt. 참고(※) 바로 뒤 표는 간격 없음. */
    static String gapBefore(String prev, String type) {
        switch (type) {
            case "o": case "num": case "box": case "figure": return "GAP8";
            case "dash": return "GAP6";
            case "dot": case "note": case "ref": return "GAP4";
            case "table": return prev.equals("ref") ? null : "GAP8";
            default: return null;
        }
    }

    Paragraph table(Map<String, Object> it) throws Exception {
        int style = (int) num(it, "style", 1);
        Paragraph p = c.proto(style == 2 ? "TABLE2" : "TABLE1");
        List<List<String>> grid = new ArrayList<>();
        for (Object o : list(it, "rows")) {
            List<String> row = new ArrayList<>();
            @SuppressWarnings("unchecked") List<Object> r = (List<Object>) o;
            for (Object v : r) row.add(v == null ? "" : v instanceof Double && ((Double) v) % 1 == 0
                    ? String.valueOf(((Double) v).longValue()) : String.valueOf(v));
            grid.add(row);
        }
        double[] ratios = null;
        if (it.get("widths") instanceof List) {
            List<Object> w = list(it, "widths");
            ratios = new double[w.size()];
            for (int i = 0; i < w.size(); i++) ratios[i] = ((Number) w.get(i)).doubleValue();
        }
        String[] align = null;
        if (it.get("align") instanceof List) {
            List<Object> a = list(it, "align");
            align = new String[a.size()];
            for (int i = 0; i < a.size(); i++) align[i] = String.valueOf(a.get(i));
        }
        ControlTable t = firstTable(p);
        tables.build(t, grid, (int) num(it, "header_rows", 1), ratios, align, tableMaxWidth);
        for (Row r : t.getRowList())
            for (Cell cell : r.getCellList())
                for (Paragraph cp : cell.getParagraphList()) bold(cp);
        fitRows(t);
        return p;
    }

    Paragraph box(Map<String, Object> it) throws Exception {
        Paragraph p = c.proto("BOX");
        Cell bc = firstTable(p).getRowList().get(0).getCellList().get(0);
        List<Paragraph> proto = new ArrayList<>();
        for (Paragraph bp : bc.getParagraphList()) proto.add(bp);
        List<Paragraph> ps = new ArrayList<>();
        ps.add(fill(proto.get(c.num("box.TITLE")).clone(), vals("TITLE", str(it, "title", "주요 내용"))));
        ps.add(proto.get(c.num("box.TITLE_GAP")).clone());
        boxItems(ps, proto, list(it, "items"), true);
        Composer.setCellParagraphs(bc, ps);
        return p;
    }

    void boxItems(List<Paragraph> ps, List<Paragraph> proto, List<Object> items, boolean first) throws Exception {
        for (Object o : items) {
            @SuppressWarnings("unchecked") Map<String, Object> it = (Map<String, Object>) o;
            String type = str(it, "type", "o");
            String key = type.equals("dash") ? "box.DASH" : type.equals("ref") ? "box.REF" : "box.O";
            if (!first) ps.add(proto.get(c.num(type.equals("dash") ? "box.DASH_GAP" : "box.GAP")).clone());
            first = false;
            String text = clean(str(it, "text", ""));
            String label = str(it, "label", "");
            if (!label.isEmpty()) text = "**(" + label + ")** " + text;
            Paragraph bp = superscriptStars(bold(fill(proto.get(c.num(key)).clone(), vals("TEXT", text))));
            // 박스 원형 문단은 내어쓰기가 없어, 머리 기호("ㅇ ", "  - ", "※ ") 폭만큼 내어쓰기한 변형을 쓴다
            int hang = key.equals("box.DASH") ? -5700 : -4400;
            int base = bp.getHeader().getParaShapeId();
            String k = base + "/" + hang;
            if (!boxPs.containsKey(k)) boxPs.put(k, addParaShape(c.hwp, base, 0, hang));
            bp.getHeader().setParaShapeId(boxPs.get(k));
            ps.add(bp);
            if (it.containsKey("children")) boxItems(ps, proto, list(it, "children"), false);
        }
    }

    // ================================================================= 굵게 표기

    /** 구간 텍스트의 **…**를 굵은 글자모양 구간으로 바꾼다. */
    Paragraph bold(Paragraph p) throws Exception {
        List<Run> in = getRuns(p), outRuns = new ArrayList<>();
        boolean any = false;
        for (Run r : in) if (r.text.contains("**")) any = true;
        if (!any) return p;
        boolean on = false;
        for (Run r : in) {
            String[] parts = r.text.split("\\*\\*", -1);
            for (int k = 0; k < parts.length; k++) {
                if (k > 0) on = !on;
                if (!parts[k].isEmpty()) outRuns.add(new Run(parts[k], on ? boldOf(r.cs) : r.cs));
            }
        }
        return setRuns(p, outRuns);
    }

    int boldOf(int cs) {
        CharShape s = c.hwp.getDocInfo().getCharShapeList().get(cs);
        if (s.getProperty().isBold()) return cs;
        return ensureCharShape(c.hwp, csCache, cs, s.getBaseSize() / 100.0, true);
    }

    // ================================================================= 쪽 계산(목차 쪽번호, 쪽 넘침 경고)

    /** 쪽마다 글 줄 수와 쓴 높이(pt), 그리고 쪽 나누기로 시작하는 쪽. paginate가 채운다. */
    final Map<Integer, Integer> pageLines = new HashMap<>();
    final Map<Integer, Double> pageUsed = new HashMap<>();
    final java.util.Set<Integer> pageBreaks = new java.util.TreeSet<>();
    final Map<Integer, Boolean> pageHasObject = new HashMap<>();
    /** 쪽 넘침 경고로 볼 마지막 쪽 줄 수 상한. */
    static final int SPILL_LINES = 3;

    double bodyHeight() {
        for (Control ct : c.proto("SECTION").getControlList())
            if (ct instanceof ControlSectionDefine) {
                PageDef pd = ((ControlSectionDefine) ct).getPageDef();
                return (pd.getPaperHeight() - pd.getTopMargin() - pd.getBottomMargin() - pd.getHeaderMargin() - pd.getFooterMargin()) / 100.0;
            }
        return 728;
    }

    /**
     * 문단마다 몇 쪽(1부터)에 오는지 추정한다. 글 문단은 줄 단위로 쪽을 넘기고, 표·그림은 통째로 넘긴다.
     * 높이는 문단모양의 줄 간격과 위·아래 간격, 표는 칸 내용으로 계산한다(뷰어 캡처로 확인, 계획 문서 14장).
     */
    Map<Paragraph, Integer> paginate(List<Paragraph> body) {
        Map<Paragraph, Integer> out = new java.util.IdentityHashMap<>();
        pageLines.clear(); pageUsed.clear(); pageBreaks.clear(); pageHasObject.clear();
        double H = bodyHeight(), y = 0;
        int page = 0;
        for (int idx = 0; idx < body.size(); idx++) {
            Paragraph p = body.get(idx);
            if (p.getHeader().getDivideSort().isDividePage() || page == 0) { page++; y = 0; pageBreaks.add(page); }
            double lh = lineHeight(p), top = paraSpace(p, true), bottom = paraSpace(p, false);
            if (keepNext.contains(p) && y > 0) {
                // 제목 + 뒤따르는 빈 간격 + 다음 내용 첫 줄이 남은 공간에 안 들어가면 제목째 다음 쪽으로
                double need = isObject(p) ? objectHeight(p) : top + lh * lines(p).size() + bottom;
                int k = idx + 1;
                while (k < body.size() && body.get(k).getText() == null && body.get(k).getControlList() == null) need += lineHeight(body.get(k++));
                if (k < body.size()) need += isObject(body.get(k)) ? objectHeight(body.get(k)) : lineHeight(body.get(k));
                if (y + need > H) { page++; y = 0; }
            }
            if (isObject(p)) {
                double h = objectHeight(p);
                if (y > 0 && y + h > H) { page++; y = 0; }
                out.put(p, page);
                if (DEBUG) System.out.printf("DEBUG page %d y=%6.1f h=%6.1f [표·그림] %s%n", page, y, h, abbrev(firstText(p)));
                pageHasObject.put(page, true);
                y += h;
                pageUsed.put(page, y);
                continue;
            }
            boolean text = p.getText() != null && plainText(p).trim().length() > 0;
            if (!text && y + lh > H + 0.5) {
                // 쪽 끝에 안 들어가는 빈 간격 문단은 다음 쪽 맨 위를 차지하지 않는다(메모 1차 렌더 캡처로 확인)
                out.put(p, page);
                y = H;
                continue;
            }
            int n = text ? lines(p).size() : 1;
            if (y > 0) y += top;
            if (y > 0 && y + lh > H + 0.5) { page++; y = 0; }   // 첫 줄이 안 들어가면 문단째 다음 쪽(목차 쪽번호용)
            out.put(p, page);
            if (DEBUG && text) System.out.printf("DEBUG page %d y=%6.1f h=%6.1f %s%n", page, y, lh * n, abbrev(plainText(p).trim()));
            for (int k = 0; k < n; k++) {
                if (y + lh > H + 0.5) { page++; y = 0; }
                y += lh;
                if (text) pageLines.merge(page, 1, Integer::sum);
                pageUsed.put(page, y);
            }
            y += bottom;
        }
        return out;
    }

    static int maxPage(Map<Paragraph, Integer> pages) {
        int m = 1;
        for (int v : pages.values()) m = Math.max(m, v);
        return m;
    }

    /**
     * 쪽 나누기로 시작한 묶음(본문, 첨부·참고, 장)마다 마지막 쪽에 글이 몇 줄만 넘어갔으면 경고한다.
     * 넘어간 높이만큼 줄이면 앞 쪽에 들어간다.
     */
    void spillWarnings() {
        List<Integer> starts = new ArrayList<>(pageBreaks);
        int last = pageUsed.keySet().stream().max(Integer::compare).orElse(1);
        for (int i = 0; i < starts.size(); i++) {
            int first = starts.get(i), end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : last;
            if (end <= first || pageHasObject.getOrDefault(end, false)) continue;
            int n = pageLines.getOrDefault(end, 0);
            if (n >= 1 && n <= SPILL_LINES)
                warnings.add(String.format("쪽 넘침: %d쪽에 글 %d줄만 넘어감(약 %.0fpt) — 그만큼 줄이면 %d쪽에 들어감",
                        end, n, pageUsed.getOrDefault(end, 0.0), end - 1));
        }
    }

    boolean isObject(Paragraph p) { return firstTable(p) != null || picture(p) != null; }

    static ControlPicture picture(Paragraph p) {
        if (p.getControlList() == null) return null;
        for (Control ct : p.getControlList()) if (ct instanceof ControlPicture) return (ControlPicture) ct;
        return null;
    }

    String firstText(Paragraph p) {
        ControlTable t = firstTable(p);
        if (t == null) return "";
        for (Row r : t.getRowList())
            for (Cell cl : r.getCellList())
                for (Paragraph cp : cl.getParagraphList()) {
                    String s = plainText(cp).trim();
                    if (!s.isEmpty()) return s;
                }
        return "";
    }

    /** 문단의 가장 큰 글자 크기(pt). 빈 간격 문단도 제 글자 크기 그대로(4~12pt). */
    double fontSize(Paragraph p) {
        double size = 0;
        if (p.getCharShape() != null)
            for (kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair pr : p.getCharShape().getPositonShapeIdPairList())
                size = Math.max(size, csOf((int) pr.getShapeId()).getBaseSize() / 100.0);
        return size == 0 ? 10 : size;
    }

    /** 줄 높이: 문단의 가장 큰 글자 크기에 문단모양 줄 간격을 적용한 값. */
    double lineHeight(Paragraph p) {
        double size = fontSize(p);
        ParaShape ps = c.hwp.getDocInfo().getParaShapeList().get(p.getHeader().getParaShapeId());
        double v = ps.getLineSpace();
        switch (ps.getProperty1().getLineSpaceSort()) {
            case FixedValue: return v / 100.0;                  // 고정값(HWPUNIT)
            case OnlyMargin: return size + v / 100.0;           // 여백만 지정
            case AtLeast: return Math.max(size * 1.6, v / 100.0); // 최소
            default: return size * v / 100.0;                  // 글자에 따라(%)
        }
    }

    /** 문단 위(true)·아래(false) 간격(pt). 파일 저장값은 HWPUNIT×2. */
    double paraSpace(Paragraph p, boolean top) {
        ParaShape ps = c.hwp.getDocInfo().getParaShapeList().get(p.getHeader().getParaShapeId());
        return (top ? ps.getTopParaSpace() : ps.getBottomParaSpace()) / 200.0;
    }

    /** 표·그림 문단 높이: 표는 줄마다 저장된 칸 높이와 칸 내용 높이 중 큰 값의 합 + 바깥 여백 + 아래 여분. */
    double objectHeight(Paragraph p) {
        ControlTable t = firstTable(p);
        if (t != null)
            return tableHeight(t) + (t.getHeader().getOutterMarginTop() + t.getHeader().getOutterMarginBottom()) / 100.0 + objectExtra(p);
        ControlPicture pic = picture(p);
        return pic.getHeader().getHeight() / 100.0 + 4;
    }

    /**
     * 글자처럼 놓인 표를 담은 문단이 표 아래에 더 차지하는 높이 = 그 문단 글자 크기 × 0.35.
     * 캡처 6쪽(요약·상세, 표로 끝나는 쪽 포함)의 마지막 줄 위치를 ±5pt 안으로 맞춘 값(고정 2pt일 때는 -9pt까지 어긋남).
     */
    double objectExtra(Paragraph p) {
        return 0.35 * fontSize(p);
    }

    double tableHeight(ControlTable t) {
        double total = 0;
        for (Row r : t.getRowList()) {
            double row = 0;
            for (Cell cl : r.getCellList()) {
                double stored = cl.getListHeader().getHeight() / 100.0;
                if (cl.getListHeader().getRowSpan() > 1) continue;   // 여러 줄 병합 칸은 저장값을 다른 줄에 나눠 맡긴다
                row = Math.max(row, Math.max(stored, cellContentHeight(t, cl)));
            }
            if (row == 0)   // 병합 칸만 있는 줄: 저장값 그대로
                for (Cell cl : r.getCellList()) row = Math.max(row, cl.getListHeader().getHeight() / 100.0 / cl.getListHeader().getRowSpan());
            total += row;
        }
        return total;
    }

    /**
     * 칸 안 문단 높이 합 + 칸 위·아래 안쪽 여백. 칸 폭으로 줄 수를 센다.
     * 칸의 마지막 줄 아래에는 줄 간격 여분(글자 크기를 넘는 부분)이 붙지 않는다
     * (제목 박스: 칸 저장 높이 합 62.3pt인데 표 높이 68.3pt = 얇은 띠 줄이 이 규칙대로 2.8→5.8pt로 늘어난 값).
     */
    double cellContentHeight(ControlTable t, Cell cl) {
        long tm = cl.getListHeader().getTopMargin(), bm = cl.getListHeader().getBottomMargin();
        long lm = cl.getListHeader().getLeftMargin(), rm = cl.getListHeader().getRightMargin();
        if (tm == 0) tm = t.getTable().getTopInnerMargin();
        if (bm == 0) bm = t.getTable().getBottomInnerMargin();
        if (lm == 0) lm = t.getTable().getLeftInnerMargin();
        if (rm == 0) rm = t.getTable().getRightInnerMargin();
        double w = (cl.getListHeader().getWidth() - lm - rm) / 100.0;
        double h = (tm + bm) / 100.0, lastExtra = 0;
        for (Paragraph cp : cl.getParagraphList()) {
            if (isObject(cp)) { h += objectHeight(cp); lastExtra = 0; continue; }
            boolean text = cp.getText() != null && plainText(cp).trim().length() > 0;
            int n = text ? Layout.lines(segs(getRuns(cp)), w, w - hangOf(cp), byWord(cp)).size() : 1;
            double lh = lineHeight(cp);
            h += paraSpace(cp, true) + lh * n + paraSpace(cp, false);
            lastExtra = Math.max(0, lh - fontSize(cp));
        }
        return h - lastExtra;
    }

    // ================================================================= 길이 점검

    double bodyWidth() {
        Paragraph sec = c.proto("SECTION");
        for (Control ct : sec.getControlList())
            if (ct instanceof ControlSectionDefine) {
                PageDef pd = ((ControlSectionDefine) ct).getPageDef();
                return (pd.getPaperWidth() - pd.getLeftMargin() - pd.getRightMargin() - pd.getGutterMargin()) / 100.0;
            }
        return 481.9;
    }

    CharShape csOf(int id) { return c.hwp.getDocInfo().getCharShapeList().get(id); }

    List<Layout.Seg> segs(List<Run> runs) {
        List<Layout.Seg> l = new ArrayList<>();
        for (Run r : runs) {
            CharShape s = csOf(r.cs);
            l.add(new Layout.Seg(r.text, s.getBaseSize() / 100.0, s.getProperty().isBold(), s.getCharSpaces().getHangul()));
        }
        return l;
    }

    double hangOf(Paragraph p) {
        return Math.abs(c.hwp.getDocInfo().getParaShapeList().get(p.getHeader().getParaShapeId()).getIndent()) / 200.0;
    }

    boolean byWord(Paragraph p) {
        return c.hwp.getDocInfo().getParaShapeList().get(p.getHeader().getParaShapeId()).getProperty1().getLineDivideForHangul()
                == kr.dogfoot.hwplib.object.docinfo.parashape.LineDivideForHangul.ByWord;
    }

    List<String> lines(Paragraph p) {
        return Layout.lines(segs(getRuns(p)), bodyWidthPt, bodyWidthPt - hangOf(p), byWord(p));
    }

    /**
     * 줄 끝 걸침 방지: 마지막 줄이 몇 글자뿐이면 머리 기호 뒤 글자들의 자간을 -1~-10%로 줄여 한 줄을 줄인다.
     * (머리 기호 부분은 내어쓰기 위치가 어긋나지 않도록 그대로 둔다)
     */
    void track(Paragraph p, int prefixLen) throws Exception {
        List<Run> runs = getRuns(p);
        if (Layout.layout(segs(runs), bodyWidthPt, bodyWidthPt - hangOf(p), byWord(p)).size() < 2) return;
        // 계산 오차(약 1~3%)가 있으므로 줄 폭을 ±safety 바꿔 본 세 경우 중 가장 나쁜 결과로 판단한다.
        // (폭을 줄이기만 하면 끊길 글자가 다음 줄로 밀려 "해결된 것처럼" 보이는 착시가 생김)
        double safety = 6;
        int best = 0;
        double bestScore = Double.MAX_VALUE;
        for (int spc = 0; spc >= -12; spc--) {
            List<Layout.Seg> sg = segsSpaced(runs, prefixLen, spc);
            double nominal = badness(Layout.layout(sg, bodyWidthPt, bodyWidthPt - hangOf(p), byWord(p)));
            double worst = nominal;
            for (double d : new double[]{-safety, safety})
                worst = Math.max(worst, badness(Layout.layout(sg, bodyWidthPt + d, bodyWidthPt - hangOf(p) + d, byWord(p))));
            // 실제 폭 기준 결과를 우선하고, 오차 범위의 위험은 절반만 반영(멀쩡한 줄을 괜히 줄이지 않도록)
            double score = nominal + 0.5 * (worst - nominal) - spc * 2.5;
            if (score < bestScore - 0.001) { bestScore = score; best = spc; }
        }
        if (DEBUG) {
            Layout.Result r0 = Layout.layout(segs(runs), bodyWidthPt, bodyWidthPt - hangOf(p), byWord(p));
            System.out.printf("DEBUG spc=%d lines=%d fill=%.2f last='%s' | %s%n", best, r0.size(), r0.minFill(), r0.last().trim(), r0.lines);
        }
        if (best != 0) setRuns(p, spaced(runs, prefixLen, best));
    }

    static final boolean DEBUG = System.getenv("HWP_DEBUG") != null;

    /**
     * 줄 나눔의 나쁨 점수: 줄 수가 가장 중요하고, 마지막 줄 한두 글자 걸침과 단어 중간 끊김에 벌점을 준다.
     * (track에서 자간 1%마다 2.5점을 더해 꼭 필요한 만큼만 줄인다)
     */
    static double badness(Layout.Result r) {
        double s = r.size() * 100;
        if (r.size() > 1) {
            String last = r.last().replace(" ", "");
            if (last.length() <= 4) s += 40;
            else if (last.length() <= 6) s += 15;
            s += r.midWordBreaks() * 30;
        }
        return s;
    }

    /** spaced()와 같은 결과의 계산용 구간(글자모양을 실제로 만들지 않음). */
    List<Layout.Seg> segsSpaced(List<Run> runs, int prefixLen, int spc) {
        List<Layout.Seg> l = new ArrayList<>();
        int pos = 0;
        for (Run r : runs) {
            CharShape s = csOf(r.cs);
            double size = s.getBaseSize() / 100.0;
            boolean b = s.getProperty().isBold();
            int base = s.getCharSpaces().getHangul();
            int cut = Math.max(0, Math.min(r.text.length(), prefixLen - pos));
            if (cut > 0) l.add(new Layout.Seg(r.text.substring(0, cut), size, b, base));
            if (cut < r.text.length()) l.add(new Layout.Seg(r.text.substring(cut), size, b, base + spc));
            pos += r.text.length();
        }
        return l;
    }

    /** prefixLen 글자 이후 구간의 글자모양을 자간 spc 변형으로 바꾼 구간 목록. */
    List<Run> spaced(List<Run> runs, int prefixLen, int spc) {
        List<Run> out = new ArrayList<>();
        int pos = 0;
        for (Run r : runs) {
            int cut = Math.max(0, Math.min(r.text.length(), prefixLen - pos));
            if (cut > 0) out.add(new Run(r.text.substring(0, cut), r.cs));
            if (cut < r.text.length()) {
                final int sp = csOf(r.cs).getCharSpaces().getHangul() + spc;
                int v = ensureVariant(c.hwp, csCache, r.cs, "spc" + sp, s -> s.getCharSpaces().setForAll((byte) sp));
                out.add(new Run(r.text.substring(cut), v));
            }
            pos += r.text.length();
        }
        return out;
    }

    /** 본문에서 글자 바로 뒤에 붙은 *(각주 표시)를 위첨자로 바꾼다. */
    Paragraph superscriptStars(Paragraph p) throws Exception {
        List<Run> in = getRuns(p), out = new ArrayList<>();
        boolean any = false;
        for (Run r : in) {
            StringBuilder sb = new StringBuilder();
            char prev = ' ';
            for (char ch : r.text.toCharArray()) {
                if (ch == '*' && prev != ' ' && prev != '*') {
                    if (sb.length() > 0) { out.add(new Run(sb.toString(), r.cs)); sb.setLength(0); }
                    out.add(new Run("*", ensureVariant(c.hwp, csCache, r.cs, "sup", s -> s.getProperty().setSuperScript(true))));
                    any = true;
                } else sb.append(ch);
                prev = ch;
            }
            if (sb.length() > 0) out.add(new Run(sb.toString(), r.cs));
        }
        return any ? setRuns(p, out) : p;
    }

    /**
     * 표 칸 글자 줄 수만큼 행 높이를 늘린다(뷰어는 저장된 칸 높이를 그대로 쓰므로 미리 계산).
     * 칸 필요 높이 = 원형 한 줄 높이 + (줄 수 - 1) × 줄 간격(글자 크기 × 160%).
     * 한 행짜리 칸으로 행 높이를 정한 뒤, 여러 행에 걸친 병합 칸이 모자라면 그 칸의 마지막 행을 늘린다.
     */
    void fitRows(ControlTable t) {
        int R = t.getTable().getRowCount();
        long[] rowH = new long[R];
        List<Cell> cells = new ArrayList<>();
        Map<Cell, Long> need = new HashMap<>();
        for (Row r : t.getRowList())
            for (Cell cell : r.getCellList()) {
                cells.add(cell);
                long lm = cell.getListHeader().getLeftMargin(), rm = cell.getListHeader().getRightMargin();
                if (lm == 0) lm = t.getTable().getLeftInnerMargin();
                if (rm == 0) rm = t.getTable().getRightInnerMargin();
                double avail = (cell.getListHeader().getWidth() - lm - rm) / 100.0;
                int lines = 0;
                double size = 13;
                for (Paragraph cp : cell.getParagraphList()) {
                    List<Run> rs = getRuns(cp);
                    if (rs.isEmpty()) { lines++; continue; }
                    size = csOf(rs.get(0).cs).getBaseSize() / 100.0;
                    lines += Layout.lines(segs(rs), avail, avail, true).size();
                }
                long base = cell.getListHeader().getHeight();
                need.put(cell, Math.round(base + Math.max(0, lines - 1) * size * 160));
                int ri = cell.getListHeader().getRowIndex();
                if (cell.getListHeader().getRowSpan() == 1) rowH[ri] = Math.max(rowH[ri], need.get(cell));
                else rowH[ri] = Math.max(rowH[ri], base / cell.getListHeader().getRowSpan());
            }
        for (Cell cell : cells) {
            int r0 = cell.getListHeader().getRowIndex(), n = cell.getListHeader().getRowSpan();
            if (n == 1) continue;
            long sum = 0;
            for (int i = r0; i < r0 + n; i++) sum += rowH[i];
            if (need.get(cell) > sum) rowH[r0 + n - 1] += need.get(cell) - sum;
        }
        long total = 0;
        for (long h : rowH) total += h;
        for (Cell cell : cells) {
            long h = 0;
            for (int i = cell.getListHeader().getRowIndex(); i < cell.getListHeader().getRowIndex() + cell.getListHeader().getRowSpan(); i++) h += rowH[i];
            cell.getListHeader().setHeight(h);
        }
        t.getHeader().setHeight(total);
    }

    void checkLines(Paragraph p, String block, String text) {
        List<String> ls = lines(p);
        int n = ls.size();
        if (n > MAX_LINES) warnings.add(String.format("3줄 초과(예상 %d줄) [%s] %s", n, block, abbrev(text)));
        else if (n > 1 && !block.equals("NOTE") && !block.equals("REF")) {
            // 자간 조정으로도 못 고친 한두 글자 걸침은 문장을 손봐야 한다.
            // 각주·참고(함초롬돋움)는 줄 계산 보정 대상이 아니라 오탐이 나므로 제외(상세 예시 ※ 줄로 확인)
            int last = ls.get(n - 1).replace(" ", "").length();
            if (last <= 2) warnings.add(String.format("마지막 줄 %d글자만 남음 [%s] %s — 단어를 조금 줄이거나 늘릴 것", last, block, abbrev(text)));
        }
    }

    /**
     * 한 줄이어야 하는 칸(날짜·부서, 결재라인)이 넘치면 칸 글자 자간을 -1~-15%까지 줄여 한 줄에 맞춘다.
     * 그래도 넘치면 경고한다(표기는 바꾸지 않는다 — "2026. 4. 23" 형식 유지).
     */
    void fitCell(Cell cell, String what) throws Exception {
        Paragraph p = cell.getParagraphList().getParagraph(0);
        List<Run> runs = getRuns(p);
        if (runs.isEmpty()) return;
        double avail = (cell.getListHeader().getWidth() - cell.getListHeader().getLeftMargin() - cell.getListHeader().getRightMargin()) / 100.0;
        if (Layout.lines(segs(runs), avail, avail, true).size() <= 1) return;
        for (int spc = -1; spc >= -15; spc--) {
            if (Layout.lines(segsSpaced(runs, 0, spc), avail - 3, avail - 3, true).size() <= 1) {
                setRuns(p, spaced(runs, 0, spc));
                return;
            }
        }
        StringBuilder t = new StringBuilder();
        for (Run r : runs) t.append(r.text);
        warnings.add(String.format("[%s] 칸 너비(%.0fpt)를 넘어 두 줄이 됨(자간 -15%%로도 부족): %s", what, avail, abbrev(t.toString())));
    }

    void checkCellFits(Cell cell, String text, String what) {
        List<Run> rs = getRuns(cell.getParagraphList().getParagraph(0));
        int cs = rs.isEmpty() ? 0 : rs.get(0).cs;
        List<Run> probe = new ArrayList<>();
        probe.add(new Run(text, cs));
        double avail = (cell.getListHeader().getWidth() - cell.getListHeader().getLeftMargin() - cell.getListHeader().getRightMargin()) / 100.0;
        if (Layout.lines(segs(probe), avail, avail, true).size() > 1)
            warnings.add(String.format("[%s] 칸 너비(%.0fpt)를 넘어 두 줄이 됨: %s", what, avail, abbrev(text)));
    }

    // ================================================================= 유틸

    /** 사용자가 넣은 머리 기호(ㅇ, -, · 등)는 원형이 붙이므로 지운다. */
    static String clean(String t) {
        return t.replaceFirst("^\\s*(ㅇ|○|o|-|·|•|\\*|※|□|■)\\s+", "").trim();
    }

    static String abbrev(String t) {
        String s = t.replace("**", "");
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    static double num(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }
}
