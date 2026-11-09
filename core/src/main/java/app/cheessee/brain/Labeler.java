package app.cheessee.brain;

import app.cheessee.core.Position;

/** Gives a played move its sticker (brilliant, great, best, ... blunder). */
public final class Labeler {
    public static final double EXCELLENT_MAX = 2, GOOD_MAX = 5, INACCURACY_MAX = 10, MISTAKE_MAX = 20;
    /** The 2nd-best move must be this much worse for the best move to count as "great" (an only move). */
    public static final double GREAT_GAP = 15;
    /** The opponent's previous move gave away at least this much for a weak reply to count as a "miss". */
    public static final double MISS_OPP_ERROR = 10;

    public static final class Result {
        public Label label;
        /** Winning-chance loss compared with the best move (percentage points). */
        public double loss;
        public int move;
        public boolean white;
    }

    /**
     * @param before      position before the move
     * @param lines       engine lines for {@code before} (rank 1 first)
     * @param move        the move that was played
     * @param afterBest   engine's best line for the position after the move (opponent to move); may be null
     * @param prevTo      destination square of the previous move (to spot recaptures), or -1
     * @param oppLastLoss how many win-% the opponent's previous move gave away (0 if unknown)
     * @return null when the move cannot be judged (forced move, no data)
     */
    public static Result label(Position before, Line[] lines, int move, Line afterBest, int prevTo, double oppLastLoss) {
        if (lines == null || lines.length == 0) return null;
        int legal = before.legalMoves().length;
        if (legal <= 1) return null;

        String uci = Position.uci(move);
        int idx = -1;
        for (int i = 0; i < lines.length; i++) if (uci.equals(lines[i].firstMove())) { idx = i; break; }

        double w0 = lines[0].win();
        double winPlayed;
        String[] pv;
        if (idx >= 0) {
            winPlayed = lines[idx].win();
            pv = lines[idx].pv;
        } else if (afterBest != null) {
            winPlayed = 100 - afterBest.win();
            pv = new String[afterBest.pv.length + 1];
            pv[0] = uci;
            System.arraycopy(afterBest.pv, 0, pv, 1, afterBest.pv.length);
        } else {
            return null;
        }
        double loss = Math.max(0, w0 - winPlayed);

        Result r = new Result();
        r.move = move;
        r.white = before.whiteToMove;
        r.loss = loss;

        if (loss <= HintPicker.BRILLIANT_MAX_LOSS && winPlayed >= 45 && w0 <= 88 && Sacrifice.isSacrifice(before, move, pv)) {
            r.label = Label.BRILLIANT;
            return r;
        }
        if (idx == 0 || loss <= 0.3) {
            boolean recapture = before.isCapture(move) && Position.to(move) == prevTo;
            boolean forcedish = before.inCheck() && legal <= 3;
            if (!recapture && !forcedish && idx == 0 && lines.length >= 2 && w0 - lines[1].win() >= GREAT_GAP) {
                r.label = Label.GREAT;
            } else {
                r.label = Label.BEST;
            }
            return r;
        }
        if (loss <= EXCELLENT_MAX) r.label = Label.EXCELLENT;
        else if (loss <= GOOD_MAX) r.label = Label.GOOD;
        else if (loss <= INACCURACY_MAX) r.label = Label.INACCURACY;
        else if (loss <= MISTAKE_MAX) r.label = oppLastLoss >= MISS_OPP_ERROR ? Label.MISS : Label.MISTAKE;
        else r.label = Label.BLUNDER;
        return r;
    }

    private Labeler() {}
}
