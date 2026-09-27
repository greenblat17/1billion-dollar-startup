package com.eliteteam.speakingcoach.telegram

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private const val CANVAS_WIDTH = 808
private const val CANVAS_HEIGHT = 200
private const val PADDING_X = 36
private const val PADDING_Y = 32
private const val LABEL_BAND = 32
private const val LABEL_GAP = 16
private const val SQUARE = 88
private const val SQUARE_GAP = 20
private const val CORNER = 18f
private const val TODAY_STROKE = 4f

private val cream = Color(0xFB, 0xFA, 0xF6)
private val ink = Color(0x0E, 0x10, 0x20)
private val flame = Color(0xE8, 0x5D, 0x04)
private val missed = Color(0x9B, 0x9E, 0xA4)
private val outline = Color(0xE4, 0xE4, 0xE4)
private val future = Color(0xDF, 0xE2, 0xE8)

private val labelFont: Font by lazy {
    val stream = checkNotNull(StreakWeekImage::class.java.getResourceAsStream("/font/inter_regular.ttf")) {
        "Inter font is missing"
    }
    stream.use { Font.createFont(Font.TRUETYPE_FONT, it).deriveFont(28f) }
}

private object StreakWeekImage

internal fun streakWeekPng(cells: List<WeekCell>): ByteArray {
    require(cells.size == WEEKDAY_LABELS.size)
    val image = BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB)
    val canvas = image.createGraphics()
    canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    canvas.color = cream
    canvas.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
    canvas.font = labelFont
    cells.forEachIndexed { index, cell ->
        val left = PADDING_X + index * (SQUARE + SQUARE_GAP)
        drawSquare(canvas, left, cell)
        drawLabel(canvas, left, WEEKDAY_LABELS[index])
    }
    canvas.dispose()
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
}

private fun drawSquare(canvas: Graphics2D, left: Int, cell: WeekCell) {
    val top = PADDING_Y + LABEL_BAND + LABEL_GAP
    val shape = RoundRectangle2D.Float(left.toFloat(), top.toFloat(), SQUARE.toFloat(), SQUARE.toFloat(), CORNER, CORNER)
    when (cell) {
        WeekCell.Done -> {
            canvas.color = flame
            canvas.fill(shape)
        }
        WeekCell.Missed -> {
            canvas.color = missed
            canvas.fill(shape)
        }
        WeekCell.Future -> {
            canvas.color = future
            canvas.fill(shape)
        }
        WeekCell.TodayOpen -> {
            canvas.color = cream
            canvas.fill(shape)
            canvas.color = outline
            canvas.stroke = BasicStroke(TODAY_STROKE)
            val inset = TODAY_STROKE / 2f
            canvas.draw(
                RoundRectangle2D.Float(
                    left + inset,
                    top + inset,
                    SQUARE - TODAY_STROKE,
                    SQUARE - TODAY_STROKE,
                    CORNER,
                    CORNER,
                ),
            )
        }
    }
}

private fun drawLabel(canvas: Graphics2D, left: Int, label: String) {
    val metrics = canvas.fontMetrics
    val x = left + (SQUARE - metrics.stringWidth(label)) / 2
    val y = PADDING_Y + metrics.ascent
    canvas.color = ink
    canvas.drawString(label, x, y)
}
