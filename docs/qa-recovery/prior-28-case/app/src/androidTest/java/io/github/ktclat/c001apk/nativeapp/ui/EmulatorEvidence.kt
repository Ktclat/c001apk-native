// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import io.github.ktclat.c001apk.nativeapp.applyNativeSystemBarAppearance
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertTrue

/** Original compositor pixels from running Android, never a Canvas/Robolectric render. */
internal object EmulatorEvidence {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val directory get() = File(instrumentation.targetContext.getExternalFilesDir(null), "independent-emulator-qa").apply { mkdirs() }
    fun configureWindow(activity: ComponentActivity, dark: Boolean) {
        instrumentation.runOnMainSync {
            activity.enableEdgeToEdge()
            // Match MainActivity's manifest policy so IME insets reach the fixture host.
            activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            applyNativeSystemBarAppearance(activity.window, dark)
        }
    }
    fun capture(name: String, semantics: String = "", expectedText: String? = null,
        expectedPackage: String = "io.github.ktclat.c001apk.nativeapp") {
        instrumentation.waitForIdleSync()
        val automation = instrumentation.uiAutomation
        val started = android.os.SystemClock.uptimeMillis()
        val trace = StringBuilder("Capture gate: expectedPackage=$expectedPackage expectedText=$expectedText; minimum post-transition interval1500ms; three unchanged full compositor frames100ms apart, excluding ONLY top-right system clock for stability comparison. Saved PNG is ORIGINAL, unmodified full screenshot.\n")
        var previous: IntArray? = null
        var previousWidth = 0
        var stable = 0
        var checkpoint = ""
        var last: Bitmap? = null
        while (android.os.SystemClock.uptimeMillis() - started < 35_000) {
            val root = automation.rootInActiveWindow
            val lines = mutableListOf<String>()
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null || depth > 30 || lines.size >= 2000) return
                val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                lines += "${node.packageName} ${node.className} text=${node.text} desc=${node.contentDescription} bounds=$bounds"
                for (i in 0 until node.childCount) collect(node.getChild(i), depth+1)
            }
            collect(root)
            checkpoint = lines.joinToString("\n")
            // API26 can leave its accessibility active-root cache empty after a Compose
            // dialog is dismissed. Never accept null by itself. Only synthetic hosts with
            // no accessibility-text checkpoint may use the independently queried current
            // WindowManager focus, and it must name the exact production ComponentActivity.
            // Non-null wrong-package roots (including Android ANR dialogs) still fail.
            val focus = if (root == null && expectedText == null &&
                expectedPackage == "io.github.ktclat.c001apk.nativeapp") {
                val windows = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("dumpsys window windows")
                ).bufferedReader().use { it.readText() }
                val focusLine = windows.lineSequence().firstOrNull { it.trimStart().startsWith("mCurrentFocus=") }.orEmpty()
                checkpoint += "\nAccessibility root NULL; independently observed WindowManager:\n$windows"
                focusLine
            } else ""
            val exactFixtureFocus = Regex("mCurrentFocus=Window\\{[^\\n]* u[0-9]+ io\\.github\\.ktclat\\.c001apk\\.nativeapp/androidx\\.activity\\.ComponentActivity\\}")
                .containsMatchIn(focus)
            val expectedWindow = if (root == null) exactFixtureFocus else
                root.packageName?.toString() == expectedPackage && (expectedText == null || checkpoint.contains(expectedText))
            val bitmap = requireNotNull(automation.takeScreenshot()) { "Android UiAutomation screenshot unavailable" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val prior = previous
            var changed = 0
            if (prior != null && prior.size == pixels.size && previousWidth == bitmap.width) {
                // Observed API26 clock bounds at720px were[635,0][704,48]; right-aligned at other test widths.
                // Ignore only that clock rectangle, not app content, system icons or cursors.
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    if (y < 48 && x in (bitmap.width - 85) until (bitmap.width - 16)) continue
                    val index = y*bitmap.width+x
                    if (prior[index] != pixels[index]) changed++
                }
                stable = if (changed == 0 && expectedWindow) stable + 1 else 0
            } else stable = 0
            val elapsed = android.os.SystemClock.uptimeMillis()-started
            trace.append("elapsedMs=$elapsed expectedWindow=$expectedWindow rootNull=${root == null} exactFixtureWindowFocus=$exactFixtureFocus changedOutsideClock=$changed stableComparisons=$stable focus=$focus\n")
            last?.recycle(); last = bitmap
            if (expectedWindow && elapsed >= 1500 && stable >= 2) break
            previous = pixels; previousWidth = bitmap.width
            android.os.SystemClock.sleep(100)
        }
        text("$name-capture-trace.txt", trace.toString()+"\nForeground accessibility checkpoint:\n"+checkpoint)
        val bitmap = requireNotNull(last)
        try {
            val accepted = stable >= 2 && android.os.SystemClock.uptimeMillis()-started >= 1500
            val fileName = if (accepted) "$name.png" else "$name-diagnostic-failed.png"
            File(directory, fileName).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(accepted) { "Compositor/checkpoint did not settle; inspect capture trace and diagnostic-failed original for $name" }
        } finally { bitmap.recycle() }
        text("$name.txt", "Actual API${android.os.Build.VERSION.SDK_INT} original emulator compositor screenshot. Fixture/production scope per test; not live-service acceptance.\n" + semantics)
    }
    /** Targeted system-bar evidence only; unrelated content captures need not pay this cost. */
    fun windowDump(name: String): String {
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys window windows")
        ).bufferedReader().use { it.readText() }
        text("$name-windowmanager.txt", dump)
        return dump
    }
    fun text(name: String, value: String) { File(directory, name).writeText(value) }
    fun asset(name: String): ByteArray = instrumentation.context.assets.open(name).use { it.readBytes() }
}

/** Every requested image is intercepted locally, and no chain.proceed exists. */
internal class EmulatorFixtureImages(context: Context) : AutoCloseable {
    val loaded = ConcurrentHashMap.newKeySet<String>()
    val unexpected = ConcurrentHashMap.newKeySet<String>()
    val loader = ImageLoader.Builder(context).components { add(object : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val url = chain.request.data.toString()
            val name = url.removePrefix("https://fixture.invalid/")
            if (!url.startsWith("https://fixture.invalid/") || !Regex("icon-([1-9]|10)\\.png").matches(name)) {
                unexpected += url
                return ErrorResult(null, chain.request, IllegalArgumentException("Image network denied"))
            }
            val bytes = EmulatorEvidence.asset("icon-grid/$name")
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            loaded += url
            return SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
        }
    }) }.build()
    override fun close() { loader.shutdown(); assertTrue("No unallowlisted images: $unexpected", unexpected.isEmpty()) }
}
