package app.cheessee.engine

import android.content.Context
import app.cheessee.brain.Line
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Talks to the Stockfish program (shipped as libstockfish.so) over the UCI text protocol.
 * All analysis calls are blocking: call them from a background thread.
 */
class Stockfish(private val ctx: Context) {
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private val inbox = LinkedBlockingQueue<String>()
    private var multiPv = 1

    fun file(): File = File(ctx.applicationInfo.nativeLibraryDir, "libstockfish.so")

    fun available(): Boolean = file().exists()

    @Synchronized
    fun start(classic: Boolean): Boolean {
        if (proc != null) return true
        val f = file()
        if (!f.exists()) return false
        try {
            f.setExecutable(true)
            val p = ProcessBuilder(f.absolutePath).redirectErrorStream(true).start()
            proc = p
            writer = p.outputStream.bufferedWriter()
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { inbox.put(it) }
                } catch (_: Exception) {
                }
            }.also { it.isDaemon = true }.start()
            send("uci")
            if (!waitFor("uciok", 10000)) return false
            send("setoption name Threads value 2")
            send("setoption name Hash value 16")
            send("setoption name Use NNUE value ${!classic}")
            send("isready")
            return waitFor("readyok", 20000)
        } catch (e: Exception) {
            proc = null
            return false
        }
    }

    /**
     * Analyses a position and returns up to [pvCount] lines (best first), or null when there is nothing to
     * analyse (game over) or the engine is not running.
     */
    fun analyze(fen: String, pvCount: Int, depth: Int, moveMs: Int): Array<Line>? {
        if (proc == null) return null
        inbox.clear()
        if (pvCount != multiPv) {
            send("setoption name MultiPV value $pvCount")
            multiPv = pvCount
        }
        send("position fen $fen")
        send("go depth $depth movetime $moveMs")
        val best = HashMap<Int, Line>()
        val deadline = System.currentTimeMillis() + moveMs + 5000
        var gotBest = false
        while (System.currentTimeMillis() < deadline) {
            val s = inbox.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (s.startsWith("bestmove")) {
                gotBest = true
                break
            }
            if (s.startsWith("info ")) {
                val l = Line.parse(s) ?: continue
                val old = best[l.multipv]
                if (old == null || l.depth >= old.depth) best[l.multipv] = l
            }
        }
        if (!gotBest) {
            send("stop")
            waitFor("bestmove", 2000)
        }
        if (best.isEmpty()) return null
        return best.values.sortedBy { it.multipv }.toTypedArray()
    }

    @Synchronized
    private fun send(cmd: String) {
        try {
            writer?.apply {
                write(cmd)
                newLine()
                flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun waitFor(prefix: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val s = inbox.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (s.startsWith(prefix)) return true
        }
        return false
    }

    @Synchronized
    fun quit() {
        try {
            send("quit")
            proc?.destroy()
        } catch (_: Exception) {
        }
        proc = null
    }
}
