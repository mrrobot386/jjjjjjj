package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import kotlin.math.sin
import kotlin.random.Random

// 1. WAVEFORM VIEW
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 20
    private val currentHeights = FloatArray(barCount) { 0.1f }
    private val targetHeights = FloatArray(barCount) { 0.1f }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barRect = RectF()
    private var isAnimating = false
    private var animator: ValueAnimator? = null
    private var currentAmplitude: Float = 0f

    init {
        startAnimation()
    }

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 50L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                // Lerp current towards target
                for (i in 0 until barCount) {
                    val target = if (currentAmplitude > 0.05f) {
                        val variation = (sin(i * 0.6 + System.currentTimeMillis() * 0.01).toFloat() + 1f) / 2f
                        (currentAmplitude * 0.8f + variation * 0.2f * currentAmplitude).coerceIn(0.08f, 1.0f)
                    } else {
                        0.08f
                    }
                    targetHeights[i] = target
                    currentHeights[i] += (targetHeights[i] - currentHeights[i]) * 0.3f
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        isAnimating = false
        animator?.cancel()
        animator = null
        for (i in 0 until barCount) {
            currentHeights[i] = 0.08f
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        currentAmplitude = rms.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val totalWidth = width.toFloat()
        val totalHeight = height.toFloat()
        if (totalWidth <= 0 || totalHeight <= 0) return

        val totalSpacing = totalWidth * 0.25f
        val spacing = totalSpacing / (barCount - 1)
        val barWidth = (totalWidth - totalSpacing) / barCount
        val maxBarHeight = totalHeight * 0.9f
        val centerY = totalHeight / 2f

        for (i in 0 until barCount) {
            val h = (currentHeights[i] * maxBarHeight).coerceAtLeast(4f)
            val left = i * (barWidth + spacing)
            val right = left + barWidth
            val top = centerY - (h / 2f)
            val bottom = centerY + (h / 2f)

            barRect.set(left, top, right, bottom)
            val alphaVal = (150 + (currentHeights[i] * 105)).toInt().coerceIn(150, 255)
            barPaint.color = Color.argb(alphaVal, 255, 23, 68) // #FF1744
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}

// 2. CHAT MESSAGE
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// 3. CHAT ADAPTER
class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_MYRA = 2
    }

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        // Deduplication rule: If incoming MYRA message is identical to the last MYRA message, skip
        if (!message.isUser) {
            val lastMyra = lastMyraText()
            if (lastMyra != null && lastMyra.trim().equals(message.text.trim(), ignoreCase = true)) {
                return
            }
        }
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun lastMyraText(): String? {
        return messages.findLast { !it.isUser }?.text
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_MYRA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_chat_myra, parent, false)
            MyraViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.text.text = msg.text
        } else if (holder is MyraViewHolder) {
            holder.text.text = msg.text
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.userMessageText)
    }

    class MyraViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.myraMessageText)
    }
}
