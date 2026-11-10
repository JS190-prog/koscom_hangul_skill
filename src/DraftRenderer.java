import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.GsoHeaderProperty;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.RelativeArrange;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.VertRelTo;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.parashape.Alignment;
import kr.dogfoot.hwplib.object.docinfo.parashape.LineDivideForHangul;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 기안문(type: draft). 본문을 만든다: 관련 근거 → 요청 문장 → "- 아 래 -" → 번호 항목(1. 2. …).
 * 머리(수신자·제목)는 recipient · title을 주면 채우고, 없으면 양식의 「시스템 자동생성 영역」 그대로 둔다.
 * 첨부 목록은 attachments를 주면 「첨부 1. … 1부. 끝.」으로 쓰고, 없으면 본문 마지막 줄 끝에 「끝.」을 붙인다.
 * 결재 · 협조 · 시행 정보는 전자결재 시스템이 만드는 영역이라 양식 글 그대로 두고, 그 표는 쪽 아래에 붙인다(작성사례와 같음).
 * 빈 줄(맑은 고딕 12pt)은 양식대로 번호 항목 앞, 항목 제목과 첫 내용 사이, ㅇ 항목 사이에만 넣는다.
 */
public class DraftRenderer extends Renderer {
    static final String ORDER = "가나다라마바사아자차카타파하";
    static final List<String> TYPES = Arrays.asList("o", "dash", "dot", "note", "ref");

    /**
     * 맑은 고딕 12pt는 Layout(함초롬바탕 기준)보다 약 2% 넓다. 기안문 견본을 한글에서 연 화면의 첫 줄 끝 6곳과 맞춘 값
     * (2026-11-09: 1.015~1.025에서 한글 위주 줄은 모두 일치, 숫자가 많은 줄은 한 글자 일찍 끊는다고 예측).
     */
    static final double MALGUN = 1.02;

    DraftRenderer(String templates) throws Exception { super(templates, "draft"); }

    @Override double bodyWidth() { return super.bodyWidth() / MALGUN; }

    void draft(Map<String, Object> r) throws Exception {
        List<Paragraph> out = c.out;
        header(str(r, "recipient", ""), str(r, "title", ""));
        out.add(c.proto("HEAD"));
        List<Object> basis = r.get("basis") instanceof String ? new ArrayList<>(Arrays.asList(r.get("basis"))) : list(r, "basis");
        if (basis.size() > ORDER.length()) throw new IllegalArgumentException("관련 근거는 " + ORDER.length() + "건까지");
        int n = 1;
        if (basis.size() == 1) out.add(body("TOP", n++ + ". 관련 근거 : " + basis.get(0), 3));
        else if (basis.size() > 1) {
            out.add(line("TOP", n++ + ". 관련 근거"));
            for (int i = 0; i < basis.size(); i++) out.add(body("BASIS", ORDER.charAt(i) + ". " + basis.get(i), 5));
        }
        if (!basis.isEmpty()) out.add(c.proto("GAP"));
        String req = str(r, "request", "");
        if (req.isEmpty())
            req = (basis.isEmpty() ? "" : "위 근거에 의거, ") + "아래와 같이 " + obj(str(r, "purpose", "○○○○"))
                    + " 추진하고자 하오니 결재하여 주시기 바랍니다.";
        out.add(body("TOP", n + ". " + req, 3));
        out.add(c.proto("GAP"));
        out.add(c.proto("BELOW"));
        boolean paren = str(r, "label_style", "colon").equals("paren");
        List<Object> secs = list(r, "sections");
        for (int i = 0; i < secs.size(); i++) {
            @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) secs.get(i);
            out.add(c.proto("GAP"));
            String inline = str(s, "text", "");
            Paragraph h = line("H1", (i + 1) + ". " + str(s, "heading", "") + (inline.isEmpty() ? "" : " : " + inline));
            keepNext.add(h);
            out.add(h);
            draftItems(out, list(s, "items"), paren, true);
        }
        List<Object> atts = list(r, "attachments");
        if (atts.isEmpty()) endMark(out);       // 첨부가 없으면 본문 끝에 「끝.」
        out.add(c.proto("GAP"));
        List<Paragraph> footer = c.protoRange("FOOTER");
        Paragraph proto = footer.remove(0);     // 꼬리 첫 줄 「첨부 //. 끝.」 안내 — 첨부 목록으로 바꾸거나(둘째 줄부터 번호를 첫 항목 번호에 맞춤) 뺀다
        for (int i = 0; i < atts.size(); i++) {
            String head = atts.size() == 1 ? "첨부 " : i == 0 ? "첨부 1. " : "     " + (i + 1) + ". ";
            String end = i == atts.size() - 1 ? ". 끝." : "";
            Paragraph p = setText(proto.clone(), head + String.valueOf(atts.get(i)).trim() + end, bodyCs());
            // 두 줄로 넘어가면 머리(「첨부 1. 」 · 「     2. 」) 다음 글자에 맞춘다. 내어쓰기 값은 작성사례 문단모양(ps20 · ps19) 그대로
            p.getHeader().setParaShapeId(attPs(p.getHeader().getParaShapeId(), atts.size() == 1 ? -5800 : i == 0 ? -9048 : -7632));
            out.add(p);
        }
        anchorTail(footer);
        out.addAll(footer);
    }

    /** 기안문 양식의 합계 행 테두리 · 바탕(가는 실선, 회색 f2f2f2). draft_base.hwp 문서 정보에 양식 그대로 들어 있다. */
    static final int TOTAL_BF = 22;

    /** 표 마지막 행이 「합계」면 양식처럼 회색 바탕에 「합계」를 머리행 글자모양(굵게)으로 쓴다. 앞 칸 병합은 표의 "<"로 준다. */
    static void totalRow(ControlTable t) throws Exception {
        List<Row> rows = t.getRowList();
        if (rows.size() < 3) return;
        Row last = rows.get(rows.size() - 1);
        Paragraph label = last.getCellList().get(0).getParagraphList().getParagraph(0);
        if (!plainText(label).replaceAll("\\s", "").matches("합계|총계")) return;
        int headCs = (int) rows.get(0).getCellList().get(0).getParagraphList().getParagraph(0)
                .getCharShape().getPositonShapeIdPairList().get(0).getShapeId();
        for (Cell cell : last.getCellList()) cell.getListHeader().setBorderFillId(TOTAL_BF);
        setText(label, plainText(label).trim(), headCs);
    }

    /**
     * 첨부 목록 줄의 내어쓰기 문단모양. 작성사례 첨부 첫 줄(ps20)처럼 왼쪽 정렬 · 한글 어절 단위 줄 나눔이다
     * (글자 단위면 「… (2026. 4. 10.) 1부」의 「1부」가 「1 / 부」로 갈린다. 왼쪽 정렬이라 어절로 끊어도 빈칸이 벌어지지 않는다).
     */
    int attPs(int base, int hang) {
        return boxPs.computeIfAbsent("att/" + base + "/" + hang, k -> {
            int id = addParaShape(c.hwp, base, 0, hang);
            c.hwp.getDocInfo().getParaShapeList().get(id).getProperty1().setAlignment(Alignment.Left);
            c.hwp.getDocInfo().getParaShapeList().get(id).getProperty1().setLineDivideForHangul(LineDivideForHangul.ByWord);
            return id;
        });
    }

    /** 본문 마지막 줄 끝에 「  끝.」을 붙인다(마지막이 표면 다음 줄에 「끝.」). */
    void endMark(List<Paragraph> out) throws Exception {
        Paragraph last = out.get(out.size() - 1);
        if (firstTable(last) != null) {
            out.add(line("TOP", "끝."));
            return;
        }
        List<Run> runs = getRuns(last);
        runs.add(new Run("  끝.", runs.isEmpty() ? bodyCs() : runs.get(runs.size() - 1).cs));
        setRuns(last, runs);
    }

    /**
     * 결재 · 협조 · 시행 칸(꼬리 표)을 작성사례처럼 쪽 아래에 붙인다: 표를 쪽 기준 아래 정렬로 두고, 표 앞 빈 줄은 한 줄만 남긴다.
     * 양식은 표 앞 빈 줄 27개로 표를 내려 두어, 본문이 한 쪽을 넘으면 결재 칸만 다음 쪽으로 넘어갔다.
     */
    static void anchorTail(List<Paragraph> footer) {
        int t = -1;
        for (int i = 0; i < footer.size() && t < 0; i++)
            if (footer.get(i).getControlList() != null)
                for (Control ct : footer.get(i).getControlList())
                    if (ct instanceof ControlTable && ((ControlTable) ct).getRowList().size() >= 10) {
                        GsoHeaderProperty pr = ((ControlTable) ct).getHeader().getProperty();
                        pr.setVertRelTo(VertRelTo.Page);
                        pr.setVertRelativeArrange(RelativeArrange.BottomOrRight);
                        t = i;
                    }
        if (t < 0) throw new IllegalStateException("기안문 양식에서 결재 칸 표를 찾지 못함");
        int b = t;
        while (b > 0 && plainText(footer.get(b - 1)).trim().isEmpty()
                && (footer.get(b - 1).getControlList() == null || footer.get(b - 1).getControlList().isEmpty())) b--;
        for (int i = t - 1; i > b; i--) footer.remove(i);
    }

    /** 본문 글자모양(관련 근거 · 요청 문장 줄과 같은 것). 머리 값 칸과 첨부 목록에 쓴다. */
    int bodyCs() {
        return (int) c.proto("TOP").getCharShape().getPositonShapeIdPairList().get(0).getShapeId();
    }

    /** 머리 표의 수신자 · 제목 값 칸을 채운다. 저장할 때 구역 문단의 컨트롤(머리 표)을 복사하므로 원형 문단의 표를 고친다. */
    void header(String recipient, String title) throws Exception {
        if (recipient.isEmpty() && title.isEmpty()) return;
        for (Control ct : c.base[c.index("SECTION")].getControlList()) {
            if (!(ct instanceof ControlTable) || ((ControlTable) ct).getRowList().size() != 2) continue;
            ControlTable t = (ControlTable) ct;
            if (!plainText(cell(t, 0, 0).getParagraphList().getParagraph(0)).startsWith("수신자")) continue;
            if (!recipient.isEmpty()) setText(cell(t, 0, 1).getParagraphList().getParagraph(0), recipient, bodyCs());
            if (!title.isEmpty()) setText(cell(t, 1, 1).getParagraphList().getParagraph(0), title, bodyCs());
            return;
        }
        throw new IllegalStateException("기안문 양식에서 수신자 · 제목 칸을 찾지 못함");
    }

    /** 라벨은 "라벨 : 내용"(기본) 또는 "(라벨) 내용"(label_style: paren, 라벨 굵게). 표는 앞뒤 빈 줄 없이 붙인다. */
    void draftItems(List<Paragraph> out, List<Object> items, boolean paren, boolean first) throws Exception {
        for (Object o : items) {
            @SuppressWarnings("unchecked") Map<String, Object> it = (Map<String, Object>) o;
            String type = str(it, "type", "o");
            if (type.equals("table")) {
                if (!str(it, "unit", "").isEmpty()) {
                    Paragraph u = line("UNIT", str(it, "unit", ""));
                    keepNext.add(u);            // 단위 줄만 쪽 끝에 남고 표가 다음 쪽으로 넘어가지 않게
                    out.add(u);
                }
                Paragraph tp = table(it);
                if (num(it, "style", 1) != 2) totalRow(firstTable(tp));
                out.add(tp);
            } else {
                if (!TYPES.contains(type))
                    throw new IllegalArgumentException("기안문에서 지원하지 않는 항목 type '" + type + "'(o, dash, dot, note, ref, table)");
                if (first || type.equals("o")) out.add(c.proto("GAP"));
                String text = clean(str(it, "text", "")), label = str(it, "label", "");
                if (!label.isEmpty()) text = paren ? "**(" + label + ")** " + text : label + " : " + text;
                String block = type.equals("o") ? "O" : type.toUpperCase();
                if (type.equals("note") || type.equals("ref")) out.add(line(block, text));   // 11pt 각주·참고는 폭 보정 대상이 아님
                else out.add(superscriptStars(body(block, text, PREFIX.get(type).length())));
            }
            first = false;
            if (it.containsKey("children")) draftItems(out, list(it, "children"), paren, false);
        }
    }

    Paragraph line(String block, String text) throws Exception {
        return bold(fill(c.proto(block), vals("TEXT", text)));
    }

    /** 본문 줄: 마지막 줄에 한두 글자만 넘어가면 머리 기호 뒤 자간을 줄이고, 그래도 남으면 경고한다. */
    Paragraph body(String block, String text, int prefixLen) throws Exception {
        Paragraph p = line(block, text);
        track(p, prefixLen);
        checkLines(p, block, text);
        return p;
    }

    /** 목적어 조사: 마지막 글자에 받침이 없으면 "를", 있거나 한글이 아니면 "을". (voice_tool.flatten_report와 같은 규칙) */
    static String obj(String w) {
        char ch = w.isEmpty() ? ' ' : w.charAt(w.length() - 1);
        return w + (ch >= 0xAC00 && ch <= 0xD7A3 && (ch - 0xAC00) % 28 == 0 ? "를" : "을");
    }
}
