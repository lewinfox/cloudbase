package app.cloudbase.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import app.cloudbase.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the launcher icon as Android draws it, to check the vector drawables match docs/logo.svg. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconTest {
    @Test fun render() {
        val ctx = RuntimeEnvironment.getApplication()
        val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for (id in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
            ctx.getDrawable(id)!!.apply { setBounds(0, 0, 432, 432); draw(c) }
        }
        val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "icon.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
