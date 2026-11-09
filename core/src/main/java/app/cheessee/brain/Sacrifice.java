package app.cheessee.brain;

import app.cheessee.core.Position;

/** Decides whether a move is a real material sacrifice (the first ingredient of a "brilliant" move). */
public final class Sacrifice {
    /** A sacrifice has to give up at least this much (a bit less than a minor piece net). */
    public static final int MIN_LOSS = 150;

    /**
     * @param pv the engine's principal variation starting with {@code move} (UCI strings); may be just the move
     */
    public static boolean isSacrifice(Position pos, int move, String[] pv) {
        if (pos.see(move) > -MIN_LOSS) return false;
        boolean white = pos.whiteToMove;
        int base = balance(pos, white);
        Position q = pos.play(move);
        int plies = 1;
        if (pv != null) {
            for (int i = 1; i < pv.length && plies < 6; i++) {
                int m = q.parseUci(pv[i]);
                if (m < 0) break;
                q = q.play(m);
                plies++;
            }
        }
        return settledBalance(q, white) - base <= -MIN_LOSS;
    }

    private static int balance(Position p, boolean white) {
        return p.material(white) - p.material(!white);
    }

    /** Material balance after the side to move cashes in its best winning capture (a poor man's quiescence). */
    private static int settledBalance(Position q, boolean white) {
        int bal = balance(q, white);
        int best = 0;
        for (int m : q.legalMoves()) {
            if (!q.isCapture(m)) continue;
            best = Math.max(best, q.see(m));
        }
        return q.whiteToMove == white ? bal + best : bal - best;
    }

    private Sacrifice() {}
}
