package dev.udemytv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Small mouse-pointer arrow. The view itself is tiny and is moved with translationX/Y,
 * so moving it never repaints the page underneath (keeps scrolling and video smooth).
 * Its tip is at the view's top-left corner.
 */
class CursorView(context: Context) : View(context) {

    // ~30 px tall on a 1080p screen, scaled for other resolutions.
    private val s = resources.displayMetrics.heightPixels / 1080f * 1.15f
    private val pad = 3f * s

    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 22f); lineTo(5.6f, 17.2f); lineTo(9.6f, 25.6f)
        lineTo(13.2f, 24f); lineTo(9.3f, 15.8f); lineTo(16.5f, 15.6f); close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.3f; strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }

    val sizeW = ((17f + 6f) * s + pad).toInt()
    val sizeH = ((26f + 6f) * s + pad).toInt()

    init {
        isFocusable = false
        isClickable = false
        setLayerType(LAYER_TYPE_HARDWARE, null) // drawn once, then only moved
    }

    /** Place the arrow tip at (x, y) in parent coordinates. */
    fun place(x: Float, y: Float) {
        translationX = x - pad
        translationY = y - pad
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(sizeW, sizeH)

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(pad + 1.5f * s, pad + 2f * s)
        canvas.scale(s, s)
        canvas.drawPath(arrow, shadow)
        canvas.restore()
        canvas.save()
        canvas.translate(pad, pad)
        canvas.scale(s, s)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }
}
