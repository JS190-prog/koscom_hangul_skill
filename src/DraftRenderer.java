import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 기안문(type: draft). 본문을 만든다: 관련 근거 → 요청 문장 → "- 아 래 -" → 번호 항목(1. 2. …).
 * 머리(수신자·제목)와 첨부 목록은 recipient · title · attachments를 주면 채우고, 없으면 양식의 「시스템 자동생성 영역」 그대로 둔다.
 * 결재 · 협조 · 시행 정보는 전자결재 시스템이 만드는 영역이라 양식 그대로 둔다.
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
        out.add(c.proto("GAP"));
        List<Paragraph> footer = c.protoRange("FOOTER");
        List<Object> atts = list(r, "attachments");
        if (!atts.isEmpty()) {          // 꼬리 첫 줄 「첨부 //. 끝.」 안내를 첨부 목록으로 바꾼다(둘째 줄부터 번호를 첫 항목 번호에 맞춤)
            Paragraph proto = footer.remove(0);
            for (int i = 0; i < atts.size(); i++) {
                String head = atts.size() == 1 ? "첨부 " : i == 0 ? "첨부 1. " : "     " + (i + 1) + ". ";
                String end = i == atts.size() - 1 ? ". 끝." : "";
                out.add(setText(proto.clone(), head + String.valueOf(atts.get(i)).trim() + end, bodyCs()));
            }
        }
        out.addAll(footer);
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
                if (!str(it, "unit", "").isEmpty()) out.add(line("UNIT", str(it, "unit", "")));
                out.add(table(it));
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
