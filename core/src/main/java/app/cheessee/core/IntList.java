package app.cheessee.core;

import java.util.Arrays;

/** Tiny growable int list (avoids boxing in move generation). */
public final class IntList {
    private int[] a = new int[64];
    private int n;

    public void add(int v) {
        if (n == a.length) a = Arrays.copyOf(a, n * 2);
        a[n++] = v;
    }

    public int get(int i) { return a[i]; }
    public int size() { return n; }
    public boolean isEmpty() { return n == 0; }
    public void clear() { n = 0; }

    public boolean contains(int v) {
        for (int i = 0; i < n; i++) if (a[i] == v) return true;
        return false;
    }

    public int[] toArray() { return Arrays.copyOf(a, n); }
}
