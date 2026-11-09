package app.cheessee.brain;

import app.cheessee.core.IntList;
import app.cheessee.core.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the tactical ideas one side could play next: checks, mate-in-one, free captures, forks, pins and skewers.
 * Ideas that belong to the same move are merged into one threat (so a knight move that is both a check and a fork
 * becomes a single "CHECK+FORK" arrow), and only the strongest idea per piece is kept.
 */
public final class Threats {
    public static final int MATE = 1, CHECK = 2, HANG = 4, FORK = 8, PIN = 16, SKEWER = 32, DISCOVERED = 64;
    /** Threats weaker than this are not worth an arrow (e.g. grabbing a defended pawn). */
    public static final int MIN_SHOW = 100;

    private static final int[] DX = {0, 1, 0, -1, 1, 1, -1, -1};
    private static final int[] DY = {1, 0, -1, 0, 1, -1, -1, 1};

    public static final class Threat {
        public int flags, from, to, score;
        /** Squares the idea is aimed at (forked pieces, pinned piece + what is behind it, ...). */
        public int[] targets = new int[0];
        public boolean byWhite;

        public boolean has(int f) { return (flags & f) != 0; }

        /** Short tags for the arrow, most important first, e.g. ["MATE"] or ["CHECK", "FORK"]. */
        public List<String> tags() {
            List<String> t = new ArrayList<>();
            if (has(MATE)) t.add("MATE");
            if (has(CHECK)) t.add(has(DISCOVERED) ? "DISC" : "CHECK");
            if (has(FORK)) t.add("FORK");
            if (has(PIN)) t.add("PIN");
            if (has(SKEWER)) t.add("SKEWER");
            if (has(HANG)) t.add("FREE");
            return t;
        }
    }

    /**
     * What could {@code attackerWhite} do next? If it is the other side's turn, the attacker is given a free move
     * (a "null move") so the question is "what are they threatening if I do nothing?".
     */
    public static List<Threat> find(Position pos, boolean attackerWhite, int max) {
        List<Threat> none = new ArrayList<>();
        Position p = pos;
        if (p.whiteToMove != attackerWhite) {
            p = p.nullMove();
            if (p == null) return none;
        }
        boolean defWhite = !attackerWhite;
        Map<Integer, Threat> bestPerPiece = new HashMap<>();

        for (int m : p.legalMoves()) {
            int from = Position.from(m), to = Position.to(m);
            Position q = p.play(m);
            int see = p.see(m);
            boolean capture = p.isCapture(m);
            int moved = q.b[to];
            int movedType = Math.abs(moved);
            boolean safe = see >= 0;

            Threat t = new Threat();
            t.byWhite = attackerWhite;
            t.from = from;
            t.to = to;
            IntList targets = new IntList();

            boolean check = q.inCheck();
            boolean mate = check && q.legalMoves().length == 0;
            if (mate) {
                t.flags |= MATE;
                t.score += 1000;
            } else if (check && safe) {
                t.flags |= CHECK;
                t.score += 120;
                int ks = q.kingSquare(defWhite);
                if (ks >= 0 && !Position.pieceAttacks(moved, to, ks, q.occupancy())) t.flags |= DISCOVERED;
            }
            if (capture && see > 0) {
                t.flags |= HANG;
                t.score += 40 + see / 3;
                targets.add(to);
            }
            if (safe && !mate) {
                IntList fork = forkTargets(q, to, attackerWhite);
                if (fork.size() >= 2) {
                    t.flags |= FORK;
                    t.score += 150;
                    for (int i = 0; i < fork.size(); i++) {
                        targets.add(fork.get(i));
                        t.score += Position.VALUE[Math.min(Math.abs(q.b[fork.get(i)]), Position.Q)] / 20;
                    }
                }
                if (movedType == Position.B || movedType == Position.R || movedType == Position.Q) {
                    lineTactics(p, q, to, movedType, attackerWhite, t, targets);
                }
            }
            if (t.flags == 0) continue;
            t.targets = targets.toArray();
            Threat old = bestPerPiece.get(from);
            if (old == null || t.score > old.score) bestPerPiece.put(from, t);
        }

        List<Threat> all = new ArrayList<>();
        for (Threat t : bestPerPiece.values()) if (t.score >= MIN_SHOW) all.add(t);
        Collections.sort(all, (a, b) -> b.score != a.score ? Integer.compare(b.score, a.score) : Integer.compare(a.from, b.from));
        return all.size() > max ? new ArrayList<>(all.subList(0, max)) : all;
    }

    /** Pieces of {@code white} (not king, not pawns) that no friendly piece protects. */
    public static List<Integer> loose(Position p, boolean white) {
        List<Integer> res = new ArrayList<>();
        for (int s = 0; s < 64; s++) {
            int x = p.b[s];
            if (x == 0 || (x > 0) != white) continue;
            int t = Math.abs(x);
            if (t == Position.K || t == Position.P) continue;
            if (!p.attacked(s, white)) res.add(s);
        }
        return res;
    }

    /** Squares the moved piece (now on {@code to}) attacks that make it a fork. */
    private static IntList forkTargets(Position q, int to, boolean attackerWhite) {
        IntList res = new IntList();
        int moved = q.b[to];
        int mt = Math.abs(moved);
        if (mt == Position.K) return res;
        long occ = q.occupancy();
        int vm = Position.VALUE[mt];
        for (int s = 0; s < 64; s++) {
            int x = q.b[s];
            if (x == 0 || s == to || (x > 0) == attackerWhite) continue;
            if (!Position.pieceAttacks(moved, to, s, occ)) continue;
            int tx = Math.abs(x);
            int vx = Position.VALUE[tx];
            boolean worth = tx == Position.K || vx > vm || (vx >= 300 && !q.attacked(s, !attackerWhite));
            if (worth) res.add(s);
        }
        return res;
    }

    /** Pins and skewers created by the slider that just landed on {@code to}. */
    private static void lineTactics(Position before, Position q, int to, int movedType, boolean attackerWhite,
                                    Threat t, IntList targets) {
        int tf = Position.file(to), tr = Position.rank(to);
        int d0 = movedType == Position.B ? 4 : 0;
        int d1 = movedType == Position.R ? 4 : 8;
        int vMoved = Position.VALUE[movedType];
        for (int d = d0; d < d1; d++) {
            int s1 = -1, s2 = -1;
            int f = tf + DX[d], r = tr + DY[d];
            while (f >= 0 && f < 8 && r >= 0 && r < 8) {
                int s = r * 8 + f;
                if (q.b[s] != 0) {
                    if (s1 < 0) s1 = s;
                    else { s2 = s; break; }
                }
                f += DX[d];
                r += DY[d];
            }
            if (s1 < 0 || s2 < 0) continue;
            int x = q.b[s1], y = q.b[s2];
            if ((x > 0) == attackerWhite || (y > 0) == attackerWhite) continue;
            int tx = Math.abs(x), ty = Math.abs(y);
            int vx = Position.VALUE[tx], vy = Position.VALUE[ty];
            if (alreadyAimedAlongLine(before, s1, d, attackerWhite)) continue;

            if (tx == Position.K) {
                if (vy >= 300) { t.flags |= SKEWER; t.score += 140; targets.add(s1); targets.add(s2); }
            } else if (ty == Position.K) {
                if (vx >= 300) { t.flags |= PIN; t.score += 160; targets.add(s1); targets.add(s2); }
            } else if (vy > vx && vx >= 300) {
                t.flags |= PIN;
                t.score += 110;
                targets.add(s1);
                targets.add(s2);
            } else if (vx > vy && vy >= 300 && (vx > vMoved || !q.attacked(s1, !attackerWhite))) {
                t.flags |= SKEWER;
                t.score += 140;
                targets.add(s1);
                targets.add(s2);
            }
        }
    }

    /** Was an attacking slider already lined up on {@code sq} along direction d (so the idea is not new)? */
    private static boolean alreadyAimedAlongLine(Position before, int sq, int d, boolean attackerWhite) {
        int f = Position.file(sq) - DX[d], r = Position.rank(sq) - DY[d];
        while (f >= 0 && f < 8 && r >= 0 && r < 8) {
            int z = before.b[r * 8 + f];
            if (z != 0) {
                if ((z > 0) != attackerWhite) return false;
                int tz = Math.abs(z);
                return tz == Position.Q || (d < 4 && tz == Position.R) || (d >= 4 && tz == Position.B);
            }
            f -= DX[d];
            r -= DY[d];
        }
        return false;
    }

    private Threats() {}
}
