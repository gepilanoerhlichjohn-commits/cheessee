package app.cheessee.brain;

import app.cheessee.core.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The glue between "what was read from the screen" and "what the overlay shows".
 *
 * <pre>
 *   Resolved r = coach.resolve(board, lastFrom, lastTo, myWhite);   // 1. what position is this, which move led here?
 *   if (r.pos != null && r.changed) {
 *       Line[] lines = engine.analyze(r.pos.toFen());               // 2. ask Stockfish (MultiPV)
 *       Report rep = coach.complete(r, lines, myWhite);             // 3. hints, sticker, eval, threats
 *   }
 * </pre>
 */
public final class Coach {

    public static final class Resolved {
        public Position pos;
        /** The previous position when {@link #move} was derived from it. */
        public Position prev;
        /** The single move that led from prev to pos, or -1 when unknown. */
        public int move = -1;
        public boolean changed;
        /** True when the move history could not be followed (new game, setup change, two moves at once). */
        public boolean reset;
    }

    public static final class Report {
        public Position pos;
        public Line[] lines;
        public Line best;
        public boolean myTurn;
        public String evalText = "";
        public double whiteWin = 50;
        /** Up to four hints; only filled on my turn. */
        public List<Hint> hints = new ArrayList<>();
        /** The opponent's most likely reply (UCI) when it is their turn, else null. */
        public String predicted;
        /** Sticker for the move that was just played (either side), or null. */
        public Labeler.Result label;
        public int labelSquare = -1;
        public List<Threats.Threat> against = new ArrayList<>();
        public List<Threats.Threat> mine = new ArrayList<>();
        public List<Integer> looseMine = new ArrayList<>();
        public List<Integer> looseTheirs = new ArrayList<>();
    }

    private Position resolvedLast;
    private Position analyzedPos;
    private Line[] analyzedLines;
    private double lastLoss;
    private boolean lastLossWhite;
    private boolean hasLastLoss;
    private int lastMoveTo = -1;

    public void reset() {
        resolvedLast = null;
        analyzedPos = null;
        analyzedLines = null;
        hasLastLoss = false;
        lastMoveTo = -1;
    }

    /**
     * @param board    signed pieces, a1 = 0 (white's point of view, already un-flipped)
     * @param lastFrom from-square of the last-move highlight on screen, or -1
     * @param lastTo   to-square of the last-move highlight on screen, or -1
     */
    public Resolved resolve(int[] board, int lastFrom, int lastTo, boolean myWhite) {
        Resolved r = new Resolved();
        if (resolvedLast != null && Arrays.equals(resolvedLast.b, board)) {
            r.pos = resolvedLast;
            r.changed = false;
            return r;
        }
        Position prev = resolvedLast;
        if (prev != null) {
            for (int m : prev.legalMoves()) {
                Position q = prev.play(m);
                if (Arrays.equals(q.b, board)) {
                    r.pos = q;
                    r.prev = prev;
                    r.move = m;
                    break;
                }
            }
            if (r.pos == null) {
                outer:
                for (int m1 : prev.legalMoves()) {
                    Position q1 = prev.play(m1);
                    for (int m2 : q1.legalMoves()) {
                        Position q2 = q1.play(m2);
                        if (Arrays.equals(q2.b, board)) {
                            r.pos = q2;
                            r.reset = true; // two moves at once: no sticker
                            break outer;
                        }
                    }
                }
            }
        }
        if (r.pos == null) {
            r.pos = build(board, lastFrom, lastTo, myWhite);
            r.reset = true;
            if (r.pos == null) {
                r.changed = false;
                return r;
            }
        }
        r.changed = true;
        resolvedLast = r.pos;
        return r;
    }

    public Report complete(Resolved r, Line[] lines, boolean myWhite) {
        Report rep = new Report();
        Position pos = r.pos;
        rep.pos = pos;
        rep.lines = lines;
        rep.myTurn = pos.whiteToMove == myWhite;

        if (lines != null && lines.length > 0) {
            rep.best = lines[0];
            rep.evalText = Eval.pill(lines[0], pos.whiteToMove);
            rep.whiteWin = Eval.whiteWin(lines[0], pos.whiteToMove);
            if (rep.myTurn) rep.hints = HintPicker.pick(pos, lines);
            else rep.predicted = lines[0].firstMove();

            boolean followed = r.prev != null && r.move >= 0 && analyzedPos != null && analyzedLines != null
                    && analyzedPos.samePlacement(r.prev) && analyzedPos.whiteToMove == r.prev.whiteToMove;
            if (followed) {
                double oppLoss = hasLastLoss && lastLossWhite != r.prev.whiteToMove ? lastLoss : 0;
                Labeler.Result res = Labeler.label(r.prev, analyzedLines, r.move, lines[0], lastMoveTo, oppLoss);
                if (res != null) {
                    rep.label = res;
                    rep.labelSquare = Position.to(r.move);
                    lastLoss = res.loss;
                    lastLossWhite = res.white;
                    hasLastLoss = true;
                } else {
                    hasLastLoss = false;
                }
            } else {
                hasLastLoss = false;
            }
            lastMoveTo = r.move >= 0 ? Position.to(r.move) : -1;
            analyzedPos = pos;
            analyzedLines = lines;
        }

        rep.against = Threats.find(pos, !myWhite, 4);
        rep.mine = Threats.find(pos, myWhite, 3);
        rep.looseMine = Threats.loose(pos, myWhite);
        rep.looseTheirs = Threats.loose(pos, !myWhite);
        return rep;
    }

    /** Squares the piece on {@code sq} could move to (for the "show moves" dots), whoever's turn it is. */
    public static List<Integer> movesFrom(Position pos, int sq) {
        List<Integer> res = new ArrayList<>();
        int piece = pos.b[sq];
        if (piece == 0) return res;
        Position p = pos;
        if ((piece > 0) != pos.whiteToMove) {
            p = pos.nullMove();
            if (p == null) return res;
        }
        for (int m : p.legalMoves()) if (Position.from(m) == sq) res.add(Position.to(m));
        return res;
    }

    /**
     * Builds a position from nothing but piece placement. The side to move comes from the last-move highlight
     * (the side that did NOT just move), otherwise it is assumed to be my turn.
     *
     * @return null when the placement cannot be a real position
     */
    public static Position build(int[] board, int lastFrom, int lastTo, boolean myWhite) {
        int wk = 0, bk = 0;
        for (int s = 0; s < 64; s++) {
            if (board[s] == Position.K) wk++;
            else if (board[s] == -Position.K) bk++;
            int t = Math.abs(board[s]);
            if (t == Position.P && (s < 8 || s >= 56)) return null;
        }
        if (wk != 1 || bk != 1) return null;

        Position p = new Position();
        System.arraycopy(board, 0, p.b, 0, 64);
        boolean stm = lastTo >= 0 && board[lastTo] != 0 ? board[lastTo] < 0 : myWhite;
        p.whiteToMove = stm;
        if (p.attacked(p.kingSquare(!stm), stm)) {
            p.whiteToMove = !stm;
            if (p.attacked(p.kingSquare(stm), !stm)) return null;
        }
        if (p.b[4] == Position.K) {
            if (p.b[7] == Position.R) p.castle |= Position.WK;
            if (p.b[0] == Position.R) p.castle |= Position.WQ;
        }
        if (p.b[60] == -Position.K) {
            if (p.b[63] == -Position.R) p.castle |= Position.BK;
            if (p.b[56] == -Position.R) p.castle |= Position.BQ;
        }
        if (lastFrom >= 0 && lastTo >= 0 && Math.abs(board[lastTo]) == Position.P && Math.abs(lastTo - lastFrom) == 16) {
            p.ep = (lastFrom + lastTo) / 2;
        }
        return p;
    }

    /** Converts a position's board into a fresh signed-int array (handy for tests). */
    public static int[] boardOf(Position p) { return p.b.clone(); }
}
