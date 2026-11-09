package app.cheessee.tests;

/** Tiny assertion helper so the self-tests need no external libraries. */
public final class Check {
    public static int passed = 0, failed = 0;

    public static void eq(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL  " + name + "\n      expected: " + expected + "\n      actual:   " + actual);
        }
    }

    public static void ok(String name, boolean cond) {
        if (cond) passed++;
        else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }

    public static void section(String s) { System.out.println("== " + s); }

    public static void summary() {
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private Check() {}
}
