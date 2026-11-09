package app.cheessee.brain;

import java.util.Locale;

/** Score helpers: win-probability model (same curve Lichess uses) and display strings. */
public final class Eval {
    /** Winning chances 0..100 for the side the score belongs to. */
    public static double win(int cp, int mate) {
        if (mate > 0) return 100;
        if (mate < 0) return 0;
        double c = Math.max(-1000, Math.min(1000, cp));
        return 50 + 50 * (2 / (1 + Math.exp(-0.00368208 * c)) - 1);
    }

    /** Eval-bar fill: white's winning chances 0..100. */
    public static double whiteWin(Line best, boolean whiteToMove) {
        double w = best.win();
        return whiteToMove ? w : 100 - w;
    }

    /** "+0.8", "-1.3", "0.0", "#3", "-#2" from white's point of view. */
    public static String pill(Line best, boolean whiteToMove) {
        if (best.mate != 0) {
            int m = whiteToMove ? best.mate : -best.mate;
            return (m > 0 ? "#" : "-#") + Math.abs(m);
        }
        int cp = whiteToMove ? best.cp : -best.cp;
        if (Math.abs(cp) < 5) return "0.0";
        return String.format(Locale.US, "%+.1f", cp / 100.0);
    }

    private Eval() {}
}
