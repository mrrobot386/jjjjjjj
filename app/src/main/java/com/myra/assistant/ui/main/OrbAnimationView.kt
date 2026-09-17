package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OrbState {
        IDLE,
        LISTENING,
        SPEAKING,
        THINKING,
        ACTIVE
    }

    private var currentState: OrbState = OrbState.IDLE
    private var amplitude: Float = 0f

    // Animators
    private var pulseAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null
    private var waveAnimator: ValueAnimator? = null
    private var thinkingAnimator: ValueAnimator? = null

    // Animation values
    private var pulseScale: Float = 1.0f
    private var glowAlpha: Int = 160
    private var rotationAngle: Float = 0f
    private var waveOffset: Float = 0f
    private var thinkingAngle: Float = 0f

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val thinkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringRect = RectF()
    private val thinkingRect = RectF()

    init {
        startAnimators()
    }

    private fun startAnimators() {
        // 1. Pulse Animator (1500ms)
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                pulseScale = value
                val fraction = it.animatedFraction
                glowAlpha = (120 + (100 * sin(fraction * Math.PI)).toInt()).coerceIn(120, 220)
                invalidate()
            }
            start()
        }

        // 2. Rotation Animator
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // 3. Wave Animator
        waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                waveOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // 4. Thinking Animator
        thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                thinkingAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setState(state: OrbState) {
        if (currentState != state) {
            currentState = state
            invalidate()
        }
    }

    fun getState(): OrbState = currentState

    fun setAmplitude(rms: Float) {
        amplitude = rms.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) / 2f) * 0.48f

        // Dynamic scale based on state and amplitude
        val ampBonus = if (currentState == OrbState.SPEAKING || currentState == OrbState.LISTENING) {
            amplitude * 0.25f
        } else {
            0f
        }
        val currentRadius = baseRadius * (pulseScale + ampBonus)

        // Color palettes per state
        val (colorStart, colorEnd) = when (currentState) {
            OrbState.IDLE -> Pair(Color.parseColor("#B71C1C"), Color.parseColor("#880E4F"))
            OrbState.LISTENING, OrbState.ACTIVE -> Pair(Color.parseColor("#FF1744"), Color.parseColor("#D500F9"))
            OrbState.SPEAKING -> Pair(Color.parseColor("#E040FB"), Color.parseColor("#FF1744"))
            OrbState.THINKING -> Pair(Color.parseColor("#40C4FF"), Color.parseColor("#00B0FF"))
        }

        // LAYER 1: Radial Glow (1.6x radius)
        val glowRadius = currentRadius * 1.6f
        val glowShader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(glowAlpha, Color.red(colorStart), Color.green(colorStart), Color.blue(colorStart)),
                Color.argb(glowAlpha / 3, Color.red(colorEnd), Color.green(colorEnd), Color.blue(colorEnd)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = glowShader
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // LAYER 2: Core Orb (Sphere illusion)
        val coreShader = RadialGradient(
            cx - currentRadius * 0.3f,
            cy - currentRadius * 0.35f,
            currentRadius * 1.3f,
            intArrayOf(
                colorStart,
                colorEnd,
                Color.parseColor("#150005")
            ),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.shader = coreShader
        canvas.drawCircle(cx, cy, currentRadius, corePaint)

        // LAYER 3: 3 Rotating Rings (dashed arcs)
        if (currentState != OrbState.IDLE) {
            ringPaint.color = Color.argb(180, Color.red(colorStart), Color.green(colorStart), Color.blue(colorStart))
            for (i in 1..3) {
                val r = currentRadius * (1.1f + i * 0.16f)
                ringRect.set(cx - r, cy - r, cx + r, cy + r)
                val ringSpeed = if (i % 2 == 0) -1f else 1f
                val angle = rotationAngle * ringSpeed * (1f + i * 0.3f)
                canvas.drawArc(ringRect, angle, 160f, false, ringPaint)
                canvas.drawArc(ringRect, angle + 200f, 120f, false, ringPaint)
            }
        }

        // LAYER 4: Wave Rings (Sine waves, amplitude-reactive)
        if (currentState == OrbState.LISTENING || currentState == OrbState.SPEAKING || currentState == OrbState.ACTIVE) {
            wavePaint.color = Color.argb(200, Color.red(colorEnd), Color.green(colorEnd), Color.blue(colorEnd))
            val waveCount = 3
            val points = 36
            for (w in 0 until waveCount) {
                val waveRadiusBase = currentRadius * (1.05f + w * 0.12f)
                var prevX = 0f
                var prevY = 0f
                var firstX = 0f
                var firstY = 0f

                for (p in 0..points) {
                    val theta = (p.toFloat() / points) * 2 * PI.toFloat()
                    val waveAmp = (4f + amplitude * 18f) * sin(theta * 4 + waveOffset + w)
                    val r = waveRadiusBase + waveAmp
                    val x = cx + r * cos(theta)
                    val y = cy + r * sin(theta)

                    if (p == 0) {
                        firstX = x
                        firstY = y
                        prevX = x
                        prevY = y
                    } else {
                        canvas.drawLine(prevX, prevY, x, y, wavePaint)
                        prevX = x
                        prevY = y
                    }
                }
                canvas.drawLine(prevX, prevY, firstX, firstY, wavePaint)
            }
        }

        // LAYER 5: Thinking Arc (2 arcs spinning, only in thinking state)
        if (currentState == OrbState.THINKING) {
            thinkingPaint.color = Color.parseColor("#40C4FF")
            val tr = currentRadius * 1.25f
            thinkingRect.set(cx - tr, cy - tr, cx + tr, cy + tr)
            canvas.drawArc(thinkingRect, thinkingAngle, 70f, false, thinkingPaint)
            canvas.drawArc(thinkingRect, thinkingAngle + 180f, 70f, false, thinkingPaint)
        }

        // LAYER 6: Particles (12 dots orbiting, active or speaking)
        if (currentState == OrbState.ACTIVE || currentState == OrbState.SPEAKING) {
            val particleCount = 12
            particlePaint.color = Color.argb(230, Color.red(colorStart), Color.green(colorStart), Color.blue(colorStart))
            for (i in 0 until particleCount) {
                val pAngle = ((i.toFloat() / particleCount) * 2 * PI + (rotationAngle * PI / 180f) * 1.5).toFloat()
                val orbitDist = currentRadius * (1.25f + (i % 3) * 0.12f + amplitude * 0.2f)
                val px = cx + orbitDist * cos(pAngle)
                val py = cy + orbitDist * sin(pAngle)
                val dotSize = 2.5f + (i % 3) * 1.2f + amplitude * 2f
                canvas.drawCircle(px, py, dotSize, particlePaint)
            }
        }

        // LAYER 7: Inner Highlight (white radial gradient, top-left)
        val hlRadius = currentRadius * 0.5f
        val hlShader = RadialGradient(
            cx - currentRadius * 0.35f,
            cy - currentRadius * 0.4f,
            hlRadius,
            intArrayOf(Color.argb(160, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        highlightPaint.shader = hlShader
        canvas.drawCircle(cx - currentRadius * 0.35f, cy - currentRadius * 0.4f, hlRadius, highlightPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        rotationAnimator?.cancel()
        waveAnimator?.cancel()
        thinkingAnimator?.cancel()
    }
}
