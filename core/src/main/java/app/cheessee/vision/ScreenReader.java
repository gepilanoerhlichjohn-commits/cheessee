package app.cheessee.vision;

import java.util.Arrays;

/**
 * Reads a chess board off a screenshot (int ARGB pixels). Works with any piece set / board theme because it LEARNS
 * the pieces from the starting position once ("calibration"), then recognises pieces by silhouette (flood-fill mask)
 * + brightness. Our own translucent overlay can be undone exactly when its pixels are supplied.
 *
 * Board indexing used here: "screen" index = row * 8 + col, row 0 = top of the screen.
 */
public final class ScreenReader {
    private static final int G = 32;          // analysis grid per square
    private static final int D = 16;          // descriptor grid
    private static final int FG_DIST = 40;    // colour distance from background that counts as "piece"
    private static final double MIN_AREA = 0.09;

    /** Learned piece look. Index = type(1..6) + (black ? 6 : 0). */
    public static final class Templates {
        final float[][] mask = new float[13][D * D];
        final float[][] lum = new float[13][D * D];

        public String encode() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 12; i++) {
                for (float v : mask[i]) sb.append(Math.round(v * 255)).append(',');
                for (float v : lum[i]) sb.append(Math.round(v * 255)).append(',');
            }
            return sb.toString();
        }

        public static Templates decode(String s) {
            try {
                String[] t = s.split(",");
                if (t.length < 12 * 2 * D * D) return null;
                Templates tp = new Templates();
                int k = 0;
                for (int i = 1; i <= 12; i++) {
                    for (int j = 0; j < D * D; j++) tp.mask[i][j] = Integer.parseInt(t[k++]) / 255f;
                    for (int j = 0; j < D * D; j++) tp.lum[i][j] = Integer.parseInt(t[k++]) / 255f;
                }
                return tp;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public static final class Reading {
        /** Signed pieces in screen order (row 0 = top). 0 empty, +1..+6 white PNBRQK, -1..-6 black. */
        public final int[] screen = new int[64];
        /** Screen indices of the last-move highlight (from = empty square, to = occupied square), or -1. */
        public int lastFrom = -1, lastTo = -1;
        public double worst;   // worst per-square match distance (lower = more confident)
    }

    private static final class Feat {
        float[] mask = new float[D * D], lum = new float[D * D];
        boolean empty = true;
        int bgR, bgG, bgB;
        double meanLum;
    }

    // ------------------------------------------------------------------ finding the board

    /** Best top edge for a board of the given size/left edge, or -1 if nothing board-like is there. */
    public static int findY(int[] px, int w, int h, int x, int size) {
        return plateauY(px, w, h, x, size, 0, h - size, 2);
    }

    /** Guess {x, y, size} of the board: tries full width first, then narrower centred boards. */
    public static int[] findBoard(int[] px, int w, int h) {
        double best = 30;
        int[] res = null;
        for (int size = w; size >= (int) (w * 0.78); size -= Math.max(2, w / 100)) {
            int x = (w - size) / 2;
            for (int y = 0; y + size <= h; y += 3) {
                double sc = checkerScore(px, w, x, y, size);
                if (sc > best) { best = sc; res = new int[]{x, y, size}; }
            }
        }
        if (res != null) {
            refine(px, w, h, res, 12, 0.05);
            refine(px, w, h, res, 3, 0.03);
        }
        return res;
    }

    /** Joint fine-tune of {x, y, size} within +-range; moves to the middle of the best-scoring region. */
    private static void refine(int[] px, int w, int h, int[] r, int range, double inset) {
        int n = 2 * range + 1;
        double[][][] sc = new double[n][n][n];
        double best = -1e9;
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) for (int c = 0; c < n; c++) {
            int x = r[0] + a - range, y = r[1] + b - range, size = r[2] + c - range;
            if (x < 0 || y < 0 || size < 100 || x + size > w || y + size > h) { sc[a][b][c] = -1e9; continue; }
            sc[a][b][c] = checkerScore(px, w, x, y, size, inset);
            best = Math.max(best, sc[a][b][c]);
        }
        double sx = 0, sy = 0, ss = 0;
        int cnt = 0;
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) for (int c = 0; c < n; c++) {
            if (sc[a][b][c] >= best - 0.01 * Math.abs(best)) { sx += a; sy += b; ss += c; cnt++; }
        }
        if (cnt == 0) return;
        r[0] += (int) Math.round(sx / cnt) - range;
        r[1] += (int) Math.round(sy / cnt) - range;
        r[2] += (int) Math.round(ss / cnt) - range;
    }

    /** Scans tops from y0..y1 and returns the middle of the band of best-scoring positions. */
    private static int plateauY(int[] px, int w, int h, int x, int size, int y0, int y1, int step) {
        if (y1 < y0 || x < 0 || x + size > w) return -1;
        int n = (y1 - y0) / step + 1;
        double[] sc = new double[n];
        double best = -1e9;
        for (int i = 0; i < n; i++) {
            sc[i] = checkerScore(px, w, x, y0 + i * step, size);
            best = Math.max(best, sc[i]);
        }
        if (best <= 30) return -1;
        int lo = -1, hi = -1;
        for (int i = 0; i < n; i++) {
            if (sc[i] >= best - 0.02 * Math.abs(best)) { if (lo < 0) lo = i; hi = i; }
        }
        return y0 + (lo + hi) * step / 2;
    }

    /** High when squares alternate light/dark like a chessboard (piece-free corners are sampled). */
    static double checkerScore(int[] px, int w, int x, int y, int size) { return checkerScore(px, w, x, y, size, 0.12); }

    static double checkerScore(int[] px, int w, int x, int y, int size, double inset) {
        double s = size / 8.0;
        double[][] c = new double[64][3];
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                double[] acc = c[r * 8 + f];
                for (int k = 0; k < 4; k++) {
                    int cx = (int) (x + f * s + (k % 2 == 0 ? inset : 1 - inset) * s);
                    int cy = (int) (y + r * s + (k / 2 == 0 ? inset : 1 - inset) * s);
                    int p = px[cy * w + cx];
                    acc[0] += (p >> 16 & 255) / 4.0;
                    acc[1] += (p >> 8 & 255) / 4.0;
                    acc[2] += (p & 255) / 4.0;
                }
            }
        }
        double adj = 0, diag = 0;
        int na = 0, nd = 0;
        for (int r = 0; r < 7; r++) {
            for (int f = 0; f < 7; f++) {
                adj += dist(c[r * 8 + f], c[r * 8 + f + 1]) + dist(c[r * 8 + f], c[(r + 1) * 8 + f]);
                na += 2;
                diag += dist(c[r * 8 + f], c[(r + 1) * 8 + f + 1]);
                nd++;
            }
        }
        return adj / na - 2 * diag / nd;
    }

    private static double dist(double[] a, double[] b) {
        double d0 = a[0] - b[0], d1 = a[1] - b[1], d2 = a[2] - b[2];
        return Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }

    // ------------------------------------------------------------------ per-square features

    /**
     * @param ov overlay pixels (straight ARGB, same size as px) or null. Translucent overlay is divided out;
     *           nearly opaque overlay pixels are ignored.
     */
    private static Feat feature(int[] px, int[] ov, int w, double sx, double sy, double s) {
        double m = s * 0.06, span = s - 2 * m;
        int[][] rgb = new int[G * G][];
        for (int gy = 0; gy < G; gy++) {
            for (int gx = 0; gx < G; gx++) {
                int x0 = (int) (sx + m + span * gx / G), x1 = Math.max(x0 + 1, (int) (sx + m + span * (gx + 1) / G));
                int y0 = (int) (sy + m + span * gy / G), y1 = Math.max(y0 + 1, (int) (sy + m + span * (gy + 1) / G));
                double r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int i = y * w + x;
                        int p = px[i];
                        double pr = p >> 16 & 255, pg = p >> 8 & 255, pb = p & 255;
                        if (ov != null) {
                            int o = ov[i];
                            int a = o >>> 24;
                            if (a > 235) continue;
                            if (a > 2) {
                                double af = a / 255.0, k = 1 - af;
                                pr = clamp((pr - (o >> 16 & 255) * af) / k);
                                pg = clamp((pg - (o >> 8 & 255) * af) / k);
                                pb = clamp((pb - (o & 255) * af) / k);
                            }
                        }
                        r += pr; g += pg; b += pb; n++;
                    }
                }
                if (n > 0) rgb[gy * G + gx] = new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
            }
        }
        // background colour = median of the outer ring
        int[] rr = new int[G * 8], gg = new int[G * 8], bb = new int[G * 8];
        int rn = 0;
        for (int i = 0; i < G; i++) {
            for (int j = 0; j < G; j++) {
                if (i > 1 && i < G - 2 && j > 1 && j < G - 2) continue;
                int[] c = rgb[i * G + j];
                if (c == null) continue;
                rr[rn] = c[0]; gg[rn] = c[1]; bb[rn] = c[2]; rn++;
            }
        }
        Feat f = new Feat();
        if (rn == 0) return f;
        f.bgR = median(rr, rn); f.bgG = median(gg, rn); f.bgB = median(bb, rn);

        boolean[] fg = new boolean[G * G];
        for (int i = 0; i < G * G; i++) {
            int[] c = rgb[i];
            if (c == null) continue;
            int dr = c[0] - f.bgR, dg = c[1] - f.bgG, db = c[2] - f.bgB;
            boolean glow = c[0] > 120 && c[0] - Math.max(c[1], c[2]) > 70 && !(f.bgR - Math.max(f.bgG, f.bgB) > 60);
            fg[i] = !glow && dr * dr + dg * dg + db * db > FG_DIST * FG_DIST;
        }
        // flood fill the outside from the border; everything not reached (piece + enclosed insides) is "piece"
        boolean[] outside = new boolean[G * G];
        int[] stack = new int[G * G];
        int sp = 0;
        for (int i = 0; i < G; i++) {
            for (int e : new int[]{i, (G - 1) * G + i, i * G, i * G + G - 1}) {
                if (!fg[e] && !outside[e]) { outside[e] = true; stack[sp++] = e; }
            }
        }
        while (sp > 0) {
            int e = stack[--sp], ex = e % G, ey = e / G;
            if (ex > 0 && !fg[e - 1] && !outside[e - 1]) { outside[e - 1] = true; stack[sp++] = e - 1; }
            if (ex < G - 1 && !fg[e + 1] && !outside[e + 1]) { outside[e + 1] = true; stack[sp++] = e + 1; }
            if (ey > 0 && !fg[e - G] && !outside[e - G]) { outside[e - G] = true; stack[sp++] = e - G; }
            if (ey < G - 1 && !fg[e + G] && !outside[e + G]) { outside[e + G] = true; stack[sp++] = e + G; }
        }
        boolean[] mask = new boolean[G * G];
        for (int i = 0; i < G * G; i++) mask[i] = !outside[i];
        boolean[] biggest = largestComponent(mask);
        int area = 0;
        for (boolean v : biggest) if (v) area++;
        if (area < MIN_AREA * G * G) return f;   // empty square (or just a move-dot)
        f.empty = false;
        double lumSum = 0;
        int lumN = 0;
        for (int dy = 0; dy < D; dy++) {
            for (int dx = 0; dx < D; dx++) {
                int cnt = 0;
                double l = 0;
                for (int k = 0; k < 4; k++) {
                    int i = (dy * 2 + k / 2) * G + dx * 2 + k % 2;
                    if (!biggest[i]) continue;
                    cnt++;
                    int[] c = rgb[i];
                    if (c != null) l += (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255.0;
                    else l += 0.5;
                }
                f.mask[dy * D + dx] = cnt / 4f;
                f.lum[dy * D + dx] = cnt > 0 ? (float) (l / cnt) : 0.5f;
                if (cnt > 0) { lumSum += l / cnt; lumN++; }
            }
        }
        f.meanLum = lumN > 0 ? lumSum / lumN : 0.5;
        return f;
    }

    private static boolean[] largestComponent(boolean[] mask) {
        int[] label = new int[G * G];
        int next = 0, bestLabel = 0, bestSize = 0;
        int[] stack = new int[G * G];
        for (int i = 0; i < G * G; i++) {
            if (!mask[i] || label[i] != 0) continue;
            next++;
            int sp = 0, size = 0;
            stack[sp++] = i;
            label[i] = next;
            while (sp > 0) {
                int e = stack[--sp];
                size++;
                int ex = e % G, ey = e / G;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = ex + dx, ny = ey + dy;
                        if (nx < 0 || ny < 0 || nx >= G || ny >= G) continue;
                        int ni = ny * G + nx;
                        if (mask[ni] && label[ni] == 0) { label[ni] = next; stack[sp++] = ni; }
                    }
                }
            }
            if (size > bestSize) { bestSize = size; bestLabel = next; }
        }
        boolean[] out = new boolean[G * G];
        for (int i = 0; i < G * G; i++) out[i] = bestLabel != 0 && label[i] == bestLabel;
        return out;
    }

    private static int median(int[] a, int n) {
        int[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return c[n / 2];
    }

    private static double clamp(double v) { return v < 0 ? 0 : Math.min(255, v); }

    private static double distance(Feat f, float[] mask, float[] lum) {
        double d = 0;
        for (int i = 0; i < D * D; i++) {
            double dm = f.mask[i] - mask[i];
            d += dm * dm;
            if (f.mask[i] > 0.5 && mask[i] > 0.5) {
                double dl = f.lum[i] - lum[i];
                d += 0.35 * dl * dl;
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ learning + reading

    private static final int[] BACK = {Position0.R, Position0.N, Position0.B, Position0.Q, Position0.K, Position0.B, Position0.N, Position0.R};

    /** Helper constants (kept local so this package does not depend on the chess core). */
    private static final class Position0 {
        static final int P = 1, N = 2, B = 3, R = 4, Q = 5, K = 6;
    }

    private static Feat[] features(int[] px, int[] ov, int w, int bx, int by, int size) {
        double s = size / 8.0;
        Feat[] fs = new Feat[64];
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) fs[r * 8 + c] = feature(px, ov, w, bx + c * s, by + r * s, s);
        return fs;
    }

    /**
     * Learns the pieces from a screenshot of the starting position (any orientation).
     * @return templates, or null when the 32 starting pieces were not found
     */
    public static Templates learn(int[] px, int[] ov, int w, int bx, int by, int size) {
        Feat[] fs = features(px, ov, w, bx, by, size);
        for (int i = 0; i < 64; i++) {
            boolean shouldBeFull = i < 16 || i >= 48;
            if (fs[i].empty == shouldBeFull) return null;
        }
        double top = 0, bottom = 0;
        for (int i = 0; i < 16; i++) { top += fs[i].meanLum; bottom += fs[48 + i].meanLum; }
        boolean whiteBottom = bottom > top;
        Templates t = new Templates();
        int[] count = new int[13];
        for (int i = 0; i < 64; i++) {
            if (fs[i].empty) continue;
            int row = i / 8, col = i % 8;
            int file = whiteBottom ? col : 7 - col, rank = whiteBottom ? 7 - row : row;
            int type = rank == 0 || rank == 7 ? BACK[file] : Position0.P;
            boolean black = rank >= 6;
            int idx = type + (black ? 6 : 0);
            for (int k = 0; k < D * D; k++) { t.mask[idx][k] += fs[i].mask[k]; t.lum[idx][k] += fs[i].lum[k]; }
            count[idx]++;
        }
        for (int idx = 1; idx <= 12; idx++) {
            if (count[idx] == 0) return null;
            for (int k = 0; k < D * D; k++) { t.mask[idx][k] /= count[idx]; t.lum[idx][k] /= count[idx]; }
        }
        Reading check = read(px, ov, w, bx, by, size, t);
        int[] expect = new int[64];
        for (int i = 0; i < 64; i++) {
            int row = i / 8, col = i % 8;
            int file = whiteBottom ? col : 7 - col, rank = whiteBottom ? 7 - row : row;
            int v = rank == 0 ? BACK[file] : rank == 1 ? Position0.P : rank == 6 ? -Position0.P : rank == 7 ? -BACK[file] : 0;
            expect[i] = v;
        }
        return Arrays.equals(check.screen, expect) ? t : null;
    }

    /** Reads the pieces on the board. */
    public static Reading read(int[] px, int[] ov, int w, int bx, int by, int size, Templates t) {
        Feat[] fs = features(px, ov, w, bx, by, size);
        Reading r = new Reading();
        for (int i = 0; i < 64; i++) {
            if (fs[i].empty) continue;
            double best = 1e9;
            int bi = 1;
            for (int idx = 1; idx <= 12; idx++) {
                double d = distance(fs[i], t.mask[idx], t.lum[idx]);
                if (d < best) { best = d; bi = idx; }
            }
            r.worst = Math.max(r.worst, best);
            r.screen[i] = bi <= 6 ? bi : -(bi - 6);
        }
        // last-move highlight: squares whose background differs from the typical light/dark colour
        int[][] light = new int[32][], dark = new int[32][];
        int nl = 0, nd = 0;
        for (int i = 0; i < 64; i++) {
            int[] c = {fs[i].bgR, fs[i].bgG, fs[i].bgB};
            if (((i / 8) + (i % 8)) % 2 == 0) light[nl++] = c; else dark[nd++] = c;
        }
        int[] lc = medianColour(light, nl), dc = medianColour(dark, nd);
        int tinted = 0, from = -1, to = -1, occupied = 0;
        for (int i = 0; i < 64; i++) {
            int[] base = ((i / 8) + (i % 8)) % 2 == 0 ? lc : dc;
            int dr = fs[i].bgR - base[0], dg = fs[i].bgG - base[1], db = fs[i].bgB - base[2];
            if (dr * dr + dg * dg + db * db > 22 * 22) {
                tinted++;
                if (fs[i].empty) from = i; else { to = i; occupied++; }
            }
        }
        if (tinted == 2 && occupied == 1 && from >= 0) { r.lastFrom = from; r.lastTo = to; }
        return r;
    }

    private static int[] medianColour(int[][] cs, int n) {
        int[] a = new int[n], b = new int[n], c = new int[n];
        for (int i = 0; i < n; i++) { a[i] = cs[i][0]; b[i] = cs[i][1]; c[i] = cs[i][2]; }
        return new int[]{median(a, n), median(b, n), median(c, n)};
    }

    // ------------------------------------------------------------------ orientation

    /** Guess which colour is at the bottom from where the pawns are. */
    public static boolean guessWhiteAtBottom(int[] screen) {
        double w = 0, b = 0;
        int nw = 0, nb = 0;
        for (int i = 0; i < 64; i++) {
            if (screen[i] == Position0.P) { w += i / 8; nw++; }
            else if (screen[i] == -Position0.P) { b += i / 8; nb++; }
        }
        if (nw == 0 || nb == 0) return true;
        return w / nw > b / nb;
    }

    /** Screen order -> board order (a1 = 0, white's point of view). */
    public static int[] toBoard(int[] screen, boolean whiteAtBottom) {
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) out[screenToSquare(i, whiteAtBottom)] = screen[i];
        return out;
    }

    public static int screenToSquare(int idx, boolean whiteAtBottom) {
        int row = idx / 8, col = idx % 8;
        int file = whiteAtBottom ? col : 7 - col, rank = whiteAtBottom ? 7 - row : row;
        return rank * 8 + file;
    }

    /** Board square (a1 = 0) -> screen index. */
    public static int squareToScreen(int sq, boolean whiteAtBottom) {
        int file = sq & 7, rank = sq >> 3;
        int col = whiteAtBottom ? file : 7 - file, row = whiteAtBottom ? 7 - rank : rank;
        return row * 8 + col;
    }

    private ScreenReader() {}
}
