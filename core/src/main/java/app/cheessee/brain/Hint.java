package app.cheessee.brain;

/** One suggested move, shown as a thin straight arrow in a colour that says how good it is. */
public final class Hint {
    public enum Kind {
        /** Dark green: the engine's top move. */
        BEST,
        /** Light green: a good / excellent alternative. */
        GOOD,
        /** Yellow: a bit worse but still playable (may set a trap). */
        TRICKY,
        /** Blue: a brilliant sacrifice that still holds up. */
        BRILLIANT
    }

    public Kind kind;
    public int move;
    public String uci;
    public String san;
    /** Winning-chance loss versus the best move, in percentage points. */
    public double loss;
    /** Winning chances for the mover after this move, 0..100. */
    public double win;
    /** True when the opponent has to find an only-move to avoid trouble (set by the trap check). */
    public boolean trap;
    public Line line;

    public int from() { return move & 63; }
    public int to() { return (move >> 6) & 63; }
}
