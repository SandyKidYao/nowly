package com.nowly.app

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onLast
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Optional render evidence. Business assertions must not depend on PixelCopy. */
internal fun captureEvidence(compose: AndroidComposeTestRule<*, MainActivity>, name: String) {
    if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
    val directory = compose.activity.getExternalFilesDir("mvp-evidence")!!
    directory.mkdirs()
    val target = File(directory, "$name.png")
    val diagnostic = File(directory, "$name.error.txt")
    var failure: Throwable? = null
    repeat(2) {
        try {
            compose.waitForIdle()
            val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            diagnostic.delete()
            return
        } catch (error: AssertionError) {
            failure = error
        }
    }
    // Remove stale evidence from an older run, and record the capture failure.
    target.delete()
    diagnostic.writeText(failure?.stackTraceToString().orEmpty())
    Log.w("NowlyTest", "Optional screenshot unavailable: $name", failure)
}
