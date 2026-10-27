import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControl;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlRectangle;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.io.PrintStream;

/** 최상위 문단 번호·문단모양·글자모양 구간·텍스트·컨트롤(표/도형 종류)을 출력한다. */
public class Inspect {
    static PrintStream out;

    public static void main(String[] args) throws Exception {
        out = new PrintStream(System.out, true, "UTF-8");
        HWPFile hwp = HWPReader.fromFile(args[0]);
        Section s = hwp.getBodyText().getSectionList().get(0);
        for (int i = 0; i < s.getParagraphCount(); i++) dump(s.getParagraph(i), "#" + i, "");
    }

    static void dump(Paragraph p, String label, String ind) throws Exception {
        StringBuilder cs = new StringBuilder();
        if (p.getCharShape() != null)
            for (CharPositionShapeIdPair pr : p.getCharShape().getPositonShapeIdPairList())
                cs.append(pr.getPosition()).append(':').append(pr.getShapeId()).append(' ');
        String t = p.getText() == null ? "" : p.getNormalString();
        out.println(ind + label + (p.getHeader().getDivideSort().isDividePage() ? " [PAGEBREAK]" : "")
                + " ps" + p.getHeader().getParaShapeId() + " st" + p.getHeader().getStyleId()
                + " cs[" + cs.toString().trim() + "] seg=" + (p.getLineSeg() != null) + " \"" + t + "\"");
        if (p.getControlList() == null) return;
        int k = 0;
        for (Control c : p.getControlList()) {
            String name = c.getType().toString();
            if (c instanceof GsoControl) name += "/" + ((GsoControl) c).getGsoType();
            if (c instanceof ControlTable) {
                ControlTable tb = (ControlTable) c;
                out.println(ind + "  ctrl" + k + " TABLE " + tb.getTable().getRowCount() + "x" + tb.getTable().getColumnCount()
                        + " bf" + tb.getTable().getBorderFillId() + " h=" + tb.getHeader().getHeight());
                for (Row r : tb.getRowList())
                    for (Cell cell : r.getCellList()) {
                        String cl = "cell(" + cell.getListHeader().getRowIndex() + "," + cell.getListHeader().getColIndex()
                                + ") bf" + cell.getListHeader().getBorderFillId() + " span" + cell.getListHeader().getRowSpan()
                                + "x" + cell.getListHeader().getColSpan();
                        out.println(ind + "    " + cl);
                        int j = 0;
                        for (Paragraph cp : cell.getParagraphList()) dump(cp, "p" + (j++), ind + "      ");
                    }
            } else {
                out.println(ind + "  ctrl" + k + " " + name);
                if (c instanceof ControlRectangle && ((ControlRectangle) c).getTextBox() != null) {
                    int j = 0;
                    for (Paragraph cp : ((ControlRectangle) c).getTextBox().getParagraphList()) dump(cp, "tb" + (j++), ind + "      ");
                }
            }
            k++;
        }
    }
}
