package com.eliteteam.speakingcoach.telegram

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private const val CANVAS_WIDTH = 960
private const val CANVAS_HEIGHT = 320
private const val PAD_X = 56
private const val PAD_TOP = 48
private const val CIRCLE = 88
private const val CIRCLE_GAP = 38
private const val HEADER_GAP = 36
private const val LABEL_GAP = 20
private const val MARK_STROKE = 7f
private const val OPEN_STROKE = 4f

private val cream = Color(0xFB, 0xFA, 0xF6)
private val card = Color.WHITE
private val ink = Color(0x0E, 0x10, 0x20)
private val blue = Color(0x18, 0x74, 0xFC)
private val muted = Color(0x9B, 0x9E, 0xA4)
private val missedFill = Color(0xE8, 0xE9, 0xED)
private val outline = Color(0xE4, 0xE4, 0xE4)
private val mark = Color.WHITE

private val regularFont: Font by lazy { loadFont("/font/inter_regular.ttf") }
private val boldFont: Font by lazy { loadFont("/font/inter_bold.ttf") }

private object StreakWeekImage

internal fun streakWeekPng(strip: WeekStrip): ByteArray {
    require(strip.days.size == 7)
    val image = BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB)
    val canvas = image.createGraphics()
    canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    canvas.color = cream
    canvas.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
    drawCard(canvas)
    val headerFont = regularFont.deriveFont(26f)
    canvas.font = headerFont
    canvas.color = muted
    canvas.drawString(strip.header, PAD_X, PAD_TOP + canvas.fontMetrics.ascent)
    val circleTop = PAD_TOP + canvas.fontMetrics.height + HEADER_GAP
    val slots = strip.days.mapIndexed { index, day ->
        PAD_X + index * (CIRCLE + CIRCLE_GAP) to day
    }
    slots.forEach { (left, day) ->
        if (day.cell == WeekCell.Done) {
            drawGlow(canvas, left + CIRCLE / 2f, circleTop + CIRCLE / 2f)
        }
    }
    slots.forEach { (left, day) ->
        drawMark(canvas, left.toFloat(), circleTop.toFloat(), day)
    }
    slots.forEach { (left, day) ->
        drawLabel(canvas, left.toFloat(), circleTop.toFloat(), day)
    }
    canvas.dispose()
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
}

private fun drawCard(canvas: Graphics2D) {
    val inset = 16f
    val radius = 40f
    val width = CANVAS_WIDTH - inset * 2
    val height = CANVAS_HEIGHT - inset * 2
    for (layer in 3 downTo 1) {
        val grow = layer * 4f
        canvas.color = Color(ink.red, ink.green, ink.blue, 8 + (3 - layer) * 6)
        canvas.fill(
            RoundRectangle2D.Float(
                inset - grow / 2f,
                inset + 6f,
                width + grow,
                height + grow,
                radius,
                radius,
            ),
        )
    }
    canvas.color = card
    canvas.fill(RoundRectangle2D.Float(inset, inset, width, height, radius, radius))
}

private fun drawMark(canvas: Graphics2D, left: Float, top: Float, day: WeekDay) {
    val cx = left + CIRCLE / 2f
    val cy = top + CIRCLE / 2f
    when (day.cell) {
        WeekCell.Done -> {
            canvas.color = blue
            canvas.fill(Ellipse2D.Float(left, top, CIRCLE.toFloat(), CIRCLE.toFloat()))
            drawCheck(canvas, cx, cy)
        }
        WeekCell.Missed -> {
            canvas.color = missedFill
            canvas.fill(Ellipse2D.Float(left, top, CIRCLE.toFloat(), CIRCLE.toFloat()))
            drawCross(canvas, cx, cy)
        }
        WeekCell.TodayOpen -> {
            canvas.color = card
            canvas.fill(Ellipse2D.Float(left, top, CIRCLE.toFloat(), CIRCLE.toFloat()))
            canvas.color = outline
            canvas.stroke = BasicStroke(OPEN_STROKE)
            val inset = OPEN_STROKE / 2f
            canvas.draw(Ellipse2D.Float(left + inset, top + inset, CIRCLE - OPEN_STROKE, CIRCLE - OPEN_STROKE))
        }
    }
}

private fun drawLabel(canvas: Graphics2D, left: Float, top: Float, day: WeekDay) {
    canvas.font = if (day.isToday) boldFont.deriveFont(28f) else regularFont.deriveFont(26f)
    canvas.color = if (day.isToday) ink else muted
    val metrics = canvas.fontMetrics
    val x = left + (CIRCLE - metrics.stringWidth(day.label)) / 2f
    val y = top + CIRCLE + LABEL_GAP + metrics.ascent
    canvas.drawString(day.label, x, y)
}

private fun drawGlow(canvas: Graphics2D, cx: Float, cy: Float) {
    val rings = 4
    for (ring in rings downTo 1) {
        val extra = ring * 5f
        val alpha = 22 + (rings - ring) * 12
        canvas.color = Color(blue.red, blue.green, blue.blue, alpha)
        canvas.fill(
            Ellipse2D.Float(
                cx - CIRCLE / 2f - extra,
                cy - CIRCLE / 2f - extra,
                CIRCLE + extra * 2,
                CIRCLE + extra * 2,
            ),
        )
    }
}

private fun drawCheck(canvas: Graphics2D, cx: Float, cy: Float) {
    val path = Path2D.Float()
    path.moveTo(cx - 18f, cy + 1f)
    path.lineTo(cx - 4f, cy + 15f)
    path.lineTo(cx + 20f, cy - 16f)
    canvas.color = mark
    canvas.stroke = BasicStroke(MARK_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    canvas.draw(path)
}

private fun drawCross(canvas: Graphics2D, cx: Float, cy: Float) {
    canvas.color = muted
    canvas.stroke = BasicStroke(MARK_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val arm = 15f
    canvas.draw(line(cx - arm, cy - arm, cx + arm, cy + arm))
    canvas.draw(line(cx - arm, cy + arm, cx + arm, cy - arm))
}

private fun line(x1: Float, y1: Float, x2: Float, y2: Float): Path2D.Float {
    val path = Path2D.Float()
    path.moveTo(x1, y1)
    path.lineTo(x2, y2)
    return path
}

private fun loadFont(resource: String): Font {
    val stream = checkNotNull(StreakWeekImage::class.java.getResourceAsStream(resource)) {
        "Font $resource is missing"
    }
    return stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
}
