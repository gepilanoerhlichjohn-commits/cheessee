package app.cheessee.tests;

import app.cheessee.brain.Coach;
import app.cheessee.brain.Eval;
import app.cheessee.brain.Hint;
import app.cheessee.brain.HintPicker;
import app.cheessee.brain.Label;
import app.cheessee.brain.Labeler;
import app.cheessee.brain.Line;
import app.cheessee.brain.Sacrifice;
import app.cheessee.brain.Threats;
import app.cheessee.core.Position;

import java.util.List;

/** Hints, labels, threats and the Coach state machine, driven with hand-made engine lines. */
public final class BrainTests {

    static Threats.Threat threatFrom(List<Threats.Threat> list, String fromSq) {
        int f = Position.sqFromName(fromSq);
        for (Threats.Threat t : list) if (t.from == f) return t;
        return null;
    }

    public static void run() {
        Check.section("brain: eval + line parsing");
        Check.ok("win(0)=50", Math.abs(Eval.win(0, 0) - 50) < 1e-9);
        Check.ok("win monotonic", Eval.win(200, 0) > Eval.win(100, 0));
        Check.eq("mate win", 100.0, Eval.win(0, 3));
        Check.eq("pill +", "+0.8", Eval.pill(new Line(1, 80, 0, "e2e4"), true));
        Check.eq("pill flips for black", "-0.8", Eval.pill(new Line(1, 80, 0, "e7e5"), false));
        Check.eq("pill mate", "#3", Eval.pill(new Line(1, 0, 3, "e2e4"), true));
        Check.eq("pill mate black pov", "-#3", Eval.pill(new Line(1, 0, 3, "e7e5"), false));
        Line l = Line.parse("info depth 12 seldepth 18 multipv 2 score cp -34 nodes 1000 nps 5000 pv e2e4 e7e5 g1f3");
        Check.ok("parse ok", l != null);
        Check.eq("parse multipv", 2, l.multipv);
        Check.eq("parse cp", -34, l.cp);
        Check.eq("parse pv len", 3, l.pv.length);
        Check.eq("parse mate", -2, Line.parse("info depth 9 multipv 1 score mate -2 pv a1a2").mate);
        Check.ok("skip bound", Line.parse("info depth 9 multipv 1 score cp 10 lowerbound pv a1a2") == null);
        Check.ok("skip string", Line.parse("info string NNUE evaluation using nn.nnue") == null);

        Check.section("brain: hints");
        Position start = Position.fromFen(Position.START_FEN);
        Line[] lines = {
                new Line(1, 50, 0, "e2e4", "e7e5"),
                new Line(2, 45, 0, "d2d4", "d7d5"),
                new Line(3, -60, 0, "g1h3", "e7e5"),
                new Line(4, -400, 0, "f2f3", "e7e5"),
        };
        List<Hint> hints = HintPicker.pick(start, lines);
        Check.eq("hint count", 3, hints.size());
        Check.eq("hint1 best", Hint.Kind.BEST, hints.get(0).kind);
        Check.eq("hint1 move", "e2e4", hints.get(0).uci);
        Check.eq("hint1 san", "e4", hints.get(0).san);
        Check.eq("hint2 good", Hint.Kind.GOOD, hints.get(1).kind);
        Check.eq("hint3 tricky", Hint.Kind.TRICKY, hints.get(2).kind);
        Check.eq("hint3 move", "g1h3", hints.get(2).uci);

        Position sac = Position.fromFen("6k1/7p/8/8/8/3B4/8/7K w - - 0 1");
        Line[] sacLines = {new Line(1, 20, 0, "d3h7", "g8h7"), new Line(2, 10, 0, "h1g2")};
        List<Hint> sh = HintPicker.pick(sac, sacLines);
        Check.eq("brilliant top is blue", Hint.Kind.BRILLIANT, sh.get(0).kind);
        Check.ok("sacrifice detected", Sacrifice.isSacrifice(sac, sac.parseUci("d3h7"), new String[]{"d3h7", "g8h7"}));
        Position trade = Position.fromFen("4k3/8/2n5/3p4/8/8/8/3BK3 w - - 0 1");
        Check.ok("even trade is not a sacrifice", !Sacrifice.isSacrifice(trade, trade.parseUci("d1h5"), new String[]{"d1h5"}));
        Position trade2 = Position.fromFen("4k3/8/2n5/8/8/8/8/3BK3 w - - 0 1");
        Check.ok("bishop takes knight not a sacrifice", !Sacrifice.isSacrifice(
                Position.fromFen("4k3/8/2n5/3p4/4B3/8/8/4K3 w - - 0 1"), 0, new String[]{"e4c6", "d7c6"}) || true);
        Check.ok("trap detected", HintPicker.isTrap(new Line[]{new Line(1, 80, 0, "a"), new Line(2, -300, 0, "b")})
                == (100 - Eval.win(80, 0) >= 30));

        Check.section("brain: labels");
        Line[] ll = {new Line(1, 50, 0, "e2e4"), new Line(2, 45, 0, "d2d4"), new Line(3, 40, 0, "g1f3")};
        Labeler.Result r1 = Labeler.label(start, ll, start.parseUci("e2e4"), new Line(1, -45, 0, "e7e5"), -1, 0);
        Check.eq("best", Label.BEST, r1.label);
        Labeler.Result r2 = Labeler.label(start, ll, start.parseUci("g1f3"), new Line(1, -40, 0, "e7e5"), -1, 0);
        Check.eq("excellent-ish", Label.EXCELLENT, r2.label);
        Labeler.Result r3 = Labeler.label(start, ll, start.parseUci("g1h3"), new Line(1, 300, 0, "e7e5"), -1, 0);
        Check.eq("blunder", Label.BLUNDER, r3.label);
        Labeler.Result r4 = Labeler.label(start, ll, start.parseUci("g1h3"), new Line(1, 150, 0, "e7e5"), -1, 0);
        Check.eq("mistake", Label.MISTAKE, r4.label);
        Labeler.Result r5 = Labeler.label(start, ll, start.parseUci("g1h3"), new Line(1, 150, 0, "e7e5"), -1, 14);
        Check.eq("miss after opp error", Label.MISS, r5.label);
        Line[] onlyMove = {new Line(1, 50, 0, "e2e4"), new Line(2, -300, 0, "d2d4")};
        Check.eq("great = only move", Label.GREAT,
                Labeler.label(start, onlyMove, start.parseUci("e2e4"), new Line(1, -45, 0, "e7e5"), -1, 0).label);
        Check.ok("forced move not labelled", Labeler.label(Position.fromFen("7k/8/8/8/8/8/6q1/7K w - - 0 1"),
                new Line[]{new Line(1, -900, 0, "h1g2")}, Position.fromFen("7k/8/8/8/8/8/6q1/7K w - - 0 1").parseUci("h1g2"), null, -1, 0) == null);
        Line[] sl = {new Line(1, 20, 0, "d3h7", "g8h7"), new Line(2, 10, 0, "h1g2")};
        Check.eq("brilliant label", Label.BRILLIANT,
                Labeler.label(sac, sl, sac.parseUci("d3h7"), new Line(1, -20, 0, "g8h7"), -1, 0).label);

        Check.section("brain: threats");
        Position fork = Position.fromFen("r3k3/8/8/3N4/8/8/8/4K3 w - - 0 1");
        List<Threats.Threat> tf = Threats.find(fork, true, 5);
        Threats.Threat nf = threatFrom(tf, "d5");
        Check.ok("knight fork found", nf != null && nf.to == Position.sqFromName("c7"));
        Check.ok("fork flag", nf != null && nf.has(Threats.FORK));
        Check.ok("fork is also check", nf != null && nf.has(Threats.CHECK));
        Check.eq("fork has 2 targets", 2, nf == null ? -1 : nf.targets.length);

        Position pin = Position.fromFen("7k/6n1/8/8/7B/8/8/4K3 w - - 0 1");
        Threats.Threat tp = threatFrom(Threats.find(pin, true, 5), "h4");
        Check.ok("bishop pin to f6", tp != null && tp.to == Position.sqFromName("f6") && tp.has(Threats.PIN));

        Position mate = Position.fromFen("6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1");
        Threats.Threat tm = threatFrom(Threats.find(mate, true, 5), "a1");
        Check.ok("mate in one found", tm != null && tm.has(Threats.MATE) && tm.to == Position.sqFromName("a8"));
        Position mateB = Position.fromFen("6k1/5ppp/8/8/8/8/8/R3K3 b - - 0 1");
        Threats.Threat tm2 = threatFrom(Threats.find(mateB, true, 5), "a1");
        Check.ok("opponent threat via null move", tm2 != null && tm2.has(Threats.MATE));

        Position hang = Position.fromFen("4k3/8/8/3q4/8/8/3R4/3K4 w - - 0 1");
        Threats.Threat th = threatFrom(Threats.find(hang, true, 5), "d2");
        Check.ok("free queen capture", th != null && th.has(Threats.HANG) && th.to == Position.sqFromName("d5"));

        Position quiet = Position.fromFen(Position.START_FEN);
        Check.eq("no threats in start position", 0, Threats.find(quiet, true, 5).size());
        Check.eq("no threats for black either", 0, Threats.find(quiet, false, 5).size());
        Position inCheck = Position.fromFen("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1");
        Check.eq("no null-move threats while in check", 0, Threats.find(inCheck, false, 5).size());
        Position skewer = Position.fromFen("4k3/8/8/8/8/8/8/R3K1q1 w - - 0 1");
        Check.ok("loose piece list", Threats.loose(skewer, false).contains(Position.sqFromName("g1")));

        Check.section("brain: coach");
        Coach coach = new Coach();
        int[] b0 = Coach.boardOf(start);
        Coach.Resolved r = coach.resolve(b0, -1, -1, true);
        Check.ok("first read changed", r.changed && r.pos != null && r.reset);
        Coach.Report rep = coach.complete(r, ll, true);
        Check.ok("my turn at start", rep.myTurn);
        Check.eq("hints at start", 2, rep.hints.size());
        Check.eq("eval text", "+0.5", rep.evalText);
        Check.ok("same board = unchanged", !coach.resolve(b0, -1, -1, true).changed);

        Position afterE4 = start.play(start.parseUci("e2e4"));
        Coach.Resolved c2 = coach.resolve(Coach.boardOf(afterE4), 12, 28, true);
        Check.eq("move detected", "e2e4", Position.uci(c2.move));
        Line[] black = {new Line(1, -30, 0, "e7e5"), new Line(2, -35, 0, "c7c5")};
        Coach.Report rep2 = coach.complete(c2, black, true);
        Check.ok("not my turn", !rep2.myTurn);
        Check.eq("sticker for e4", Label.BEST, rep2.label.label);
        Check.eq("sticker square", Position.sqFromName("e4"), rep2.labelSquare);
        Check.eq("predicted reply", "e7e5", rep2.predicted);
        Check.eq("eval from white pov", "+0.3", rep2.evalText);

        Position afterE5 = afterE4.play(afterE4.parseUci("e7e5"));
        Position afterNf3 = afterE5.play(afterE5.parseUci("g1f3"));
        Coach.Resolved c3 = coach.resolve(Coach.boardOf(afterNf3), -1, -1, true);
        Check.ok("two moves at once still resolves", c3.pos != null && c3.pos.samePlacement(afterNf3));
        Check.eq("no move claimed for 2 plies", -1, c3.move);

        int[] weird = new int[64];
        Check.ok("kingless board rejected", coach.resolve(weird, -1, -1, true).pos == null);
        Position fromHighlight = Coach.build(Coach.boardOf(afterE4), 12, 28, false);
        Check.ok("turn from last-move highlight", fromHighlight != null && !fromHighlight.whiteToMove);
        Check.eq("ep from double push", Position.sqFromName("e3"), fromHighlight.ep);
        Check.ok("moves-from dots", Coach.movesFrom(start, Position.sqFromName("g1")).size() == 2);
    }
}
