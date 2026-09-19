package com.glass.lisn.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Limbus 桌面歌词演出视图:忠实移植 TempuraYMY0728/Limbus-Like-Lyric-Simulator effect.py
 *  逐字显现(100ms/字,进度驱动)/ 143ms 抖动插值 / 淡出行保持字号角度位置 / 随机锚点+随机角度+透视 */
class LimbusOverlayView(context: Context) : View(context) {

    data class Line(val timeMs: Long, val text: String)

    private val cream = Color.parseColor("#fffeef")
    private val gold = Color.parseColor("#d8a523")
    private val density = resources.displayMetrics.density

    private var lines: List<Line> = emptyList()
    private var artist = ""
    private var basePosMs = 0L
    private var basePosTick = 0L
    private var playing = false
    private var posChangedAt = 0L
    private var scheduled = false
    private var lastDrawn = 0L

    // 演出状态
    private var shownIdx = -1
    private var chars: CharArray = CharArray(0)
    private var charShown = 0
    private var anchorX = 0f
    private var anchorY = 0f
    private var angleDeg = 0f
    private var fontPx = 56f
    private var pPx = 0f
    private var pPy = 0f
    private var pSx = 1f

    private class Shake { var x = 0f; var y = 0f; var tx = 0f; var ty = 0f }
    private val shakes = mutableListOf<Shake>()

    private class Fading {
        lateinit var chars: CharArray
        var alpha = 255
        var x = 0f; var y = 0f; var angle = 0f; var fontPx = 0f
        var px = 0f; var py = 0f; var sx = 1f
    }
    private val fading = mutableListOf<Fading>()

    private var lastShakeTick = 0L
    private var lastFadeTick = 0L
    private var lastFrame = 0L

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val cachePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- 外部输入 ----

    fun setLyrics(newLines: List<Line>, newArtist: String) {
        lines = newLines
        artist = newArtist
        shownIdx = -1
        chars = CharArray(0)
        charShown = 0
        fading.clear()
        wake()
    }

    fun setPos(posMs: Long, isPlaying: Boolean) {
        basePosMs = posMs
        basePosTick = SystemClock.elapsedRealtime()
        playing = isPlaying
        posChangedAt = SystemClock.elapsedRealtime()
        wake()
    }

    /** 闲置停机后的唤醒入口(幂等) */
    private fun wake() {
        if (!scheduled) {
            scheduled = true
            postInvalidateOnAnimation()
        }
    }

    // ---- 过滤规则(对齐 PC 端) ----

    private val creditPatterns = listOf(
        Regex("作词"), Regex("作曲"), Regex("编曲"), Regex("制作人"), Regex("OP[：:]"), Regex("SP[：:]"),
        Regex("原唱"), Regex("翻唱"), Regex("混音"), Regex("录音"), Regex("和声"), Regex("监制"),
        Regex("统筹"), Regex("企划"), Regex("出品"), Regex("封面"), Regex("曲\\s*[：:]"), Regex("词\\s*[：:]"),
        Regex("吉他\\s*[：:]"), Regex("贝斯\\s*[：:]"), Regex("鼓\\s*[：:]"), Regex("键盘\\s*[：:]"), Regex("弦乐\\s*[：:]"),
        Regex("program(ming)?\\s*[：:]", RegexOption.IGNORE_CASE), Regex("produced\\s+by", RegexOption.IGNORE_CASE),
        Regex("written\\s+by", RegexOption.IGNORE_CASE), Regex("composed\\s+by", RegexOption.IGNORE_CASE),
        Regex("arranged\\s+by", RegexOption.IGNORE_CASE), Regex("mixed\\s+by", RegexOption.IGNORE_CASE),
        Regex("mastered\\s+by", RegexOption.IGNORE_CASE),
    )
    private val instPatterns = listOf(
        Regex("\\(inst\\.?\\)", RegexOption.IGNORE_CASE), Regex("（inst\\.?）", RegexOption.IGNORE_CASE),
        Regex("\\[inst\\.?\\]", RegexOption.IGNORE_CASE), Regex("【inst\\.?】", RegexOption.IGNORE_CASE),
        Regex("\\binst\\.?$", RegexOption.IGNORE_CASE), Regex("instrumental", RegexOption.IGNORE_CASE),
        Regex("纯音乐"), Regex("伴奏"), Regex("off\\s*vocal", RegexOption.IGNORE_CASE),
        Regex("offvocal", RegexOption.IGNORE_CASE), Regex("カラオケ"), Regex("karaoke", RegexOption.IGNORE_CASE),
    )
    private val colonCredit = Regex("^[^\\s:：]{1,6}[：:]")
    private fun isFiltered(t: String): Boolean =
        creditPatterns.any { it.containsMatchIn(t) } || instPatterns.any { it.containsMatchIn(t) } ||
            (t.length < 40 && colonCredit.containsMatchIn(t))
    private fun isMeta(t: String): Boolean =
        artist.length >= 2 && t.length < 60 && t.contains(Regex("[-–—]")) && t.contains(artist)

    // ---- 主循环(帧驱动) ----

    private var probeFrames = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime()
        if (probeFrames++ % 900 == 0) {
            android.util.Log.i("LisnOverlay", "onDraw f=$probeFrames lines=${lines.size} chars=${chars.size} shown=$charShown cur=$shownIdx pos=$basePosMs play=$playing w=$width h=$height")
        }
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L)
        lastFrame = now
        val posNow = basePosMs + if (playing) now - basePosTick else 0L

        val w = width.toFloat()
        val h = height.toFloat()
        // 字号:随视图宽度等比
        fontPx = (w * 0.052f).coerceIn(34f, 96f)

        // 定位当前行(跳过职员表/伴奏/元数据行)
        var cur = -1
        for (i in lines.indices) {
            val l = lines[i]
            if (l.timeMs <= posNow && !isFiltered(l.text) && !isMeta(l.text)) cur = i
        }

        // 切句:旧句入淡出队列
        if (cur != shownIdx) {
            if (shownIdx >= 0 && charShown > 0) {
                val f = Fading()
                f.chars = chars.copyOfRange(0, charShown)
                f.alpha = 255
                f.x = anchorX; f.y = anchorY; f.angle = angleDeg; f.fontPx = fontPx
                f.px = pPx; f.py = pPy; f.sx = pSx
                fading.add(f)
                if (fading.size > 4) fading.removeAt(0)
            }
            shownIdx = cur
            if (cur >= 0) {
                chars = CharArray(lines[cur].text.length) { lines[cur].text[it] }
                charShown = 0
                // 量宽适配
                textPaint.textSize = fontPx
                val spacing = 4f * density
                var totalW = 0f
                for (c in chars) totalW += textPaint.measureText(c.toString()) + spacing
                if (totalW > w * 0.92f) {
                    fontPx = (fontPx * w * 0.92f / totalW).coerceAtLeast(22f)
                    textPaint.textSize = fontPx
                    totalW = 0f
                    for (c in chars) totalW += textPaint.measureText(c.toString()) + spacing
                }
                // 随机锚点(上部区间)+ 随机角度
                anchorX = w * 0.04f + Random.nextFloat() * max(1f, w * 0.92f - totalW)
                anchorY = h * 0.12f + Random.nextFloat() * max(1f, h * 0.6f - fontPx * 2f)
                angleDeg = Random.nextFloat() * 20f - 10f
                val relX = ((anchorX + totalW / 2f) - w / 2f) / (w / 2f)
                val relY = (anchorY - h / 2f) / (h / 2f)
                pPx = 0.00005f * relX
                pPy = 0.0003f * relY
                pSx = 1f + 0.03f * max(0f, relX)
                shakes.clear()
                repeat(chars.size) { shakes.add(Shake()) }
            }
        }

        // 逐字显现(100ms/字,由进度驱动:暂停定格、seek 同步)
        if (cur >= 0) {
            charShown = min(chars.size, max(0, floor((posNow - lines[cur].timeMs) / 100.0).toInt() + 1))
        }

        // 抖动(143ms 节拍:随机目标 ±2px + 0.3 插值)
        if (now - lastShakeTick > 143) {
            lastShakeTick = now
            for (sh in shakes) {
                sh.tx = Random.nextFloat() * 4f - 2f
                sh.ty = Random.nextFloat() * 4f - 2f
                sh.x += (sh.tx - sh.x) * 0.3f
                sh.y += (sh.ty - sh.y) * 0.3f
            }
        }

        // 淡出行更新(30ms 节拍:alpha-8 / y-1.5)
        if (now - lastFadeTick > 30) {
            lastFadeTick = now
            val it = fading.iterator()
            while (it.hasNext()) {
                val f = it.next()
                f.alpha -= 8
                f.y -= 1.5f * density * 0.5f
                if (f.alpha <= 0) it.remove()
            }
        }

        // 闲置停机:无当前行且无淡出行;或暂停定格且无淡出行
        val paused = now - posChangedAt > 2500
        if ((cur < 0 && fading.isEmpty()) || (paused && fading.isEmpty())) {
            if (fading.isEmpty() && (cur < 0 || shownIdx >= 0)) {
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                if (cur < 0) shownIdx = -1
            }
            scheduled = false
            return
        }

        // 绘制(30fps 上限)
        if (now - lastDrawn >= 33) {
            lastDrawn = now
            paintFrame(canvas, cur)
        }
        postInvalidateDelayed(33)
    }

    private fun paintFrame(canvas: Canvas, cur: Int) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        for (f in fading) drawLine(canvas, f.chars, f.x, f.y, f.angle, f.fontPx, f.px, f.py, f.sx, f.alpha / 255f, null)
        if (cur >= 0 && charShown > 0) {
            drawLine(canvas, chars.copyOfRange(0, charShown), anchorX, anchorY, angleDeg, fontPx, pPx, pPy, pSx, 1f, shakes)
        }
    }

    private fun drawLine(
        canvas: Canvas, drawChars: CharArray, x0: Float, y0: Float, angleDeg: Float,
        fpx: Float, px: Float, py: Float, sx: Float, alpha: Float, shakesArr: List<Shake>?,
    ) {
        if (drawChars.isEmpty()) return
        cachePaint.textSize = fpx
        val spacing = 4f * density
        val rad = Math.toRadians(angleDeg.toDouble())
        var cursor = 0f
        var y = 0f
        for (i in drawChars.indices) {
            val s = drawChars[i].toString()
            val cw = cachePaint.measureText(s) + spacing
            val rx = (cursor * cos(rad)).toFloat()
            val ry = (cursor * sin(rad)).toFloat()
            val fw = (1f + px * rx + py * ry).coerceIn(0.6f, 1.6f)
            val dx = x0 + sx * rx / fw
            val dy = y0 + ry / fw
            val jx = shakesArr?.getOrNull(i)?.x ?: 0f
            val jy = shakesArr?.getOrNull(i)?.y ?: 0f
            val size = fpx / fw
            textPaint.textSize = size
            textPaint.color = gold
            textPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawText(s, dx + jx + 3f * density, dy + jy + 3f * density + size, textPaint)
            textPaint.color = cream
            canvas.drawText(s, dx + jx, dy + jy + size, textPaint)
            cursor += cw
            y += 0f
        }
    }
}
