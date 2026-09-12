package com.galaxyairpods.ui.components

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.galaxyairpods.R
import com.galaxyairpods.domain.motion.ProductMotion
import com.galaxyairpods.domain.motion.ProductPose
import kotlin.math.min

/** Coordinates are in dp. Each destination describes visible pixels, not transparent padding. */
internal data class ArtworkLayout(
    val left: RectF,
    val right: RectF,
    val body: RectF,
    val lid: RectF,
    val closed: RectF,
    val frontEdge: Float,
    val caseAlpha: Float,
    val open: Float,
)

internal class ProductArtworkScene(resources: Resources) {
    private class Sprite(val bitmap: Bitmap, val source: Rect) {
        val aspect = source.width().toFloat() / source.height()
    }

    private val left = sprite(resources, R.drawable.airpods_pro2_bud_left)
    private val right = sprite(resources, R.drawable.airpods_pro2_bud_right)
    private val body = sprite(resources, R.drawable.airpods_pro2_case_body)
    private val lid = sprite(resources, R.drawable.airpods_pro2_lid_open)
    private val closed = sprite(resources, R.drawable.airpods_pro2_lid_closed)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun layout(width: Float, height: Float, pose: ProductPose): ArtworkLayout {
        val unit = min(1f, min(width / 280f, height / 224f))
        val center = width / 2f
        val heroWidth = 146f * unit
        val baseY = height - 16f * unit
        val bodyHeight = heroWidth / body.aspect
        val heroTop = baseY - bodyHeight
        val dock = pose.dock.coerceIn(0f, 1f)
        val gap = 8f
        val columnWidth = (width - 2f * gap) / 3f
        val slots = floatArrayOf(columnWidth / 2f, center, width - columnWidth / 2f)
        val finalY = height - 47f * unit

        fun bud(sprite: Sprite, side: Float, lift: Float, slot: Float): RectF {
            val h = 62f * unit
            val inCaseY = heroTop + 14f * unit
            val raisedY = 39f * unit
            // The same height and same interpolation for both physical buds.
            return centered(
                lerp(center + side * (34f + 16f * lift) * unit, slot, dock),
                lerp(lerp(inCaseY, raisedY, lift), finalY, dock),
                h * sprite.aspect, h,
            )
        }

        // Drop below the hero, become fully invisible, reposition, then
        // reappear in the right column. Never draw a second case or earbud.
        val departure = ProductMotion.smooth((dock / 0.45f).coerceIn(0f, 1f))
        val arrival = ProductMotion.smooth(((dock - 0.60f) / 0.40f).coerceIn(0f, 1f))
        val inSlot = dock >= 0.5f
        val caseWidth = if (inSlot) min(76f * unit, columnWidth - 12f) else heroWidth
        val caseX = if (inSlot) slots[2] else center
        val caseBottom = if (inSlot) {
            height - 16f * unit + (1f - arrival) * 14f * unit
        } else baseY + departure * 25f * unit
        val bodyRect = centered(caseX, caseBottom - caseWidth / body.aspect / 2f,
            caseWidth, caseWidth / body.aspect)
        val lidWidth = caseWidth * 0.981f
        val lidHeight = lidWidth / lid.aspect
        val lidBottom = bodyRect.top + caseWidth * 0.055f
        val lidRect = centered(caseX, lidBottom - lidHeight / 2f + (1f - pose.lid) * 5f * unit,
            lidWidth, lidHeight)
        val closedHeight = caseWidth / closed.aspect
        val closedRect = centered(caseX, caseBottom - closedHeight / 2f, caseWidth, closedHeight)
        return ArtworkLayout(
            left = bud(left, -1f, pose.leftLift.coerceIn(0f, 1f), slots[0]),
            right = bud(right, 1f, pose.rightLift.coerceIn(0f, 1f), slots[1]),
            body = bodyRect,
            lid = lidRect,
            closed = closedRect,
            frontEdge = bodyRect.top + bodyRect.height() * 0.25f,
            caseAlpha = if (inSlot) arrival else 1f - departure,
            open = pose.lid.coerceIn(0f, 1f),
        )
    }

    /** This is also called by the native-graphics frame tests; no second preview renderer. */
    fun draw(canvas: Canvas, width: Float, height: Float, pose: ProductPose,
             showCase: Boolean = true, charging: Boolean = false) {
        val geometry = layout(width, height, pose)
        val caseAlpha = if (showCase) geometry.caseAlpha else 0f
        val open = geometry.open
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        if (caseAlpha > 0f) {
            paint.color = Color.argb((22 * caseAlpha).toInt(), 70, 80, 90)
            canvas.drawOval(geometry.body.left + 8f, geometry.body.bottom - 1f,
                geometry.body.right - 8f, geometry.body.bottom + 5f, paint)
            // Start with the actual closed product render. The open body and
            // lid are then crossfaded as independent layers, so the case does
            // not swell or rotate as one flat image.
            drawSprite(canvas, closed, geometry.closed, (1f - open) * caseAlpha)
            // The source closed render contains a green indicator baked into
            // the photo. Cover it unless a fresh case-charging sample exists;
            // otherwise a stale/static green dot is shown on every popup.
            if (!charging && open < 1f) {
                paint.color = Color.argb((255 * (1f - open) * caseAlpha).toInt(),
                    231, 237, 244)
                canvas.drawCircle(
                    geometry.closed.centerX(),
                    geometry.closed.top + geometry.closed.height() * 0.50f,
                    geometry.body.width() * 0.014f,
                    paint,
                )
            }
            drawSprite(canvas, lid, geometry.lid, open * caseAlpha)
            drawSprite(canvas, body, geometry.body, open * caseAlpha)
        }

        val inCaseAlpha = if (showCase && pose.dock < 0.5f) {
            ProductMotion.smooth(((open - 0.45f) / 0.55f).coerceIn(0f, 1f))
        } else 1f
        // A bud already out of the case remains visible even when the case is
        // closed (for example, a connection popup after the lid event).
        val leftAlpha = if (pose.leftLift > 0.001f || !showCase) 1f else inCaseAlpha
        val rightAlpha = if (pose.rightLift > 0.001f || !showCase) 1f else inCaseAlpha
        drawSprite(canvas, left, geometry.left, leftAlpha)
        drawSprite(canvas, right, geometry.right, rightAlpha)

        if (caseAlpha > 0f && pose.dock < 0.5f) {
            if (open > 0.001f) {
                // Repaint only the opaque front shell over stems still inside
                // the case. The lid stays behind the buds throughout extraction.
                canvas.save()
                canvas.clipRect(geometry.body.left, geometry.frontEdge,
                    geometry.body.right, geometry.body.bottom)
                drawSprite(canvas, body, geometry.body, caseAlpha)
                canvas.restore()
            }
            if (charging) {
                paint.color = Color.argb((255 * caseAlpha).toInt(), 30, 170, 105)
                canvas.drawCircle(geometry.body.centerX(),
                    geometry.body.top + geometry.body.height() * 0.58f,
                    geometry.body.width() * 0.013f, paint)
            }
        }
        canvas.restore()
    }

    private fun drawSprite(canvas: Canvas, sprite: Sprite, destination: RectF, alpha: Float) {
        if (alpha <= 0f) return
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(sprite.bitmap, sprite.source, destination, paint)
        paint.alpha = 255
    }

    companion object {
        private fun sprite(resources: Resources, resource: Int): Sprite {
            val bitmap = requireNotNull(BitmapFactory.decodeResource(resources, resource))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var x0 = bitmap.width
            var y0 = bitmap.height
            var x1 = -1
            var y1 = -1
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if ((pixels[y * bitmap.width + x] ushr 24) >= 32) {
                    x0 = minOf(x0, x); y0 = minOf(y0, y)
                    x1 = maxOf(x1, x); y1 = maxOf(y1, y)
                }
            }
            require(x1 >= x0 && y1 >= y0) { "Empty AirPods artwork" }
            return Sprite(bitmap, Rect(x0, y0, x1 + 1, y1 + 1))
        }

        private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
        private fun centered(x: Float, y: Float, w: Float, h: Float) =
            RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
    }
}
