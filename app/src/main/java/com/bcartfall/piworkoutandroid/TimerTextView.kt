package com.bcartfall.piworkoutandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

enum class States {
    STOPPED,
    RUNNING,
}

class TimerTextView(ctx: Context, attrs: AttributeSet) : AppCompatTextView(ctx, attrs) {

    private var startTime = 0L
    private var state = States.STOPPED
    private var timerStrokeColor = Color.argb(255,0, 0, 0)
    private var timerTextColor = Color.WHITE
    private var timerShadowColor = Color.argb(128,0, 0, 0)
    private var timerShadowRadius = 8F
    private var timerShadowX = 8F
    private var timerShadowY = 8F
    private var timerStrokeWidth = 16F
    private val timerDefault : String

    init {
        startTime = System.currentTimeMillis()
        state = States.STOPPED
        setTextColor(Color.WHITE)
        timerDefault = ctx.getString(R.string.timer_default)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setPaintToOutline()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Prepare to draw outline
     */
    private fun setPaintToOutline()  {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = timerStrokeWidth
        super.setTextColor(timerStrokeColor)
        super.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    /**
     * Prepare to draw regular text
     */
    private fun setPaintToRegular() {
        val paint: Paint = paint
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        super.setTextColor(timerTextColor)
        super.setShadowLayer(timerShadowRadius, timerShadowX, timerShadowY, timerShadowColor)
    }

    /**
     * Update time and draw border
     */
    override fun onDraw(canvas: Canvas?) {
        // update timer

        text = timerDefault
        if (state == States.STOPPED) {
            text = timerDefault
        } else {
            val now = System.currentTimeMillis()
            val elapsed = now - startTime

            val millisecondsFormatted = (elapsed % 1000).toString().padStart(3, '0')
            val seconds = elapsed / 1000
            val secondsFormatted = (seconds % 60).toString().padStart(2, '0')
            val minutes = seconds / 60
            val minutesFormatted = (minutes % 60).toString().padStart(2, '0')
            val hours = minutes / 60

            val formatted = if (hours > 0) {
                val hoursFormatted = (minutes / 60).toString().padStart(2, '0')
                "$hoursFormatted:$minutesFormatted:$secondsFormatted"
            } else {
                "$minutesFormatted:$secondsFormatted.$millisecondsFormatted"
            }
            text = formatted
        }

        setPaintToOutline()
        super.onDraw(canvas)

        setPaintToRegular()
        super.onDraw(canvas)
    }

    /**
     * Restart time
     */
    fun restart() {
        state = States.RUNNING
        startTime = System.currentTimeMillis()
    }

    fun stop() {
        state = States.STOPPED
    }
}