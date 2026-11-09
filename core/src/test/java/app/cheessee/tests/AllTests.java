package app.cheessee.tests;

/** Entry point: runs every self-test. Exit code 1 when anything fails (used by CI). */
public final class AllTests {
    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        CoreTests.run();
        BrainTests.run();
        VisionTests.run();
        Check.summary();
        System.out.println("time " + (System.currentTimeMillis() - t0) + " ms");
    }
}
