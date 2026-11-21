package dev.udemytv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Mouse-pointer arrow drawn on top of the page. (x, y) is the arrow tip. */
class CursorView(context: Context) : View(context) {

    var px = 0f
        private set
    var py = 0f
        private set

    private val scale = resources.displayMetrics.density * 1.5f
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 22f); lineTo(6f, 17f); lineTo(10.5f, 26f)
        lineTo(14f, 24.5f); lineTo(9.5f, 15.5f); lineTo(17f, 15.5f); close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; style = Paint.Style.FILL }

    init {
        isFocusable = false
        isClickable = false
    }

    fun moveTo(x: Float, y: Float) {
        px = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        py = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    fun center() = moveTo(width / 2f, height / 2f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0) center() else moveTo(px * w / oldw, py * h / oldh)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(px + 2f, py + 3f)
        canvas.scale(scale, scale)
        canvas.drawPath(arrow, shadow)
        canvas.restore()
        canvas.save()
        canvas.translate(px, py)
        canvas.scale(scale, scale)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }
}
