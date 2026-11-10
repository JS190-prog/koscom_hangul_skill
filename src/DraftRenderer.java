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
 * 결재 · 협조 · 시행 정보는 전자결재 시스템이 만드는 영역이라 양식 글 그대로 두고(시행된 문서를 다시 만들 때만 issue로 시행 칸을 채움),
 * 그 표는 쪽 아래에 붙인다(작성사례와 같음).
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
        String req = str(r, "request", "");
        if (basis.isEmpty()) {
            // 관련 근거가 없으면 번호 없이 한 문장으로 시작한다(예: 본사 구내식당 에어컨 추가 설치를 아래와 같이 추진하고자 합니다.)
            if (req.isEmpty()) req = obj(str(r, "purpose", "○○○○")) + " 아래와 같이 추진하고자 합니다.";
            Paragraph p = line("TOP", req);
            p.getHeader().setParaShapeId(flatPs(p.getHeader().getParaShapeId()));
            track(p, 0);
            checkLines(p, "TOP", req);
            out.add(p);
        } else {
            out.add(c.proto("GAP"));
            if (req.isEmpty())
                req = "위 근거에 의거, 아래와 같이 " + obj(str(r, "purpose", "○○○○")) + " 추진하고자 하오니 결재하여 주시기 바랍니다.";
            out.add(body("TOP", n + ". " + req, 3));
        }
        boolean paren = str(r, "label_style", "colon").equals("paren");
        draftItems(out, list(r, "notes"), paren, false);   // 요청 문장 바로 아래 단서(예: ※ BMT 결과는 별도 제출)
        List<Object> secs = list(r, "sections");
        if (!secs.isEmpty()) {                              // 번호 항목이 없는 통보 · 협조 문서는 「- 아 래 -」 없이 끝난다
            out.add(c.proto("GAP"));
            out.add(c.proto("BELOW"));
        }
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
            // 두 줄로 넘어가면 머리(「첨부 1. 」 · 「     2. 」 — 두 머리는 폭이 같다) 다음 글자에 맞춘다. 작성사례 첫 줄 문단모양(ps20)의 내어쓰기 -9048. 둘째 줄부터의 ps19(-7632)는 줄이 넘어가지 않을 때만 맞아서 쓰지 않는다
            p.getHeader().setParaShapeId(attPs(p.getHeader().getParaShapeId(), atts.size() == 1 ? -5800 : -9048));
            out.add(p);
        }
        anchorTail(footer);
        issue(footer, str(r, "issue", ""));
        keepTailWithBody(out, out.size() - atts.size(), footer);
        out.addAll(footer);
    }

    /** 쪽 추정 오차 여유(pt), 줄 1개 — 길이를 바꾼 시험 36건에서 추정이 한글 위치와 ±6pt 안. 늘리면 작성사례(2쪽)도 첨부가 3쪽으로 밀린다. */
    static final double TAIL_SAFETY = 18;

    /**
     * 결재 칸(쪽 아래 고정)이 마지막 쪽 본문과 겹치지 않게 한다. 한글은 쪽 기준 개체를 그 문단이 처음 놓인 쪽에 두고, 본문은
     * 개체 자리를 피해 다음 쪽으로 민다 — 결재 칸만 앞 쪽에 남거나(첨부02: 결재 칸 3쪽, 10장 · 11장 · 첨부 4쪽) 빈 쪽이 하나 더 생긴다.
     * 한글이 비켜 주는 높이는 그려진 표(약 210pt)가 아니라 저장된 표 높이(344pt)이고, 표 앞 빈 줄 · 표 문단 · 뒤 빈 줄도 그 위에 들어가야 한다
     * (본문 길이를 바꾼 기안문 36건을 한글 PDF로 확인 — 마지막 쪽 본문 끝이 이 한계를 넘은 4건만 어긋났다).
     * 쪽을 추정해 넘치면 첨부 목록(없으면 마지막 장)을 새 쪽에서 시작해 결재 칸과 같은 쪽에 둔다.
     * 첨부 목록이 두 쪽에 걸쳐도(첨부05: 「첨부 1.」 첫 줄만 앞 쪽 맨 끝, 「1부」부터 다음 쪽) 목록째 새 쪽에서 시작한다.
     */
    void keepTailWithBody(List<Paragraph> out, int attStart, List<Paragraph> footer) {
        ControlTable tail = null;
        double after = 0;
        for (Paragraph p : footer) {
            ControlTable t = firstTable(p);
            if (t != null && t.getRowList().size() >= 10) tail = t;
            after += lineHeight(p) + paraSpace(p, true) + paraSpace(p, false);
        }
        if (tail == null) return;
        double limit = bodyHeight() - tail.getHeader().getHeight() / 100.0 - after - TAIL_SAFETY;
        firstPageTop = headHeight();
        Map<Paragraph, Integer> pg = paginate(out);
        int last = pageUsed.keySet().stream().max(Integer::compare).orElse(1);
        double used = pageUsed.getOrDefault(last, 0.0);
        boolean split = attStart < out.size() && pg.get(out.get(attStart)) < last;
        if (DEBUG) System.out.printf("DEBUG tail: last page %d used %.1f vs limit %.1f (tail %.1f, footer lines %.1f, head %.1f) att split %b%n",
                last, used, limit, tail.getHeader().getHeight() / 100.0, after, firstPageTop, split);
        if (used <= limit && !split) return;
        int at = attStart < out.size() ? attStart : -1;
        for (int i = out.size() - 1; at < 0 && i > 0; i--)       // 첨부가 없으면 마지막 장 제목(앞 빈 줄째)
            if (keepNext.contains(out.get(i))) at = out.get(i - 1).getText() == null ? i - 1 : i;
        if (at > 0) out.get(at).getHeader().getDivideSort().setDividePage(true);
    }

    /** 첫 쪽 머리(구역 문단의 로고 · 수신자 · 제목 표) 높이(pt). */
    double headHeight() {
        double h = 0;
        for (Control ct : c.base[c.index("SECTION")].getControlList())
            if (ct instanceof ControlTable) {
                ControlTable t = (ControlTable) ct;
                h += tableHeight(t) + (t.getHeader().getOutterMarginTop() + t.getHeader().getOutterMarginBottom()) / 100.0;
            }
        return h;
    }

    /** 기안문 양식의 합계 행 테두리 · 바탕(가는 실선, 회색 f2f2f2). draft_base.hwp 문서 정보에 양식 그대로 들어 있다. */
    static final int TOTAL_BF = 22;

    /** 표 마지막 행이 「합계」면 양식처럼 회색 바탕에 「합계」를 머리행 글자모양(굵게)으로 쓴다. 앞 칸 병합은 표의 "<"로 준다. */
    static void totalRow(ControlTable t) throws Exception {
        List<Row> rows = t.getRowList();
        if (rows.size() < 3) return;
        Row last = rows.get(rows.size() - 1);
        Paragraph label = last.getCellList().get(0).getParagraphList().getParagraph(0);
        if (!plainText(label).replaceAll("\\s", "").matches("(합계|총계).*")) return;   // 「합 계 (①+②)」처럼 꼬리가 붙어도
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

    /** 번호 없는 요청 문장의 문단모양: 「1. 」 내어쓰기를 없앤 것(둘째 줄도 왼쪽 여백에서 시작). */
    int flatPs(int base) {
        return boxPs.computeIfAbsent("flat/" + base, k -> addParaShape(c.hwp, base, 0, 0));
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

    /**
     * 꼬리 표의 시행 칸에 문서번호 · 시행일을 쓴다(「시행  운영2026-0921  (2026. 9. 10.)」). 이미 시행된 문서를 양식으로 다시 만들 때만 준다 —
     * 이 줄이 없으면 그 문서를 첨부로 읽는 쪽(다음 기안)이 문서번호 · 시행일을 알 수 없다. 새 기안은 전자결재 시스템이 채우므로 비워 둔다.
     */
    void issue(List<Paragraph> footer, String no) throws Exception {
        if (no.isEmpty()) return;
        for (Paragraph p : footer) {
            ControlTable t = firstTable(p);
            if (t == null) continue;
            for (Row row : t.getRowList())
                for (Cell cell : row.getCellList()) {
                    Paragraph q = cell.getParagraphList().getParagraph(0);
                    if (!plainText(q).trim().equals("시행")) continue;
                    setText(q, "시행  " + no, (int) q.getCharShape().getPositonShapeIdPairList().get(0).getShapeId());
                    return;
                }
        }
        throw new IllegalStateException("기안문 양식에서 시행 칸을 찾지 못함");
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
                // 표를 여는 줄(「ㅇ 검사자(내부감사부 입회)」 · 장 제목)이 쪽 끝에 남고 표만 다음 쪽으로 넘어가지 않게
                Paragraph lead = out.get(out.size() - 1);
                if (firstTable(lead) == null) keepNext.add(lead);
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
