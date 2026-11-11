package com.raillink.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlin.math.min

/** True on Fire TV, Android TV and other devices without a touchscreen. */
fun isTvDevice(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    if (ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = context.packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature("amazon.hardware.fire_tv") ||
        !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

/** The on-screen pointer drawn over the website on TVs. */
class CursorView(context: Context) : View(context) {
    var cx = 0f
    var cy = 0f
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#009B8E"); style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }

    override fun onDraw(canvas: Canvas) {
        val s = density
        val arrow = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy + 22 * s)
            lineTo(cx + 6 * s, cy + 17 * s)
            lineTo(cx + 10 * s, cy + 26 * s)
            lineTo(cx + 14 * s, cy + 24 * s)
            lineTo(cx + 10 * s, cy + 15.5f * s)
            lineTo(cx + 17 * s, cy + 15.5f * s)
            close()
        }
        canvas.save(); canvas.translate(2 * s, 2 * s); canvas.drawPath(arrow, shadow); canvas.restore()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
    }
}

/**
 * Lets a TV remote drive the website: arrows move the pointer (and scroll at the
 * edges), OK clicks, media buttons control the video.
 */
class TvRemote(private val webView: WebView, private val cursor: CursorView) {

    private val density = webView.resources.displayMetrics.density
    private var placed = false

    fun show(visible: Boolean) {
        cursor.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && !placed && webView.width > 0) {
            cursor.cx = webView.width / 2f
            cursor.cy = webView.height / 2f
            placed = true
            cursor.invalidate()
        }
    }

    /** Returns true if the key was used. */
    fun handle(event: KeyEvent, fullscreen: View?): Boolean {
        val code = event.keyCode

        // Media buttons work everywhere, including full-screen video.
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    js("var v=document.querySelector('video');if(v){v.paused?v.play():v.pause();}")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN) js("var v=document.querySelector('video');if(v)v.currentTime+=10;")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_DOWN) js("var v=document.querySelector('video');if(v)v.currentTime-=10;")
                return true
            }
        }

        // In full-screen video: OK = play/pause, left/right = skip 10 seconds.
        if (fullscreen != null) {
            if (event.action != KeyEvent.ACTION_DOWN) return isNavKey(code)
            return when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    js("var v=document.querySelector('video');if(v){v.paused?v.play():v.pause();}"); true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { js("var v=document.querySelector('video');if(v)v.currentTime+=10;"); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { js("var v=document.querySelector('video');if(v)v.currentTime-=10;"); true }
                else -> false
            }
        }

        if (!isNavKey(code)) return false
        if (!placed) show(true)

        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) tap()
                return true
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true

        // Speed up while the button is held.
        val step = (8 + min(event.repeatCount, 20) * 3) * density
        var dx = 0f
        var dy = 0f
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
        }
        val w = webView.width.toFloat()
        val h = webView.height.toFloat()
        val margin = 4 * density
        val nx = cursor.cx + dx
        val ny = cursor.cy + dy

        // At the edge of the screen, scroll the page instead.
        val scrollAmount = (h / 3).toInt()
        when {
            ny < margin -> { cursor.cy = margin; scrollPage(0, -scrollAmount) }
            ny > h - margin -> { cursor.cy = h - margin; scrollPage(0, scrollAmount) }
            else -> cursor.cy = ny
        }
        when {
            nx < margin -> cursor.cx = margin
            nx > w - margin -> cursor.cx = w - margin
            else -> cursor.cx = nx
        }
        cursor.invalidate()
        return true
    }

    private fun isNavKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun tap() {
        val x = cursor.cx
        val y = cursor.cy
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun scrollPage(dx: Int, dy: Int) {
        // Scroll whatever is under the pointer if it scrolls, otherwise the whole page.
        val x = cursor.cx / density
        val y = cursor.cy / density
        js(
            "(function(){var e=document.elementFromPoint($x,$y);" +
                "while(e&&e!==document.body){var s=getComputedStyle(e);" +
                "if(/(auto|scroll)/.test(s.overflowY)&&e.scrollHeight>e.clientHeight){e.scrollBy($dx,${dy / density});return;}" +
                "e=e.parentElement;}" +
                "window.scrollBy($dx,${dy / density});})()"
        )
    }

    private fun js(code: String) = webView.evaluateJavascript(code, null)
}
