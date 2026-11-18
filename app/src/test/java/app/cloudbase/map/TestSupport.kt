package app.cloudbase.map

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

/** A 256x256 PNG; [pixel] gives the ARGB colour of each pixel. */
fun pngBytes(pixel: (x: Int, y: Int) -> Int): ByteArray {
    val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    for (y in 0 until 256) for (x in 0 until 256) bmp.setPixel(x, y, pixel(x, y))
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        .also { bmp.recycle() }
}

fun solidPng(color: Int): ByteArray = pngBytes { _, _ -> color }

fun TileStore.put(source: TileSource, z: Int, x: Int, y: Int, bytes: ByteArray) {
    val f = file(source, z, x, y)
    f.parentFile!!.mkdirs()
    f.writeBytes(bytes)
}

/** Polls [cond] for up to [timeoutMs]; the loaders are real background threads. */
fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(10)
    }
    return cond()
}

val RED = Color.rgb(255, 0, 0)
val BLUE = Color.rgb(0, 0, 255)
