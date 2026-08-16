package com.bookscan.app

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** 카메라 화면에서 책 페이지의 네 귀퉁이를 찾고, 지금 찍어도 되는 상태인지 판단한다. */
object PageDetector {

    /** 페이지가 화면에서 차지해야 하는 최소 비율 */
    const val FILL_MIN = 0.40f
    /** 흔들림·초점 판정(라플라시안 분산) */
    const val SHARP_MIN = 75.0
    const val BRIGHT_MIN = 55.0
    const val BRIGHT_MAX = 228.0
    /** 마주 보는 변의 길이 차이 — 클수록 비스듬히 찍은 것 */
    const val SKEW_MAX = 0.24f

    /** 윤곽 계산용 축소 폭(작을수록 빠르다) */
    private const val WORK_WIDTH = 480.0

    class Result(
        /** 화면(회전 반영) 좌표계의 네 귀퉁이 8개 값, 못 찾으면 null */
        val quad: FloatArray?,
        val srcWidth: Int,
        val srcHeight: Int,
        val fill: Float,
        val sharpness: Double,
        val brightness: Double,
        val skew: Float,
    ) {
        val hasQuad get() = quad != null
        val fillOk get() = fill >= FILL_MIN
        val sharpOk get() = sharpness >= SHARP_MIN
        val brightOk get() = brightness in BRIGHT_MIN..BRIGHT_MAX
        val skewOk get() = skew <= SKEW_MAX
        val allOk get() = hasQuad && fillOk && sharpOk && brightOk && skewOk
    }

    fun analyze(image: ImageProxy): Result {
        val gray = grayFromY(image)
        try {
            rotate(gray, image.imageInfo.rotationDegrees)
            val width = gray.cols()
            val height = gray.rows()

            val brightness = Core.mean(gray).`val`[0]
            val sharpness = sharpnessOf(gray)

            val scale = (WORK_WIDTH / width).coerceAtMost(1.0)
            val small = Mat()
            Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
            try {
                val quadSmall = findQuad(small)
                if (quadSmall == null) {
                    return Result(null, width, height, 0f, sharpness, brightness, 1f)
                }
                val area = polygonArea(quadSmall)
                val fill = (area / (small.cols() * small.rows())).toFloat()
                val skew = skewOf(quadSmall)

                val quad = FloatArray(8)
                for (i in 0 until 4) {
                    quad[i * 2] = (quadSmall[i].x / scale).toFloat()
                    quad[i * 2 + 1] = (quadSmall[i].y / scale).toFloat()
                }
                return Result(quad, width, height, fill, sharpness, brightness, skew)
            } finally {
                small.release()
            }
        } finally {
            gray.release()
        }
    }

    // ── 이미지 준비 ───────────────────────────────────────────────

    private fun grayFromY(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val data = ByteArray(width * height)
        if (rowStride == width) {
            buffer.get(data, 0, minOf(data.size, buffer.remaining()))
        } else {
            val row = ByteArray(rowStride)
            var offset = 0
            for (y in 0 until height) {
                val take = minOf(rowStride, buffer.remaining())
                if (take <= 0) break
                buffer.get(row, 0, take)
                System.arraycopy(row, 0, data, offset, minOf(width, take))
                offset += width
            }
        }
        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, data)
        return mat
    }

    private fun rotate(mat: Mat, degrees: Int) {
        when (((degrees % 360) + 360) % 360) {
            90 -> Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(mat, mat, Core.ROTATE_180)
            270 -> Core.rotate(mat, mat, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
    }

    /** 가운데를 잘라 초점을 본다(라플라시안 분산이 낮으면 흔들렸거나 초점이 안 맞은 것). */
    private fun sharpnessOf(gray: Mat): Double {
        val w = gray.cols()
        val h = gray.rows()
        val crop = Mat(gray, Rect(w / 4, h / 4, w / 2, h / 2))
        val lap = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            Imgproc.Laplacian(crop, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, stddev)
            val sd = stddev.toArray().firstOrNull() ?: 0.0
            return sd * sd
        } finally {
            crop.release(); lap.release(); mean.release(); stddev.release()
        }
    }

    // ── 페이지 윤곽 ───────────────────────────────────────────────

    private fun findQuad(small: Mat): Array<Point>? {
        val blurred = Mat()
        val binary = Mat()
        try {
            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)
            // 종이는 바탕보다 밝다 — 밝기로 종이 덩어리를 잡는다
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            kernel.release()

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()
            if (contours.isEmpty()) return null

            val biggest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val area = Imgproc.contourArea(biggest)
            val frame = (small.cols() * small.rows()).toDouble()
            if (area < 0.12 * frame) {
                contours.forEach { it.release() }
                return null
            }

            val curve = MatOfPoint2f(*biggest.toArray())
            val approx = MatOfPoint2f()
            val perimeter = Imgproc.arcLength(curve, true)
            var corners: Array<Point>? = null
            for (eps in doubleArrayOf(0.02, 0.03, 0.05)) {
                Imgproc.approxPolyDP(curve, approx, eps * perimeter, true)
                if (approx.total() == 4L) {
                    corners = approx.toArray()
                    break
                }
            }
            if (corners == null) {
                // 사각형으로 안 떨어지면 최소외접 사각형으로 대신한다
                val box = Imgproc.minAreaRect(curve)
                val pts = arrayOf(Point(), Point(), Point(), Point())
                box.points(pts)
                corners = pts
            }
            curve.release(); approx.release()
            contours.forEach { it.release() }
            return if (corners.size == 4) orderCorners(corners) else null
        } finally {
            blurred.release(); binary.release()
        }
    }

    /** 좌상 → 우상 → 우하 → 좌하 */
    private fun orderCorners(pts: Array<Point>): Array<Point> {
        val bySum = pts.sortedBy { it.x + it.y }
        val byDiff = pts.sortedBy { it.y - it.x }
        return arrayOf(bySum.first(), byDiff.first(), bySum.last(), byDiff.last())
    }

    private fun polygonArea(p: Array<Point>): Double {
        var sum = 0.0
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            sum += p[i].x * q.y - q.x * p[i].y
        }
        return abs(sum) / 2.0
    }

    /** 마주 보는 변의 길이가 얼마나 다른가(0이면 정면). */
    private fun skewOf(p: Array<Point>): Float {
        fun len(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val top = len(p[0], p[1])
        val bottom = len(p[3], p[2])
        val left = len(p[0], p[3])
        val right = len(p[1], p[2])
        val h = abs(top - bottom) / max(max(top, bottom), 1.0)
        val v = abs(left - right) / max(max(left, right), 1.0)
        return max(h, v).toFloat()
    }
}
