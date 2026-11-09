package app.cheessee.brain;

import app.cheessee.core.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the engine's MultiPV lines into up to four coloured hints:
 * dark green (best), light green (good/excellent), yellow (playable but a bit worse, maybe a trap),
 * blue (brilliant sacrifice).  Every hint comes straight from the engine's own lines, so the moves are
 * real engine moves; this class only decides which ones are worth showing and how to colour them.
 */
public final class HintPicker {
    /** Max win-chance loss (percentage points) for a "brilliant" move. */
    public static final double BRILLIANT_MAX_LOSS = 2.0;
    /** Light green: up to this much worse than the best move. */
    public static final double GOOD_MAX_LOSS = 5.0;
    /** Yellow: between GOOD_MAX_LOSS and this. */
    public static final double TRICKY_MAX_LOSS = 15.0;
    /** Yellow moves must still leave the mover with at least this winning chance. */
    public static final double TRICKY_MIN_WIN = 35.0;
    /** The opponent has an "only move" when the 2nd-best reply is this much worse than the best. */
    public static final double TRAP_GAP = 20.0;

    public static List<Hint> pick(Position pos, Line[] lines) {
        List<Hint> out = new ArrayList<>();
        if (lines == null || lines.length == 0) return out;
        int n = lines.length;
        double w0 = lines[0].win();
        int[] mv = new int[n];
        for (int i = 0; i < n; i++) mv[i] = pos.parseUci(lines[i].firstMove());

        int brilliant = -1;
        for (int i = 0; i < n; i++) {
            if (mv[i] < 0) continue;
            if (isBrilliant(pos, lines[i], mv[i], w0)) { brilliant = i; break; }
        }

        if (mv[0] >= 0) out.add(make(pos, lines, mv, 0, brilliant == 0 ? Hint.Kind.BRILLIANT : Hint.Kind.BEST, w0));
        if (brilliant > 0) out.add(make(pos, lines, mv, brilliant, Hint.Kind.BRILLIANT, w0));

        for (int i = 1; i < n; i++) {
            if (i == brilliant || mv[i] < 0) continue;
            if (w0 - lines[i].win() <= GOOD_MAX_LOSS) {
                out.add(make(pos, lines, mv, i, Hint.Kind.GOOD, w0));
                break;
            }
        }

        int tricky = -1;
        double bestLoss = Double.MAX_VALUE;
        for (int i = 1; i < n; i++) {
            if (i == brilliant || mv[i] < 0) continue;
            double loss = w0 - lines[i].win();
            if (loss > GOOD_MAX_LOSS && loss <= TRICKY_MAX_LOSS && lines[i].win() >= TRICKY_MIN_WIN && loss < bestLoss) {
                bestLoss = loss;
                tricky = i;
            }
        }
        if (tricky >= 0) out.add(make(pos, lines, mv, tricky, Hint.Kind.TRICKY, w0));
        return out;
    }

    /** Can this line be called a brilliant move? */
    public static boolean isBrilliant(Position pos, Line line, int move, double bestWin) {
        double win = line.win();
        if (bestWin - win > BRILLIANT_MAX_LOSS) return false;
        if (win < 45) return false;          // not brilliant if it leaves you worse
        if (bestWin > 88) return false;      // not brilliant when you were already crushing
        return Sacrifice.isSacrifice(pos, move, line.pv);
    }

    private static Hint make(Position pos, Line[] lines, int[] mv, int i, Hint.Kind kind, double w0) {
        Hint h = new Hint();
        h.kind = kind;
        h.move = mv[i];
        h.uci = Position.uci(mv[i]);
        h.san = pos.toSan(mv[i]);
        h.win = lines[i].win();
        h.loss = Math.max(0, w0 - h.win);
        h.line = lines[i];
        return h;
    }

    /**
     * Trap check for a hint: after the hint move, does the opponent have to find an only-move?
     *
     * @param childLines MultiPV lines (at least two) for the position after the hint move,
     *                   scored from the opponent's point of view
     */
    public static boolean isTrap(Line[] childLines) {
        if (childLines == null || childLines.length < 2) return false;
        double gap = childLines[0].win() - childLines[1].win();
        double myWinAfterBestReply = 100 - childLines[0].win();
        return gap >= TRAP_GAP && myWinAfterBestReply >= TRICKY_MIN_WIN - 5;
    }

    private HintPicker() {}
}
