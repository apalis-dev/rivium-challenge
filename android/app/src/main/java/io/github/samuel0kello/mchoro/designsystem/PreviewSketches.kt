package io.github.samuel0kello.mchoro.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * A stand-in ink mask for @Previews, drawn in code because previews can't run Rust.
 * Like the real one: white pixels, alpha = ink. Bold outlines (high alpha) plus soft
 * hatching (low alpha), so the reveal animation has something to stage.
 */
object PreviewSketches {
    fun mask(width: Int = 320, height: Int = 400): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val shading = Paint(outline).apply { alpha = 90; strokeWidth = 2f }
        val cx = width / 2f
        val cy = height * 0.45f
        // A head, two eyes and a beak: close enough to the owl to read as a drawing.
        canvas.drawOval(cx - 120, cy - 140, cx + 120, cy + 140, outline)
        canvas.drawCircle(cx - 50, cy - 30, 30f, outline)
        canvas.drawCircle(cx + 50, cy - 30, 30f, outline)
        canvas.drawCircle(cx - 50, cy - 30, 9f, outline.apply { style = Paint.Style.FILL })
        canvas.drawCircle(cx + 50, cy - 30, 9f, outline)
        outline.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy, cx - 14, cy + 40, outline)
        canvas.drawLine(cx, cy, cx + 14, cy + 40, outline)
        var y = cy + 60
        while (y < cy + 130) {
            canvas.drawLine(cx - 90, y, cx + 90, y + 20, shading)
            y += 12
        }
        return bitmap.asImageBitmap()
    }
}
