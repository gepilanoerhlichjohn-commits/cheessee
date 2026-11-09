package app.cheessee.core;

import java.util.Arrays;

/**
 * A chess position. Squares are 0..63 with a1 = 0, b1 = 1, ... h1 = 7, a2 = 8 ... h8 = 63.
 * Pieces are signed ints: +1..+6 = white P N B R Q K, -1..-6 = black.
 * Moves are plain ints: from | to << 6 | promotionPieceType << 12.
 */
public final class Position {
    public static final int P = 1, N = 2, B = 3, R = 4, Q = 5, K = 6;
    /** Centipawn-ish piece values, index = piece type. */
    public static final int[] VALUE = {0, 100, 320, 330, 500, 900, 20000};
    public static final int WK = 1, WQ = 2, BK = 4, BQ = 8;
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private static final int[] KN_DX = {1, 2, 2, 1, -1, -2, -2, -1};
    private static final int[] KN_DY = {2, 1, -1, -2, -2, -1, 1, 2};
    private static final int[] K_DX = {1, 1, 1, 0, -1, -1, -1, 0};
    private static final int[] K_DY = {1, 0, -1, -1, -1, 0, 1, 1};
    /** Ray directions: 0..3 straight (N, E, S, W), 4..7 diagonal. */
    private static final int[] DX = {0, 1, 0, -1, 1, 1, -1, -1};
    private static final int[] DY = {1, 0, -1, 0, 1, -1, -1, 1};

    private static final int[] CASTLE_MASK = new int[64];

    static {
        CASTLE_MASK[0] = WQ;
        CASTLE_MASK[4] = WK | WQ;
        CASTLE_MASK[7] = WK;
        CASTLE_MASK[56] = BQ;
        CASTLE_MASK[60] = BK | BQ;
        CASTLE_MASK[63] = BK;
    }

    public final int[] b = new int[64];
    public boolean whiteToMove = true;
    public int castle = 0;
    /** En passant target square or -1. */
    public int ep = -1;
    public int halfmove = 0;
    public int fullmove = 1;

    // ------------------------------------------------------------------ helpers

    public static int file(int sq) { return sq & 7; }
    public static int rank(int sq) { return sq >> 3; }
    public static int sq(int file, int rank) { return rank * 8 + file; }
    private static boolean in(int f, int r) { return (f & ~7) == 0 && (r & ~7) == 0; }

    public static int move(int from, int to) { return from | (to << 6); }
    public static int move(int from, int to, int promo) { return from | (to << 6) | (promo << 12); }
    public static int from(int m) { return m & 63; }
    public static int to(int m) { return (m >> 6) & 63; }
    public static int promo(int m) { return (m >> 12) & 7; }

    public static String sqName(int sq) {
        return "" + (char) ('a' + file(sq)) + (char) ('1' + rank(sq));
    }

    /** "e4" -> 28, or -1 when malformed. */
    public static int sqFromName(String s) {
        if (s == null || s.length() < 2) return -1;
        int f = s.charAt(0) - 'a', r = s.charAt(1) - '1';
        return in(f, r) ? sq(f, r) : -1;
    }

    public static String uci(int m) {
        String s = sqName(from(m)) + sqName(to(m));
        int p = promo(m);
        if (p != 0) s += "?pnbrqk".charAt(p);
        return s;
    }

    public Position copy() {
        Position q = new Position();
        System.arraycopy(b, 0, q.b, 0, 64);
        q.whiteToMove = whiteToMove;
        q.castle = castle;
        q.ep = ep;
        q.halfmove = halfmove;
        q.fullmove = fullmove;
        return q;
    }

    public static boolean isWhite(int piece) { return piece > 0; }

    public int kingSquare(boolean white) {
        int k = white ? K : -K;
        for (int i = 0; i < 64; i++) if (b[i] == k) return i;
        return -1;
    }

    /** Material (no kings) for one side, in centipawn-ish units. */
    public int material(boolean white) {
        int s = 0;
        for (int i = 0; i < 64; i++) {
            int p = b[i];
            if (p == 0) continue;
            if ((p > 0) == white && Math.abs(p) != K) s += VALUE[Math.abs(p)];
        }
        return s;
    }

    public boolean samePlacement(Position o) { return Arrays.equals(b, o.b); }

    // ------------------------------------------------------------------ FEN

    public static Position fromFen(String fen) {
        String[] t = fen.trim().split("\\s+");
        if (t.length < 1) throw new IllegalArgumentException("empty FEN");
        Position p = new Position();
        String[] rows = t[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("bad FEN rows: " + fen);
        for (int i = 0; i < 8; i++) {
            int rank = 7 - i, file = 0;
            for (char c : rows[i].toCharArray()) {
                if (c >= '1' && c <= '8') {
                    file += c - '0';
                } else {
                    int type = "pnbrqk".indexOf(Character.toLowerCase(c)) + 1;
                    if (type == 0 || file > 7) throw new IllegalArgumentException("bad FEN: " + fen);
                    p.b[sq(file++, rank)] = Character.isUpperCase(c) ? type : -type;
                }
            }
            if (file != 8) throw new IllegalArgumentException("bad FEN row: " + rows[i]);
        }
        p.whiteToMove = t.length < 2 || !t[1].equals("b");
        if (t.length > 2) {
            for (char c : t[2].toCharArray()) {
                if (c == 'K') p.castle |= WK;
                else if (c == 'Q') p.castle |= WQ;
                else if (c == 'k') p.castle |= BK;
                else if (c == 'q') p.castle |= BQ;
            }
        }
        if (t.length > 3 && !t[3].equals("-")) p.ep = sqFromName(t[3]);
        if (t.length > 4) p.halfmove = parseIntSafe(t[4], 0);
        if (t.length > 5) p.fullmove = parseIntSafe(t[5], 1);
        if (p.kingSquare(true) < 0 || p.kingSquare(false) < 0) throw new IllegalArgumentException("missing king: " + fen);
        return p;
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    public String placementFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                int p = b[sq(f, r)];
                if (p == 0) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                char c = "pnbrqk".charAt(Math.abs(p) - 1);
                sb.append(p > 0 ? Character.toUpperCase(c) : c);
            }
            if (empty > 0) sb.append(empty);
            if (r > 0) sb.append('/');
        }
        return sb.toString();
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder(placementFen());
        sb.append(whiteToMove ? " w " : " b ");
        if (castle == 0) sb.append('-');
        else {
            if ((castle & WK) != 0) sb.append('K');
            if ((castle & WQ) != 0) sb.append('Q');
            if ((castle & BK) != 0) sb.append('k');
            if ((castle & BQ) != 0) sb.append('q');
        }
        sb.append(' ').append(ep >= 0 ? sqName(ep) : "-");
        sb.append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    @Override public String toString() { return toFen(); }

    // ------------------------------------------------------------------ attacks

    /** Is square {@code sq} attacked by any piece of the given colour (ignores what stands on sq)? */
    public boolean attacked(int sq, boolean byWhite) {
        int f = file(sq), r = rank(sq);
        int sgn = byWhite ? 1 : -1;
        int pr = byWhite ? r - 1 : r + 1;
        if (pr >= 0 && pr < 8) {
            if (f > 0 && b[pr * 8 + f - 1] == sgn * P) return true;
            if (f < 7 && b[pr * 8 + f + 1] == sgn * P) return true;
        }
        for (int i = 0; i < 8; i++) {
            int nf = f + KN_DX[i], nr = r + KN_DY[i];
            if (in(nf, nr) && b[nr * 8 + nf] == sgn * N) return true;
            nf = f + K_DX[i];
            nr = r + K_DY[i];
            if (in(nf, nr) && b[nr * 8 + nf] == sgn * K) return true;
        }
        for (int d = 0; d < 8; d++) {
            int nf = f + DX[d], nr = r + DY[d];
            while (in(nf, nr)) {
                int p = b[nr * 8 + nf];
                if (p != 0) {
                    if (p * sgn > 0) {
                        int t = Math.abs(p);
                        if (t == Q || (d < 4 && t == R) || (d >= 4 && t == B)) return true;
                    }
                    break;
                }
                nf += DX[d];
                nr += DY[d];
            }
        }
        return false;
    }

    public boolean inCheck(boolean white) {
        int k = kingSquare(white);
        return k >= 0 && attacked(k, !white);
    }

    public boolean inCheck() { return inCheck(whiteToMove); }

    /** Does {@code piece} standing on {@code from} attack {@code to}, given occupancy {@code occ}? */
    public static boolean pieceAttacks(int piece, int from, int to, long occ) {
        int type = Math.abs(piece);
        int ff = file(from), fr = rank(from), tf = file(to), tr = rank(to);
        int dx = tf - ff, dy = tr - fr;
        switch (type) {
            case P:
                return Math.abs(dx) == 1 && dy == (piece > 0 ? 1 : -1);
            case N:
                return (Math.abs(dx) == 1 && Math.abs(dy) == 2) || (Math.abs(dx) == 2 && Math.abs(dy) == 1);
            case K:
                return (dx != 0 || dy != 0) && Math.abs(dx) <= 1 && Math.abs(dy) <= 1;
            default:
                break;
        }
        boolean straight = dx == 0 || dy == 0;
        boolean diag = Math.abs(dx) == Math.abs(dy);
        if (dx == 0 && dy == 0) return false;
        if (type == R && !straight) return false;
        if (type == B && !diag) return false;
        if (type == Q && !straight && !diag) return false;
        int sx = Integer.signum(dx), sy = Integer.signum(dy);
        int f = ff + sx, r = fr + sy;
        while (f != tf || r != tr) {
            if ((occ >>> (r * 8 + f) & 1L) != 0) return false;
            f += sx;
            r += sy;
        }
        return true;
    }

    public long occupancy() {
        long o = 0;
        for (int i = 0; i < 64; i++) if (b[i] != 0) o |= 1L << i;
        return o;
    }

    /** Bitboard of every piece (both colours) that attacks {@code sq} when only squares in {@code occ} are occupied. */
    public long attackersTo(int sq, long occ) {
        long res = 0;
        for (int i = 0; i < 64; i++) {
            if ((occ >>> i & 1L) == 0 || b[i] == 0 || i == sq) continue;
            if (pieceAttacks(b[i], i, sq, occ)) res |= 1L << i;
        }
        return res;
    }

    // ------------------------------------------------------------------ move generation

    private void genPseudo(IntList out) {
        boolean w = whiteToMove;
        for (int from = 0; from < 64; from++) {
            int piece = b[from];
            if (piece == 0 || (piece > 0) != w) continue;
            int type = Math.abs(piece);
            int f = file(from), r = rank(from);
            switch (type) {
                case P: {
                    int dir = w ? 1 : -1;
                    int startRank = w ? 1 : 6, promoRank = w ? 7 : 0;
                    int nr = r + dir;
                    if (nr >= 0 && nr < 8) {
                        int to = nr * 8 + f;
                        if (b[to] == 0) {
                            addPawn(out, from, to, nr == promoRank);
                            if (r == startRank && b[(r + 2 * dir) * 8 + f] == 0) out.add(move(from, (r + 2 * dir) * 8 + f));
                        }
                        for (int df = -1; df <= 1; df += 2) {
                            int nf = f + df;
                            if (nf < 0 || nf > 7) continue;
                            int t = nr * 8 + nf;
                            if ((b[t] != 0 && (b[t] > 0) != w) || t == ep) {
                                if (t == ep && b[t] != 0) continue;
                                addPawn(out, from, t, nr == promoRank);
                            }
                        }
                    }
                    break;
                }
                case N:
                    for (int i = 0; i < 8; i++) {
                        int nf = f + KN_DX[i], nr = r + KN_DY[i];
                        if (!in(nf, nr)) continue;
                        int t = nr * 8 + nf;
                        if (b[t] == 0 || (b[t] > 0) != w) out.add(move(from, t));
                    }
                    break;
                case K:
                    for (int i = 0; i < 8; i++) {
                        int nf = f + K_DX[i], nr = r + K_DY[i];
                        if (!in(nf, nr)) continue;
                        int t = nr * 8 + nf;
                        if (b[t] == 0 || (b[t] > 0) != w) out.add(move(from, t));
                    }
                    genCastles(out, from, w);
                    break;
                default: {
                    int d0 = type == B ? 4 : 0, d1 = type == R ? 4 : 8;
                    for (int d = d0; d < d1; d++) {
                        int nf = f + DX[d], nr = r + DY[d];
                        while (in(nf, nr)) {
                            int t = nr * 8 + nf;
                            if (b[t] == 0) out.add(move(from, t));
                            else {
                                if ((b[t] > 0) != w) out.add(move(from, t));
                                break;
                            }
                            nf += DX[d];
                            nr += DY[d];
                        }
                    }
                }
            }
        }
    }

    private void addPawn(IntList out, int from, int to, boolean promo) {
        if (promo) {
            out.add(move(from, to, Q));
            out.add(move(from, to, R));
            out.add(move(from, to, B));
            out.add(move(from, to, N));
        } else out.add(move(from, to));
    }

    private void genCastles(IntList out, int from, boolean w) {
        if (w && from == 4 && b[4] == K) {
            if ((castle & WK) != 0 && b[7] == R && b[5] == 0 && b[6] == 0
                    && !attacked(4, false) && !attacked(5, false) && !attacked(6, false)) out.add(move(4, 6));
            if ((castle & WQ) != 0 && b[0] == R && b[1] == 0 && b[2] == 0 && b[3] == 0
                    && !attacked(4, false) && !attacked(3, false) && !attacked(2, false)) out.add(move(4, 2));
        } else if (!w && from == 60 && b[60] == -K) {
            if ((castle & BK) != 0 && b[63] == -R && b[61] == 0 && b[62] == 0
                    && !attacked(60, true) && !attacked(61, true) && !attacked(62, true)) out.add(move(60, 62));
            if ((castle & BQ) != 0 && b[56] == -R && b[57] == 0 && b[58] == 0 && b[59] == 0
                    && !attacked(60, true) && !attacked(59, true) && !attacked(58, true)) out.add(move(60, 58));
        }
    }

    public int[] legalMoves() {
        IntList pseudo = new IntList();
        genPseudo(pseudo);
        IntList legal = new IntList();
        boolean mover = whiteToMove;
        for (int i = 0; i < pseudo.size(); i++) {
            int m = pseudo.get(i);
            Position q = play(m);
            int k = q.kingSquare(mover);
            if (k >= 0 && !q.attacked(k, !mover)) legal.add(m);
        }
        return legal.toArray();
    }

    /** Applies a (pseudo-)legal move and returns the new position. Does not validate. */
    public Position play(int m) {
        Position q = copy();
        int from = from(m), to = to(m), promo = promo(m);
        int piece = b[from];
        int type = Math.abs(piece);
        int sgn = piece > 0 ? 1 : -1;
        int captured = b[to];
        q.ep = -1;
        q.b[from] = 0;
        if (type == P) {
            if (to == ep && captured == 0 && file(from) != file(to)) {
                q.b[sgn > 0 ? to - 8 : to + 8] = 0;
            }
            if (Math.abs(to - from) == 16) q.ep = (from + to) / 2;
            if (promo != 0) piece = sgn * promo;
        }
        q.b[to] = piece;
        if (type == K && Math.abs(to - from) == 2) {
            if (to > from) { q.b[to - 1] = q.b[to + 1]; q.b[to + 1] = 0; }
            else { q.b[to + 1] = q.b[to - 2]; q.b[to - 2] = 0; }
        }
        q.castle = castle & ~(CASTLE_MASK[from] | CASTLE_MASK[to]);
        q.whiteToMove = !whiteToMove;
        q.halfmove = (type == P || captured != 0) ? 0 : halfmove + 1;
        q.fullmove = fullmove + (whiteToMove ? 0 : 1);
        return q;
    }

    /** Passes the move (null move). Returns null when that would be illegal (side to move is in check). */
    public Position nullMove() {
        if (inCheck()) return null;
        Position q = copy();
        q.whiteToMove = !whiteToMove;
        q.ep = -1;
        return q;
    }

    public boolean isCheckmate() { return inCheck() && legalMoves().length == 0; }

    public boolean isStalemate() { return !inCheck() && legalMoves().length == 0; }

    /** Finds the legal move matching a UCI string like "e2e4" or "e7e8q", or -1. */
    public int parseUci(String s) {
        if (s == null || s.length() < 4) return -1;
        int from = sqFromName(s.substring(0, 2)), to = sqFromName(s.substring(2, 4));
        if (from < 0 || to < 0) return -1;
        int promo = 0;
        if (s.length() >= 5) promo = "?pnbrqk".indexOf(Character.toLowerCase(s.charAt(4)));
        if (promo < 0) promo = 0;
        for (int m : legalMoves()) {
            if (from(m) == from && to(m) == to && promo(m) == promo) return m;
        }
        return -1;
    }

    public boolean isCapture(int m) {
        int from = from(m), to = to(m);
        if (b[to] != 0) return true;
        return Math.abs(b[from]) == P && to == ep && file(from) != file(to);
    }

    // ------------------------------------------------------------------ static exchange evaluation

    /**
     * Static exchange evaluation of a move: expected material result (in VALUE units) for the mover when both
     * sides keep capturing on the destination square with their cheapest attackers.
     */
    public int see(int m) {
        int from = from(m), to = to(m);
        int mover = b[from];
        boolean white = mover > 0;
        int[] gain = new int[40];
        int d = 0;
        long occ = occupancy();
        int capturedValue = 0;
        if (b[to] != 0) capturedValue = VALUE[Math.abs(b[to])];
        else if (Math.abs(mover) == P && to == ep && file(from) != file(to)) {
            capturedValue = VALUE[P];
            occ &= ~(1L << (white ? to - 8 : to + 8));
        }
        int promo = promo(m);
        int occupant = promo != 0 ? promo : Math.abs(mover);
        if (promo != 0) capturedValue += VALUE[promo] - VALUE[P];
        gain[0] = capturedValue;
        occ &= ~(1L << from);
        boolean side = !white;
        while (true) {
            long att = attackersTo(to, occ);
            int best = -1, bestVal = Integer.MAX_VALUE;
            for (int i = 0; i < 64; i++) {
                if ((att >>> i & 1L) == 0) continue;
                if ((b[i] > 0) != side) continue;
                int v = VALUE[Math.abs(b[i])];
                if (v < bestVal) { bestVal = v; best = i; }
            }
            if (best < 0 || d >= 38) break;
            d++;
            gain[d] = VALUE[occupant] - gain[d - 1];
            occupant = Math.abs(b[best]);
            occ &= ~(1L << best);
            side = !side;
        }
        while (d > 0) {
            gain[d - 1] = -Math.max(-gain[d - 1], gain[d]);
            d--;
        }
        return gain[0];
    }

    // ------------------------------------------------------------------ SAN

    public String toSan(int m) {
        int from = from(m), to = to(m);
        int piece = b[from];
        int type = Math.abs(piece);
        StringBuilder sb = new StringBuilder();
        boolean capture = isCapture(m);
        if (type == K && Math.abs(to - from) == 2) {
            sb.append(to > from ? "O-O" : "O-O-O");
        } else {
            if (type == P) {
                if (capture) sb.append((char) ('a' + file(from)));
            } else {
                sb.append("?PNBRQK".charAt(type));
                boolean sameFile = false, sameRank = false, ambiguous = false;
                for (int o : legalMoves()) {
                    int of = from(o);
                    if (of == from || to(o) != to || b[of] != piece) continue;
                    ambiguous = true;
                    if (file(of) == file(from)) sameFile = true;
                    if (rank(of) == rank(from)) sameRank = true;
                }
                if (ambiguous) {
                    if (!sameFile) sb.append((char) ('a' + file(from)));
                    else if (!sameRank) sb.append((char) ('1' + rank(from)));
                    else sb.append(sqName(from));
                }
            }
            if (capture) sb.append('x');
            sb.append(sqName(to));
            if (promo(m) != 0) sb.append('=').append("?PNBRQK".charAt(promo(m)));
        }
        Position q = play(m);
        if (q.inCheck()) sb.append(q.legalMoves().length == 0 ? '#' : '+');
        return sb.toString();
    }

    // ------------------------------------------------------------------ perft (tests)

    public long perft(int depth) {
        if (depth == 0) return 1;
        int[] moves = legalMoves();
        if (depth == 1) return moves.length;
        long n = 0;
        for (int m : moves) n += play(m).perft(depth - 1);
        return n;
    }
}
