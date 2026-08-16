package com.bookscan.app

import android.content.Context
import android.graphics.Bitmap
import me.pqpo.smartcropperlib.SmartCropper
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/**
 * **학습 모델로 테두리 찾기** — SmartCropper(Apache-2.0).
 *
 * 우리가 손으로 짠 규칙(밝기·윤곽선)은 책상 무늬나 그늘에 잘 속는다.
 * SmartCropper는 문서 테두리를 학습한 TensorFlow Lite 모델로 네 귀퉁이를 찍어 주므로
 * 훨씬 덜 흔들린다. 실패하면 null을 돌려주고, 그때는 예전 방식으로 되돌아간다.
 */
object SmartDetect {

    private var ready = false
    private var broken = false

    /** 모델을 한 번만 올린다(실패해도 앱은 그대로 돈다). */
    fun prepare(context: Context) {
        if (ready || broken) return
        try {
            SmartCropper.buildImageDetector(context)
            ready = true
        } catch (e: Throwable) {
            broken = true
        }
    }

    val usable get() = ready

    /**
     * 사진에서 페이지 네 귀퉁이를 찾는다(좌상 → 우상 → 우하 → 좌하).
     * 못 찾거나 미덥지 않으면 null.
     */
    fun quad(bitmap: Bitmap): Array<Point>? {
        if (!ready) return null
        val found = try {
            SmartCropper.scan(bitmap)
        } catch (e: Throwable) {
            null
        } ?: return null
        if (found.size != 4) return null

        val points = found.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
        val ordered = order(points)
        return if (plausible(ordered, bitmap.width, bitmap.height)) ordered else null
    }

    /** 회색 Mat에서 바로 찾기(미리보기용). */
    fun quadOfGray(gray: Mat): Array<Point>? {
        if (!ready) return null
        val rgba = Mat()
        var bitmap: Bitmap? = null
        return try {
            Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
            bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bitmap)
            quad(bitmap)
        } catch (e: Throwable) {
            null
        } finally {
            rgba.release()
            bitmap?.recycle()
        }
    }

    private fun order(points: Array<Point>): Array<Point> {
        val byY = points.sortedBy { it.y }
        val top = byY.take(2).sortedBy { it.x }
        val bottom = byY.drop(2).sortedBy { it.x }
        return arrayOf(top[0], top[1], bottom[1], bottom[0])
    }

    /** 너무 작거나 찌그러진 네모는 버린다. */
    private fun plausible(q: Array<Point>, width: Int, height: Int): Boolean {
        fun len(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val top = len(q[0], q[1])
        val bottom = len(q[3], q[2])
        val left = len(q[0], q[3])
        val right = len(q[1], q[2])
        if (minOf(top, bottom) < width * 0.25) return false
        if (minOf(left, right) < height * 0.25) return false
        if (maxOf(top, bottom) / maxOf(minOf(top, bottom), 1.0) > 1.6) return false
        if (maxOf(left, right) / maxOf(minOf(left, right), 1.0) > 1.6) return false
        val tilt = Math.toDegrees(kotlin.math.atan2(q[1].y - q[0].y, q[1].x - q[0].x))
        return abs(tilt) <= 25
    }
}
