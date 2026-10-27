import java.util.ArrayList;
import java.util.List;

/**
 * 한글의 줄바꿈을 흉내 내 문단이 몇 줄이 되는지 계산한다(한컴 뷰어 화면으로 보정한 근사 모델).
 * - 한글·한자·기호: 글자 단위 줄바꿈 / 영문·숫자 연속: 단어 단위(ByWord) / 공백: 줄 끝이면 버림.
 * - 여는 괄호·따옴표는 뒤 글자에, 닫는 괄호·문장부호는 앞 글자에 붙여 함께 넘긴다.
 * - 폭 단위는 pt. 자간(spc, %)은 모든 글자에 size×spc/100 만큼 더한다.
 */
public class Layout {
    /**
     * 글자 폭 표(em, 함초롬바탕 본문). 뷰어 캡처에서 같은 글자를 4번·18번 늘어놓은 줄의 잉크 끝 차이로 직접 잰 값
     * (A4 쪽 테두리로 px→pt 환산, 2026-10-26 3차 보정). 굵게는 폭이 같고, 자간은 글자 폭에 비례한다(-12%에서 확인).
     */
    public static double SPACE = 0.502, HALF = 0.549, NARROW = 0.319, BOLD = 1.0;
    /**
     * 줄 경계 보정(한 줄에 들어가는지 판정할 때만): 표 값 합에 SCALE을 곱하고, 자간을 줄인 글자가 줄 끝에 오면
     * 그 글자에서 줄인 폭의 END_SPC만큼은 줄지 않은 것으로 본다.
     * 근거: 481.9pt·281.9pt 폭에서 자간 0/-6/-12%로 0.4pt 간격의 줄을 늘어놓고 넘어가는 경계를 관찰(2026-10-26).
     */
    public static double SCALE = 1.0039, END_SPC = 0.8;
    /** 한글·한글 자모·도형 기호(□ ○ ① →): 15pt에서 한 줄(481.9pt)에 33자. 한자는 조금 더 넓다. */
    public static double HANGUL = 0.967, HANJA = 0.999;
    static final String UPPER_W = "705 605 681 721 629 613 685 733 303 315 661 605 840 733 733 601 701 661 629 665 729 705 908 701 701 629";
    static final String LOWER_W = "569 597 553 597 533 358 561 633 287 287 581 287 908 633 589 597 577 478 498 358 633 561 721 541 541 486";
    static final double[] UPPER = parse(UPPER_W), LOWER = parse(LOWER_W);

    static double[] parse(String s) {
        String[] p = s.split(" ");
        double[] d = new double[p.length];
        for (int i = 0; i < p.length; i++) d[i] = Integer.parseInt(p[i]) / 1000.0;
        return d;
    }

    public static class Seg {
        final String text; final double size; final boolean bold; final int spc;
        public Seg(String text, double size, boolean bold, int spc) { this.text = text; this.size = size; this.bold = bold; this.spc = spc; }
    }

    public static double em(char ch) {
        if (ch == ' ') return SPACE;
        if (ch >= 'A' && ch <= 'Z') return UPPER[ch - 'A'];
        if (ch >= 'a' && ch <= 'z') return LOWER[ch - 'a'];
        if ("·.,:;()[]!|'\"‘’".indexOf(ch) >= 0) return NARROW;
        if (ch == '“' || ch == '”') return 0.478;
        if (ch == '‧') return 0.287;
        if (ch == '%' || ch == '@') return 0.828;
        if (ch == '&') return 0.725;
        if (ch == '#') return 0.609;
        if (ch == '※') return 0.768;
        if (ch >= 0x4E00 && ch <= 0x9FFF) return HANJA;
        if (ch < 0x2000) return HALF;   // 숫자, - ~ / * + = < > ? _ 등 반각 기호는 모두 같은 폭
        return HANGUL;
    }

    static boolean latin(char ch) { return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'); }
    static boolean opener(char ch) { return ch == '(' || ch == '[' || ch == '‘' || ch == '“'; }
    static boolean closer(char ch) { return ch == ')' || ch == ']' || ch == '’' || ch == '”' || ch == ',' || ch == '.' || ch == ':' || ch == ';' || ch == '%'; }

    /** 줄바꿈 단위(토큰)와 폭. 공백은 단독 토큰. */
    static class Tok { final StringBuilder s = new StringBuilder(); double w, endPen; boolean space; }

    static List<Tok> tokens(List<Seg> segs, boolean hangulByWord) {
        List<Tok> out = new ArrayList<>();
        Tok cur = null;
        char prev = ' ';
        boolean pendingOpen = false;
        for (Seg g : segs) {
            for (char ch : g.text.toCharArray()) {
                // 자간은 글자 폭에 대한 비율로 적용된다(SpcCalib: 0/-5/-10/-20%에서 33/34/36/41자)
                double natural = em(ch) * g.size * (g.bold ? BOLD : 1.0) * SCALE;
                double w = natural * (1 + g.spc / 100.0);
                if (ch == ' ') {
                    Tok t = new Tok(); t.s.append(ch); t.w = w; t.space = true; out.add(t);
                    cur = null; pendingOpen = false; prev = ch; continue;
                }
                // 어절 단위면 공백 사이 전체가 한 덩어리(뷰어는 "체계도(비전" 사이에서도 끊지 않음)
                boolean join = cur != null && (hangulByWord || pendingOpen || closer(ch) || (latin(ch) && latin(prev)));
                if (!join) { cur = new Tok(); out.add(cur); }
                cur.s.append(ch); cur.w += w;
                cur.endPen = natural * Math.max(0, -g.spc) / 100.0 * END_SPC;
                pendingOpen = opener(ch);
                prev = ch;
            }
        }
        return out;
    }

    /** 줄 나눔 결과: 줄 텍스트, 줄 끝 공백을 뺀 사용 폭, 그 줄의 폭. */
    public static class Result {
        public final List<String> lines = new ArrayList<>();
        public final List<Double> used = new ArrayList<>();
        public final List<Double> cap = new ArrayList<>();
        /** 이 줄 끝이 단어 중간에서 끊겼는지(다음 줄과 공백 없이 이어짐). */
        public final List<Boolean> midWord = new ArrayList<>();

        public int size() { return lines.size(); }

        public int midWordBreaks() {
            int n = 0;
            for (boolean b : midWord) if (b) n++;
            return n;
        }

        /** 양쪽 정렬로 벌어지는 줄(마지막 줄 제외)의 채움 비율 최솟값. */
        public double minFill() {
            double m = 1;
            for (int i = 0; i < lines.size() - 1; i++) m = Math.min(m, used.get(i) / cap.get(i));
            return m;
        }

        public String last() { return lines.get(lines.size() - 1); }
    }

    /** 줄별 텍스트. firstWidth=첫 줄 폭, restWidth=둘째 줄부터 폭(내어쓰기 반영). */
    public static List<String> lines(List<Seg> segs, double firstWidth, double restWidth, boolean hangulByWord) {
        return layout(segs, firstWidth, restWidth, hangulByWord).lines;
    }

    public static Result layout(List<Seg> segs, double firstWidth, double restWidth, boolean hangulByWord) {
        Result r = new Result();
        StringBuilder line = new StringBuilder();
        double used = 0, cap = firstWidth, trail = 0;
        for (Tok t : tokens(segs, hangulByWord)) {
            if (used + t.w + t.endPen <= cap + 0.01 || line.length() == 0) {
                if (!(t.space && line.length() == 0 && !r.lines.isEmpty())) {
                    line.append(t.s); used += t.w; trail = t.space ? trail + t.w : 0;
                }
                continue;
            }
            r.lines.add(line.toString()); r.used.add(used - trail); r.cap.add(cap);
            r.midWord.add(!t.space && trail == 0);
            line.setLength(0); used = 0; trail = 0; cap = restWidth;
            if (t.space) continue;
            line.append(t.s);
            used = t.w;
        }
        if (line.length() > 0) { r.lines.add(line.toString()); r.used.add(used - trail); r.cap.add(cap); r.midWord.add(false); }
        return r;
    }

    /** 마지막 줄 폭(pt). */
    public static double lastLineWidth(List<Seg> segs, double firstWidth, double restWidth, boolean byWord) {
        List<String> ls = lines(segs, firstWidth, restWidth, byWord);
        String last = ls.get(ls.size() - 1);
        double size = segs.isEmpty() ? 15 : segs.get(segs.size() - 1).size;
        double w = 0;
        for (char ch : last.toCharArray()) w += em(ch) * size;
        return w;
    }
}
