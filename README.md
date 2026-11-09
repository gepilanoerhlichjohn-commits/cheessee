# 🧀 Cheessee

A private chess practice overlay for Android that floats on top of **Lichess**:

| What | How it looks |
|---|---|
| **Hint arrows** (thin, always straight) | dark green = best · light green = good/excellent · yellow = playable but worse (`TRAP` = opponent must find an only move) · blue = brilliant sacrifice |
| **Threat arrows** (dotted) | red = what *they* threaten · purple = what *you* threaten · tags: `MATE` `CHECK` `FORK` `PIN` `SKEWER` `FREE`, merged when one move does several things |
| **Your arrows** (thick, desktop style) | drag between squares; knight moves bend in an L; tap a square to highlight it; tap a piece to see its moves as dots |
| **Eval** | slim eval bar on the board edge + number like `+0.8` / `#3` |
| **Stickers** | brilliant `!!` · great `!` · best ★ · excellent · good · inaccuracy `?!` · mistake `?` · miss ✗ · blunder `??` on the last move's square |

Everything comes from **Stockfish** running on your phone. Hints are real engine lines; Cheessee only decides the colour.

> **Practice tool.** Use it for analysis, puzzles, bot games and learning. Using engine help in live/rated games against real people is cheating and against Lichess's rules.

## Get the app (about 15 minutes, no PC needed)

1. Make a free account at github.com, then create a **new empty repository** called `cheessee`.
2. Upload this whole project into it. On a phone this is fiddly (hidden `.github` folder), so the easy way is:
   tell Claude your GitHub username and repo name and it can push the files for you.
   On a computer: unzip, then in the repo choose **Add file → Upload files** and drag everything in (including the `.github` folder).
3. Open the repo's **Actions** tab. A run called *Build Cheessee APK* starts by itself (about 10–15 min). Wait for the green ✓.
   - First time only: if GitHub asks, press the green button to enable workflows.
4. Open the repo's **Releases** (right side of the repo page) → **Cheessee (latest build)** → download **Cheessee.apk**.
5. Open the APK to install. Android will ask to allow installs from your browser/Files app: allow it.

If the run shows a red ✗, open it, copy the error text from the failed step and send it to Claude. It will fix it.

## First run on your phone

1. Open **Cheessee** → allow *Draw over other apps* and *Notifications*.
2. Tap **Start Cheessee** → *Start now*. If Android asks what to share, choose **Entire screen**.
3. Open **Lichess** on a **new game or analysis board with nothing moved** (all 32 pieces on their start squares).
4. Tap the floating 🧀 bubble → **✛**. A cyan frame appears. Drag/pinch until its grid sits exactly on the board, then tap **✓**.
   Cheessee learns your piece style from the start position (works with any piece set / board colour). You only redo this if you change piece set or board theme.
5. Park the 🧀 bubble outside the board (above it works well). Play or analyse. Arrows appear a second or two after each move.

Bubble buttons: **✎** draw mode · **●** arrow colour · **🗑** clear · **⇅** which side is at the bottom (Auto / White / Black) · **✛** calibrate · **⚙** settings · **■** stop.

## Settings that matter on a slow phone (Infinix Smart 8)

* **Analysis strength**: *Fast* (depth ~10), *Balanced* (default, ~2 s per move), *Deep* (~5 s). Deeper = more accurate hints, slower and warmer phone.
* **Faster classic engine**: turns Stockfish's neural evaluation off. Quicker, weaker. Try it if arrows take too long.
* Turn off **Trap check** if you want fewer extra searches.

## How it reads the screen

1. Android screen capture gives frames; Cheessee finds the 8×8 board and learns each piece's silhouette + brightness from the start position.
2. Each square is classified by silhouette (flood-fill), so it still works when Lichess highlights squares, shows legal-move dots, or a king is in check. Cheessee's own translucent arrows are mathematically subtracted before reading.
3. Whose turn it is comes from Lichess's last-move highlight (otherwise it assumes it is your turn). The move that was played is found by trying all legal moves, which is how stickers know what you just did.

Limits: castling rights are guessed from king/rook home squares; Lichess's own drawn arrows can confuse the reader; the "Book" sticker is not included (needs an openings list).

## Troubleshooting

* **"Stockfish missing" in the app**: the engine step failed in the build. Open the run on GitHub → *Build Stockfish* step → send Claude the log. (You can also run the workflow manually with `stockfish_tag` = `sf_16.1` or `sf_14.1`.)
* **"Can't read the board"**: Lichess isn't showing the board, the frame is off by a few pixels (redo ✛), or you changed piece set (redo ✛).
* **Hints for the wrong side**: press ⇅ until the right colour is at the bottom.
* **Arrows lag**: use *Fast* strength or the classic engine.

## For developers

```
core/   pure Java: chess rules, hints, labels, threats, screen reader  (self-tests: ./tools/run-tests.sh)
app/    Android (Kotlin): overlay, screen capture, Stockfish bridge, UI
.github/workflows/build-apk.yml   builds Stockfish with the NDK + the APK
```
