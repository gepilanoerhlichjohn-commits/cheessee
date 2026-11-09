package app.cheessee.brain;

/** One engine line (a MultiPV entry). Scores are from the point of view of the side to move. */
public final class Line {
    public int multipv = 1;
    public int depth;
    /** Centipawns (only meaningful when {@link #mate} is 0). */
    public int cp;
    /** 0 = no mate; +N = side to move mates in N; -N = side to move gets mated in N. */
    public int mate;
    public String[] pv = new String[0];

    public Line() {}

    public Line(int multipv, int cp, int mate, String... pv) {
        this.multipv = multipv;
        this.cp = cp;
        this.mate = mate;
        this.pv = pv;
    }

    public String firstMove() { return pv.length > 0 ? pv[0] : null; }

    /** Winning chances for the side to move, 0..100. */
    public double win() { return Eval.win(cp, mate); }

    /**
     * Parses a UCI "info ..." line. Returns null for lines without a usable score + pv
     * (e.g. "info string ..." or bound-only scores).
     */
    public static Line parse(String info) {
        if (info == null || !info.startsWith("info ")) return null;
        String[] t = info.trim().split("\\s+");
        Line l = new Line();
        boolean hasScore = false;
        for (int i = 1; i < t.length; i++) {
            switch (t[i]) {
                case "depth":
                    if (i + 1 < t.length) l.depth = parseInt(t[++i]);
                    break;
                case "multipv":
                    if (i + 1 < t.length) l.multipv = parseInt(t[++i]);
                    break;
                case "score":
                    if (i + 2 < t.length) {
                        String kind = t[++i];
                        int v = parseInt(t[++i]);
                        if (kind.equals("cp")) l.cp = v;
                        else if (kind.equals("mate")) l.mate = v;
                        else return null;
                        hasScore = true;
                    }
                    break;
                case "lowerbound":
                case "upperbound":
                    return null;
                case "pv": {
                    int n = t.length - i - 1;
                    l.pv = new String[n];
                    System.arraycopy(t, i + 1, l.pv, 0, n);
                    i = t.length;
                    break;
                }
                default:
                    break;
            }
        }
        return hasScore && l.pv.length > 0 ? l : null;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    @Override public String toString() {
        return "Line#" + multipv + " d" + depth + (mate != 0 ? " M" + mate : " " + cp + "cp") + " " + String.join(" ", pv);
    }
}
