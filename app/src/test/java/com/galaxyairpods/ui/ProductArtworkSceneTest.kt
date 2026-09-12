package com.galaxyairpods.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.galaxyairpods.domain.motion.ProductMotion
import com.galaxyairpods.domain.motion.ProductPose
import com.galaxyairpods.ui.components.ProductArtworkScene
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductArtworkSceneTest {
    private fun scene() = ProductArtworkScene(RuntimeEnvironment.getApplication().resources)

    @Test fun equalBudHeightsAndClearanceAcrossEveryFrameAndScreenWidth() {
        val renderer = scene()
        for (width in listOf(240f, 280f, 320f, 368f, 600f)) {
            for (ms in 0..ProductMotion.DurationMs step 8) {
                val pose = ProductMotion.frame(ms.toFloat())
                val g = renderer.layout(width, 224f, pose)
                assertEquals("same height at $width / $ms", g.left.height(), g.right.height(), .001f)
                assertEquals("synchronized lift", g.left.top, g.right.top, .001f)
                assertFalse("buds intersect at $width / $ms", RectF.intersects(g.left, g.right))
                for (r in listOf(g.left, g.right)) {
                    assertTrue(r.left >= 0f && r.right <= width)
                    assertTrue(r.top >= 0f && r.bottom <= 224f)
                }
                if (ms in 840..900) {
                    assertTrue("buds must clear the open lid before docking", g.left.bottom <= g.lid.top)
                }
                if (pose.dock >= .6f) {
                    assertFalse("case overlaps left dock", RectF.intersects(g.body, g.left))
                    assertFalse("case overlaps right dock", RectF.intersects(g.body, g.right))
                }
            }
        }
    }

    @Test fun noExtractionBeforeLidFinishesAndNoDockBeforeBothBudsClearCase() {
        for (ms in 0..ProductMotion.DurationMs) {
            val f = ProductMotion.frame(ms.toFloat())
            if (f.leftLift > 0) assertEquals(1f, f.lid, 0f)
            if (f.dock > 0) assertEquals(1f, f.leftLift, 0f)
            assertEquals(f.leftLift, f.rightLift, 0f)
        }
    }

    @Test fun closedCaseAndOneBudStateDoNotBorrowTheFullTwoBudChoreography() {
        val closed = ProductMotion.frame(
            elapsedMs = ProductMotion.DurationMs.toFloat(),
            caseOpen = false,
            leftOutOfCase = true,
            rightOutOfCase = false,
        )
        assertEquals(0f, closed.lid, 0f)
        assertEquals(1f, closed.leftLift, 0f)
        assertEquals(0f, closed.rightLift, 0f)
        assertEquals(0f, closed.dock, 0f)

        val oneBudDuringOpen = ProductMotion.frame(
            elapsedMs = ProductMotion.DurationMs.toFloat(),
            caseOpen = true,
            leftOutOfCase = true,
            rightOutOfCase = false,
        )
        assertEquals(1f, oneBudDuringOpen.lid, 0f)
        assertEquals(1f, oneBudDuringOpen.leftLift, 0f)
        assertEquals(0f, oneBudDuringOpen.rightLift, 0f)
        assertEquals(0f, oneBudDuringOpen.dock, 0f)
    }

    @Test fun finalArtworkCentersMatchBatteryColumns() {
        for (width in listOf(240f, 320f, 368f, 600f)) {
            val g = scene().layout(width, 224f, ProductMotion.frame(2000f))
            val column = (width - 16f) / 3f
            assertEquals(column / 2f, g.left.centerX(), .01f)
            assertEquals(width / 2f, g.right.centerX(), .01f)
            assertEquals(width - column / 2f, g.body.centerX(), .01f)
        }
    }

    @Test fun caseRepositionsOnlyWhileFullyInvisible() {
        val renderer = scene()
        for (dock in listOf(.45f, .49f, .5f, .59f, .6f)) {
            assertEquals(0f, renderer.layout(320f, 224f,
                ProductPose(1f, 1f, 1f, dock)).caseAlpha, .001f)
        }
    }

    @Test fun renderProductionFramesForVisualReview() {
        val renderer = scene()
        val times = listOf(0, 180, 360, 500, 620, 760, 840, 960, 1060, 1140, 1260, 1380)
        val out = File("build/reports/motion").apply { mkdirs() }
        val sheet = Bitmap.createBitmap(1280, 840, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(sheet)
        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = Color.DKGRAY }
        times.forEachIndexed { index, ms ->
            val frame = Bitmap.createBitmap(640, 560, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.drawColor(Color.WHITE)
            canvas.scale(2f, 2f)
            canvas.drawText("${ms} ms", 12f, 20f, caption)
            canvas.save()
            canvas.translate(0f, 28f)
            renderer.draw(canvas, 320f, 224f, ProductMotion.frame(ms.toFloat()))
            canvas.restore()
            caption.textAlign = Paint.Align.CENTER
            for ((x, label) in listOf(50.67f to "L", 160f to "R", 269.33f to "Case")) {
                canvas.drawText(label, x, 270f, caption)
            }
            caption.textAlign = Paint.Align.LEFT
            File(out, "frame-$ms.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sheetCanvas.drawBitmap(frame, null, RectF((index % 4) * 320f,
                (index / 4) * 280f, (index % 4 + 1) * 320f, (index / 4 + 1) * 280f), null)
            assertTrue("native graphics must produce pixels", frame.getPixel(320, 380) != Color.TRANSPARENT)
        }
        File(out, "contact-sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
