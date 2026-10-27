import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlExtend;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlInline;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * hwplib 위에서 문단·표를 다루는 공통 도구.
 *
 * P0에서 확정한 규칙을 여기서 지킨다.
 *  - 글자를 바꾼 문단은 LineSeg를 지운다(옛 정보가 남으면 한글이 파일을 열지 못함).
 *  - 문단 끝 문자(0x0D)는 정확히 하나만 둔다(ParaText.addString은 스스로 붙이고, 글자 단위 추가는 붙이지 않음).
 *  - 표·도형 같은 확장 컨트롤 문자는 텍스트를 바꿀 때도 그대로 보존한다.
 */
public class HwpOps {

    /** 같은 글자모양이 적용되는 텍스트 구간. 텍스트의 '\t'는 탭 문자로 들어간다. */
    public static class Run {
        public final String text;
        public final int cs;

        public Run(String text, int cs) { this.text = text; this.cs = cs; }

        @Override public String toString() { return cs + ":" + text; }
    }

    public static Run run(String text, int cs) { return new Run(text, cs); }

    // ------------------------------------------------------------------ 텍스트

    /** 문단의 텍스트를 글자모양 구간으로 읽는다(확장 컨트롤 문자는 건너뜀, 탭은 '\t'). */
    public static List<Run> getRuns(Paragraph p) {
        List<Run> out = new ArrayList<>();
        if (p.getText() == null) return out;
        List<CharPositionShapeIdPair> pairs = p.getCharShape() == null
                ? new ArrayList<>() : p.getCharShape().getPositonShapeIdPairList();
        int pos = 0, pi = 0, curCs = pairs.isEmpty() ? 0 : (int) pairs.get(0).getShapeId();
        StringBuilder sb = new StringBuilder();
        for (HWPChar ch : p.getText().getCharList()) {
            while (pi < pairs.size() && pairs.get(pi).getPosition() <= pos) {
                int next = (int) pairs.get(pi).getShapeId();
                if (next != curCs && sb.length() > 0) { out.add(new Run(sb.toString(), curCs)); sb.setLength(0); }
                curCs = next;
                pi++;
            }
            if (ch.getType() == HWPCharType.Normal) sb.append((char) ch.getCode());
            else if (ch.getType() == HWPCharType.ControlInline && ch.getCode() == 9) sb.append('\t');
            pos += ch.getCharSize();
        }
        if (sb.length() > 0) out.add(new Run(sb.toString(), curCs));
        return out;
    }

    public static String plainText(Paragraph p) {
        StringBuilder sb = new StringBuilder();
        for (Run r : getRuns(p)) sb.append(r.text);
        return sb.toString();
    }

    /**
     * 문단의 텍스트를 runs로 통째로 바꾼다. 기존 확장 컨트롤(표, 도형, 구역 정의 등)은 맨 앞에 그대로 둔다.
     * '\t'는 원래 문단에 있던 탭 문자(없으면 기본 탭)를 복제해 넣는다.
     */
    public static Paragraph setRuns(Paragraph p, List<Run> runs) throws Exception {
        List<HWPChar> keep = new ArrayList<>();
        byte[] tabAddition = null;
        int fallbackCs = 0;
        if (p.getCharShape() != null && !p.getCharShape().getPositonShapeIdPairList().isEmpty())
            fallbackCs = (int) p.getCharShape().getPositonShapeIdPairList().get(0).getShapeId();
        if (p.getText() != null) {
            for (HWPChar ch : p.getText().getCharList()) {
                if (ch.getType() == HWPCharType.ControlExtend) keep.add(ch);
                if (ch.getType() == HWPCharType.ControlInline && ch.getCode() == 9 && tabAddition == null)
                    tabAddition = ((HWPCharControlInline) ch).getAddition().clone();
            }
        }
        p.deleteText();
        p.createText();
        p.deleteCharShape();
        p.createCharShape();
        int pos = 0;
        for (HWPChar ch : keep) { p.getText().getCharList().add(ch); pos += ch.getCharSize(); }
        int lastCs = -1;
        for (Run r : runs) {
            if (r.text.isEmpty()) continue;
            if (r.cs != lastCs) {
                p.getCharShape().addParaCharShape(lastCs == -1 ? 0 : pos, r.cs);
                lastCs = r.cs;
            }
            for (char c : r.text.toCharArray()) {
                if (c == '\t') {
                    HWPCharControlInline tab = p.getText().addNewInlineControlChar();
                    tab.setCode(9);
                    tab.setAddition(tabAddition != null ? tabAddition.clone() : new byte[12]);
                    pos += tab.getCharSize();
                } else {
                    p.getText().addNewNormalChar().setCode(c);
                    pos += 1;
                }
            }
        }
        if (lastCs == -1) p.getCharShape().addParaCharShape(0, fallbackCs);
        // 글자가 있으면 문단 끝 문자(0x0D)를 붙인다(addNewNormalChar는 자동으로 붙이지 않음, 빠지면 한글이 못 엶).
        // 빈 문단은 원본처럼 텍스트 레코드 없이 둔다(hwplib가 빈 목록을 쓰지 못함).
        if (pos == 0) p.deleteText();
        else p.getText().addNewCharControlChar().setCode(0x0d);
        p.getHeader().setCharacterCount(pos + 1);
        p.getHeader().setCharShapeCount(p.getCharShape().getPositonShapeIdPairList().size());
        dropLineSeg(p);
        return p;
    }

    /** 글자 없이 글자모양만 cs로 지정한 문단(간격용 빈 줄)으로 만든다. 확장 컨트롤은 보존. */
    public static Paragraph setEmpty(Paragraph p, int cs) throws Exception {
        setRuns(p, new ArrayList<>());
        p.getCharShape().getPositonShapeIdPairList().get(0).setShapeId(cs);
        return p;
    }

    /** 문단 전체를 한 글자모양의 텍스트로 바꾼다. */
    public static Paragraph setText(Paragraph p, String text, int cs) throws Exception {
        List<Run> rs = new ArrayList<>();
        rs.add(new Run(text, cs));
        return setRuns(p, rs);
    }

    static final Pattern PH = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    /**
     * 원형 문단의 각 구간에 들어 있는 {{이름}}을 values로 바꾼다.
     * 값이 없거나 빈 placeholder를 가진 구간은 통째로 뺀다(예: 라벨이 없는 ㅇ 항목).
     */
    public static Paragraph fill(Paragraph p, Map<String, String> values) throws Exception {
        List<Run> out = new ArrayList<>();
        for (Run r : getRuns(p)) {
            Matcher m = PH.matcher(r.text);
            StringBuffer sb = new StringBuffer();
            boolean drop = false;
            while (m.find()) {
                String v = values.get(m.group(1));
                if (v == null || v.isEmpty()) { drop = true; break; }
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
            if (drop) continue;
            m.appendTail(sb);
            out.add(new Run(sb.toString(), r.cs));
        }
        return setRuns(p, out);
    }

    public static Map<String, String> vals(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static void dropLineSeg(Paragraph p) {
        p.deleteLineSeg();
        p.getHeader().setLineAlignCount(0);
    }

    // ------------------------------------------------------------------ 컨트롤

    /** 문단에서 컨트롤 하나를 지운다(대응하는 확장 컨트롤 문자와 글자모양 위치도 정리). */
    public static void removeControl(Paragraph p, Control c) {
        int idx = p.getControlList().indexOf(c);
        if (idx < 0) throw new IllegalArgumentException("control not in paragraph");
        List<HWPChar> chars = p.getText().getCharList();
        int seen = -1, pos = 0, at = -1, size = 0;
        for (int i = 0; i < chars.size(); i++) {
            HWPChar ch = chars.get(i);
            if (ch.getType() == HWPCharType.ControlExtend && ++seen == idx) { at = i; size = ch.getCharSize(); break; }
            pos += ch.getCharSize();
        }
        if (at < 0) throw new IllegalStateException("extend char not found for control " + idx);
        chars.remove(at);
        p.getControlList().remove(idx);
        shiftCharShapes(p, pos, -size);
        p.getHeader().setCharacterCount(p.getHeader().getCharacterCount() - size);
        dropLineSeg(p);
    }

    /** from 문단의 확장 컨트롤 전부(구역·단 정의 등)를 to 문단 맨 앞으로 옮긴다. */
    public static void moveControlsToFront(Paragraph from, Paragraph to) {
        List<HWPChar> moved = new ArrayList<>();
        for (HWPChar ch : from.getText().getCharList())
            if (ch.getType() == HWPCharType.ControlExtend) moved.add(ch);
        List<Control> ctrls = new ArrayList<>(from.getControlList());
        int size = 0;
        for (HWPChar ch : moved) size += ch.getCharSize();
        if (to.getText() == null) to.createText();
        to.getText().getCharList().addAll(0, moved);
        if (to.getControlList() == null) to.createControlList();
        to.getControlList().addAll(0, ctrls);
        shiftCharShapes(to, 1, size);
        to.getHeader().setCharacterCount(to.getHeader().getCharacterCount() + size);
        to.getHeader().getControlMask().setValue(to.getHeader().getControlMask().getValue()
                | from.getHeader().getControlMask().getValue());
        dropLineSeg(to);
    }

    /**
     * 컨트롤 하나를 문단 맨 앞에 넣는다(확장 컨트롤 문자 + 컨트롤 목록).
     * code: 확장 문자 코드(11=표·그림, 21=쪽 감추기·새 번호 등), ctrlId: 4글자 컨트롤 ID(예 "gso ", "pghd", "nwno").
     */
    public static void insertControlFront(Paragraph p, Control ctrl, int code, String ctrlId) throws Exception {
        boolean noText = p.getText() == null;
        if (noText) p.createText();
        HWPCharControlExtend ch = p.getText().addNewExtendControlChar();
        p.getText().getCharList().remove(p.getText().getCharList().size() - 1);
        ch.setCode(code);
        byte[] add = new byte[12];
        for (int i = 0; i < 4; i++) add[i] = (byte) ctrlId.charAt(3 - i); // ID는 뒤집힌 순서로 저장됨(nwno → "onwn")
        ch.setAddition(add);
        p.getText().getCharList().add(0, ch);
        if (noText) p.getText().addNewCharControlChar().setCode(0x0d);
        if (p.getControlList() == null) p.createControlList();
        p.getControlList().add(0, ctrl);
        shiftCharShapes(p, 1, ch.getCharSize());
        p.getHeader().setCharacterCount((noText ? 1 : p.getHeader().getCharacterCount()) + ch.getCharSize());
        p.getHeader().getControlMask().setValue(p.getHeader().getControlMask().getValue() | (1L << code));
        dropLineSeg(p);
    }

    /** position >= from 인 글자모양 시작 위치를 delta만큼 옮긴다(시작 위치 0은 그대로). */
    static void shiftCharShapes(Paragraph p, int from, int delta) {
        if (p.getCharShape() == null) return;
        ArrayList<CharPositionShapeIdPair> l = p.getCharShape().getPositonShapeIdPairList();
        for (CharPositionShapeIdPair pr : l)
            if (pr.getPosition() > 0 && pr.getPosition() >= from) pr.setPosition(Math.max(0, pr.getPosition() + delta));
        for (int i = l.size() - 1; i > 0; i--)
            if (l.get(i).getPosition() <= l.get(i - 1).getPosition()) l.remove(i - 1);
        p.getHeader().setCharShapeCount(l.size());
    }

    public static ControlTable firstTable(Paragraph p) {
        if (p.getControlList() == null) return null;
        for (Control c : p.getControlList()) if (c instanceof ControlTable) return (ControlTable) c;
        return null;
    }

    public static Cell cell(ControlTable t, int row, int col) {
        for (Row r : t.getRowList())
            for (Cell c : r.getCellList())
                if (c.getListHeader().getRowIndex() == row && c.getListHeader().getColIndex() == col) return c;
        throw new IllegalArgumentException("no cell " + row + "," + col);
    }

    /** 셀 문단 목록에서 keep 번호의 문단만 남긴다(순서 유지). */
    public static void keepParagraphs(ParagraphList pl, int... keep) {
        for (int i = pl.getParagraphCount() - 1; i >= 0; i--) {
            boolean k = false;
            for (int x : keep) if (x == i) k = true;
            if (!k) pl.deleteParagraph(i);
        }
        for (int i = 0; i < pl.getParagraphCount(); i++)
            pl.getParagraph(i).getHeader().setLastInList(i == pl.getParagraphCount() - 1);
    }

    public static void fixCellParaCount(Cell c) {
        c.getListHeader().setParaCount(c.getParagraphList().getParagraphCount());
    }

    // ------------------------------------------------------------------ DocInfo

    /** base 글자모양을 복제해 크기(pt)·굵기만 바꾼 글자모양을 추가하고 번호를 돌려준다. 같은 조합은 재사용. */
    public static int ensureCharShape(HWPFile hwp, Map<String, Integer> cache, int base, double sizePt, Boolean bold) {
        String key = base + "/" + sizePt + "/" + bold;
        if (cache.containsKey(key)) return cache.get(key);
        ArrayList<CharShape> list = hwp.getDocInfo().getCharShapeList();
        CharShape cs = list.get(base).clone();
        cs.setBaseSize((int) Math.round(sizePt * 100));
        if (bold != null) cs.getProperty().setBold(bold);
        list.add(cs);
        cache.put(key, list.size() - 1);
        return list.size() - 1;
    }

    /** base 글자모양의 변형(자간, 위첨자 등)을 추가하고 번호를 돌려준다. key가 같으면 재사용. */
    public static int ensureVariant(HWPFile hwp, Map<String, Integer> cache, int base, String key,
                                    java.util.function.Consumer<CharShape> change) {
        String k = base + "/" + key;
        if (cache.containsKey(k)) return cache.get(k);
        ArrayList<CharShape> list = hwp.getDocInfo().getCharShapeList();
        CharShape cs = list.get(base).clone();
        change.accept(cs);
        list.add(cs);
        cache.put(k, list.size() - 1);
        return list.size() - 1;
    }

    /** base 문단모양을 복제해 왼쪽 여백·들여쓰기만 바꾼 문단모양을 추가한다(값 단위는 파일 저장값 = HWPUNIT×2). */
    public static int addParaShape(HWPFile hwp, int base, int leftMargin, int indent) {
        ArrayList<ParaShape> list = hwp.getDocInfo().getParaShapeList();
        ParaShape ps = list.get(base).clone();
        ps.setLeftMargin(leftMargin);
        ps.setIndent(indent);
        // 줄나눔은 원본처럼 글자 단위로 둔다. 어절 단위로 바꾸면 긴 어절이 넘어갈 때 양쪽 정렬로 빈칸이 크게 벌어진다.
        // 단어 중간 끊김은 렌더러가 자간을 줄여 피한다(원본 작성자가 손으로 하던 방식).
        list.add(ps);
        return list.size() - 1;
    }
}
