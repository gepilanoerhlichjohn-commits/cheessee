package app.cheessee.tests;

import app.cheessee.core.Position;
import app.cheessee.vision.ScreenReader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.BasicStroke;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Renders fake Lichess-like screenshots and checks the screen reader gets the position back. */
public final class VisionTests {
    static final int W = 600, H = 900, BX = 44, BY = 150, SQ = 64;

    static BufferedImage render(int[] screen, int[] tinted, int glowSquare) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(22, 21, 18));
        g.fillRect(0, 0, W, H);
        Font font = new Font("DejaVu Sans", Font.PLAIN, 54);
        for (int i = 0; i < 64; i++) {
            int r = i / 8, c = i % 8;
            boolean light = (r + c) % 2 == 0;
            Color bg = light ? new Color(240, 217, 181) : new Color(181, 136, 99);
            for (int t : tinted) if (t == i) bg = light ? new Color(205, 210, 106) : new Color(170, 162, 58);
            g.setColor(bg);
            g.fillRect(BX + c * SQ, BY + r * SQ, SQ, SQ);
            if (i == glowSquare) {
                g.setPaint(new RadialGradientPaint(BX + c * SQ + SQ / 2f, BY + r * SQ + SQ / 2f, SQ * 0.55f,
                        new float[]{0f, 0.25f, 0.89f}, new Color[]{new Color(255, 0, 0, 255), new Color(231, 0, 0, 255), new Color(169, 0, 0, 0)}));
                g.fillRect(BX + c * SQ, BY + r * SQ, SQ, SQ);
            }
            int p = screen[i];
            if (p == 0) continue;
            int type = Math.abs(p);
            String glyph = new String(Character.toChars(0x265F + 0)) ;
            // U+265A king, 265B queen, 265C rook, 265D bishop, 265E knight, 265F pawn
            int cp = new int[]{0, 0x265F, 0x265E, 0x265D, 0x265C, 0x265B, 0x265A}[type];
            glyph = new String(Character.toChars(cp));
            GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), glyph);
            Rectangle2D b = gv.getVisualBounds();
            Shape s = AffineTransform.getTranslateInstance(BX + c * SQ + SQ / 2.0 - b.getCenterX(), BY + r * SQ + SQ / 2.0 - b.getCenterY()).createTransformedShape(gv.getOutline());
            g.setColor(p > 0 ? new Color(250, 250, 250) : new Color(45, 45, 45));
            g.fill(s);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2f));
            g.draw(s);
        }
        g.dispose();
        return img;
    }

    static int[] pixels(BufferedImage img) { return img.getRGB(0, 0, W, H, null, 0, W); }

    /** Draws a translucent thick arrow-like bar into an overlay array and composites it onto the screenshot. */
    static int[] overlayBar(int[] px, int x0, int y0, int x1, int y1, int rgb, int alpha) {
        int[] ov = new int[W * H];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                ov[y * W + x] = alpha << 24 | rgb;
                int p = px[y * W + x];
                double a = alpha / 255.0;
                int r = (int) Math.round((rgb >> 16 & 255) * a + (p >> 16 & 255) * (1 - a));
                int gg = (int) Math.round((rgb >> 8 & 255) * a + (p >> 8 & 255) * (1 - a));
                int b = (int) Math.round((rgb & 255) * a + (p & 255) * (1 - a));
                px[y * W + x] = 0xFF000000 | r << 16 | gg << 8 | b;
            }
        }
        return ov;
    }

    static int[] screenFrom(Position p, boolean whiteBottom) {
        int[] s = new int[64];
        for (int sq = 0; sq < 64; sq++) s[ScreenReader.squareToScreen(sq, whiteBottom)] = p.b[sq];
        return s;
    }

    public static void run() {
        Check.section("vision: synthetic screenshots");
        Position start = Position.fromFen(Position.START_FEN);
        boolean[] orient = {true, false};
        for (boolean whiteBottom : orient) {
            String tag = whiteBottom ? "white-bottom" : "black-bottom";
            BufferedImage img = render(screenFrom(start, whiteBottom), new int[0], -1);
            int[] px = pixels(img);
            int[] found = ScreenReader.findBoard(px, W, H);
            Check.ok(tag + " board found", found != null && Math.abs(found[0] - BX) <= 3 && Math.abs(found[1] - BY) <= 3 && Math.abs(found[2] - 8 * SQ) <= 6);
            Check.eq(tag + " tracking y", BY, ScreenReader.findY(px, W, H, BX, 8 * SQ));
            ScreenReader.Templates t = ScreenReader.learn(px, null, W, BX, BY, 8 * SQ);
            Check.ok(tag + " learned from start", t != null);
            if (t == null) continue;
            ScreenReader.Templates t2 = ScreenReader.Templates.decode(t.encode());
            Check.ok(tag + " templates survive save/load", t2 != null);

            // a busy middlegame, last move highlighted, king in check glow, overlay arrow across the board
            Position mid = Position.fromFen("r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P1B2/2PBPN2/PP1N1PPP/R2QK2R b KQ - 0 8");
            int from = ScreenReader.squareToScreen(Position.sqFromName("e2"), whiteBottom);
            int to = ScreenReader.squareToScreen(Position.sqFromName("e3"), whiteBottom);
            int[] scr = screenFrom(mid, whiteBottom);
            BufferedImage img2 = render(scr, new int[]{from, to}, -1);
            int[] px2 = pixels(img2);
            ScreenReader.Reading rd = ScreenReader.read(px2, null, W, BX, BY, 8 * SQ, t2);
            Check.ok(tag + " reads midgame", Arrays.equals(scr, rd.screen));
            Check.eq(tag + " last move from", from, rd.lastFrom);
            Check.eq(tag + " last move to", to, rd.lastTo);

            int[] ov = overlayBar(px2, BX + 20, BY + 100, BX + 400, BY + 135, 0x15781B, 190);
            ScreenReader.Reading rd2 = ScreenReader.read(px2, ov, W, BX, BY, 8 * SQ, t2);
            Check.ok(tag + " reads through translucent overlay", Arrays.equals(scr, rd2.screen));

            Position chk = Position.fromFen("4k3/8/8/8/8/8/8/R3K2q w - - 0 1");
            int[] scr3 = screenFrom(chk, whiteBottom);
            int ksq = ScreenReader.squareToScreen(Position.sqFromName("e1"), whiteBottom);
            ScreenReader.Reading rd3 = ScreenReader.read(pixels(render(scr3, new int[0], ksq)), null, W, BX, BY, 8 * SQ, t2);
            Check.ok(tag + " king in check (red glow)", Arrays.equals(scr3, rd3.screen));

            // orientation helper + board conversion
            Check.eq(tag + " orientation guess", whiteBottom, ScreenReader.guessWhiteAtBottom(scr));
            Check.ok(tag + " toBoard", Arrays.equals(mid.b, ScreenReader.toBoard(scr, whiteBottom)));
        }
    }
}
