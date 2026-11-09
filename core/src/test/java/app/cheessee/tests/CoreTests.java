package app.cheessee.tests;

import app.cheessee.core.Position;

/** Move generator correctness: perft counts from the chess programming wiki + a few rule checks. */
public final class CoreTests {
    static void perft(String name, String fen, long... expected) {
        Position p = Position.fromFen(fen);
        for (int d = 1; d <= expected.length; d++) {
            Check.eq("perft " + name + " d" + d, expected[d - 1], p.perft(d));
        }
    }

    public static void run() {
        Check.section("core: perft");
        perft("start", Position.START_FEN, 20, 400, 8902, 197281);
        perft("kiwipete", "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 48, 2039, 97862);
        perft("pos3", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 14, 191, 2812, 43238);
        perft("pos4", "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 6, 264, 9467);
        perft("pos5", "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 44, 1486, 62379);
        perft("pos6", "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", 46, 2079, 89890);

        Check.section("core: fen/san/uci/see");
        Position s = Position.fromFen(Position.START_FEN);
        Check.eq("fen roundtrip", Position.START_FEN, s.toFen());
        Check.eq("san e4", "e4", s.toSan(s.parseUci("e2e4")));
        Check.eq("san Nf3", "Nf3", s.toSan(s.parseUci("g1f3")));
        Position m = Position.fromFen("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4");
        Check.eq("san mate Qxf7#", "Qxf7#", m.toSan(m.parseUci("h5f7")));
        Check.ok("mate detected", m.play(m.parseUci("h5f7")).isCheckmate());
        Position c = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Check.eq("castle O-O", "O-O", c.toSan(c.parseUci("e1g1")));
        Check.eq("castle O-O-O", "O-O-O", c.toSan(c.parseUci("e1c1")));
        Position dis = Position.fromFen("4k3/8/8/8/8/8/K7/R6R w - - 0 1");
        Check.eq("disambiguation Rad1", "Rad1", dis.toSan(dis.parseUci("a1d1")));
        Position ep = Position.fromFen("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3");
        Check.ok("en passant legal", ep.parseUci("e5f6") >= 0);
        Check.eq("ep removes pawn", 0, ep.play(ep.parseUci("e5f6")).b[Position.sq(5, 4)]);
        Position pr = Position.fromFen("8/P6k/8/8/8/8/8/K7 w - - 0 1");
        Check.eq("promotion count", 4, countFrom(pr, Position.sq(0, 6)));
        Check.eq("san promo", "a8=Q", pr.toSan(pr.parseUci("a7a8q")));

        // SEE: pawn takes defended pawn = even trade, rook takes defended pawn loses
        Position see1 = Position.fromFen("4k3/8/3p4/4p3/3P4/8/8/4K3 w - - 0 1");
        Check.eq("see pxp defended = even trade", 0, see1.see(see1.parseUci("d4e5")));
        Position see2 = Position.fromFen("4k3/8/2p5/3p4/8/8/3R4/3K4 w - - 0 1");
        Check.ok("see Rxd5 loses exchange", see2.see(see2.parseUci("d2d5")) < 0);
        Position see3 = Position.fromFen("4k3/8/8/3q4/8/8/3R4/3K4 w - - 0 1");
        Check.ok("see Rxq wins", see3.see(see3.parseUci("d2d5")) >= 900);
    }

    private static int countFrom(Position p, int from) {
        int n = 0;
        for (int mv : p.legalMoves()) if (Position.from(mv) == from) n++;
        return n;
    }
}
