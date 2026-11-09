package app.cheessee.brain;

/** Move-quality labels (the little stickers) with the colour and symbol each one is drawn with. */
public enum Label {
    BRILLIANT(0xFF1BACA6, "!!", "Brilliant"),
    GREAT(0xFF5C8BB0, "!", "Great"),
    BEST(0xFF96BC4B, "star", "Best"),
    EXCELLENT(0xFF81B64C, "thumb", "Excellent"),
    GOOD(0xFF95B776, "check", "Good"),
    BOOK(0xFFA88865, "book", "Book"),
    INACCURACY(0xFFF7C631, "?!", "Inaccuracy"),
    MISTAKE(0xFFE58F2A, "?", "Mistake"),
    MISS(0xFFFF7769, "x", "Miss"),
    BLUNDER(0xFFCA3431, "??", "Blunder");

    /** ARGB colour of the sticker. */
    public final int color;
    /** What to draw: "!!", "!", "?!", "?", "??" are text; "star", "thumb", "check", "book", "x" are drawn shapes. */
    public final String glyph;
    public final String title;

    Label(int color, String glyph, String title) {
        this.color = color;
        this.glyph = glyph;
        this.title = title;
    }
}
