package app.cheessee.overlay

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper

/** Keeps a live copy of the screen (via MediaProjection) and hands out the newest frame as ARGB ints. */
class ScreenGrabber(private val projection: MediaProjection, val w: Int, val h: Int, dpi: Int) {
    class Frame(val px: IntArray, val w: Int, val h: Int)

    private val reader: ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
    private var display: VirtualDisplay? = null
    private var last: Frame? = null
    private var padded: Bitmap? = null

    init {
        // Android 14+ requires a callback to be registered before the virtual display is created.
        projection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        display = projection.createVirtualDisplay(
            "cheessee", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    /** Newest frame, or the previous one if the screen has not changed since. Null before the first frame. */
    fun grab(): Frame? {
        val img = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return last
        try {
            val plane = img.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            var bmp = padded
            if (bmp == null || bmp.width != rowPixels) {
                bmp = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
                padded = bmp
            }
            bmp!!.copyPixelsFromBuffer(plane.buffer)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            last = Frame(px, w, h)
        } finally {
            img.close()
        }
        return last
    }

    fun release() {
        try {
            display?.release()
            reader.close()
            projection.stop()
        } catch (_: Exception) {
        }
    }
}
