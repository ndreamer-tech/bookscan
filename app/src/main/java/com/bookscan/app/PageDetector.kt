package com.bookscan.app

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
        /** 장면 지문(8x8 밝기) — 같은 쪽을 또 찍지 않으려고 비교한다 */
        val signature: IntArray,
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
                // 학습 모델(SmartCropper)이 먼저 보고, 못 찾으면 예전 규칙으로
                val quadSmall = SmartDetect.quadOfGray(small) ?: findQuad(small)
                val signature = signatureOf(small)
                if (quadSmall == null) {
                    return Result(null, width, height, 0f, sharpness, brightness, 1f, signature)
                }
                val area = polygonArea(quadSmall)
                // 「꽉 찼는가」는 종이 전체로 보고, 안내 네모는 **한 면**만 그린다
                val fill = (area / (small.cols() * small.rows())).toFloat()
                val onePage = singlePage(small, quadSmall)
                val skew = skewOf(onePage)

                val quad = FloatArray(8)
                for (i in 0 until 4) {
                    quad[i * 2] = (onePage[i].x / scale).toFloat()
                    quad[i * 2 + 1] = (onePage[i].y / scale).toFloat()
                }
                return Result(quad, width, height, fill, sharpness, brightness, skew, signature)
            } finally {
                small.release()
            }
        } finally {
            gray.release()
        }
    }


    /**
     * 펼친 책이면 **책등 그늘**에서 갈라 한 쪽만 남긴다.
     *
     * 두 면을 한꺼번에 잡으면 글자가 작아 인식이 나빠지고, 찍은 뒤 자르기도 어렵다.
     * 책등은 두 면 사이가 그늘져 세로로 어두운 골이 생기므로 그 자리를 찾는다.
     * 골이 뚜렷하지 않으면(한 면만 찍은 사진) 그대로 둔다.
     */
    private fun singlePage(gray: Mat, quad: Array<Point>): Array<Point> {
        val (tl, tr, br, bl) = quad
        val left = minOf(tl.x, bl.x)
        val right = maxOf(tr.x, br.x)
        val top = minOf(tl.y, tr.y)
        val bottom = maxOf(bl.y, br.y)
        if (right - left < 60 || bottom - top < 60) return quad

        val y0 = (top + (bottom - top) * 0.18).toInt().coerceIn(0, gray.rows() - 2)
        val y1 = (bottom - (bottom - top) * 0.18).toInt().coerceIn(y0 + 1, gray.rows())
        val x0 = left.toInt().coerceIn(0, gray.cols() - 2)
        val x1 = right.toInt().coerceIn(x0 + 1, gray.cols())
        val band = Mat(gray, Rect(x0, y0, x1 - x0, y1 - y0))
        val columns = Mat()
        try {
            Core.reduce(band, columns, 0, Core.REDUCE_AVG, CvType.CV_32F)
            val width = columns.cols()
            if (width < 40) return quad
            val values = FloatArray(width)
            columns.get(0, 0, values)

            val smooth = FloatArray(width)
            for (i in 0 until width) {
                var sum = 0f
                var n = 0
                for (k in -4..4) {
                    val j = i + k
                    if (j in 0 until width) { sum += values[j]; n++ }
                }
                smooth[i] = sum / n
            }
            val middle = smooth.sorted()[width / 2]
            if (middle <= 1f) return quad

            var cut = -1
            var lowest = Float.MAX_VALUE
            for (i in (width * 0.25).toInt() until (width * 0.75).toInt()) {
                if (smooth[i] < lowest) { lowest = smooth[i]; cut = i }
            }
            if (cut < 0 || lowest > middle * 0.90f) return quad   // 뚜렷한 골이 없다

            // 글씨가 더 많은 쪽을 본문으로 본다
            val level = middle * 0.72
            var inkLeft = 0
            var inkRight = 0
            val row = ByteArray(x1 - x0)
            for (y in y0 until y1 step 3) {
                gray.get(y, x0, row)
                for (i in row.indices) {
                    val v = row[i].toInt() and 0xFF
                    if (v < level) { if (i < cut) inkLeft++ else inkRight++ }
                }
            }
            val keepRight = inkRight > inkLeft
            val cutX = x0 + cut.toDouble()

            fun along(a: Point, b: Point): Point {
                val t = ((cutX - a.x) / (b.x - a.x)).coerceIn(0.0, 1.0)
                return Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            val topCut = along(tl, tr)
            val bottomCut = along(bl, br)
            return if (keepRight) arrayOf(topCut, tr, br, bottomCut)
            else arrayOf(tl, topCut, bottomCut, bl)
        } catch (e: Throwable) {
            return quad
        } finally {
            band.release(); columns.release()
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

    /** 화면을 8x8로 나눈 평균 밝기 — 쪽을 넘겼는지 알아보는 데 쓴다. */
    private fun signatureOf(small: Mat): IntArray {
        val tiny = Mat()
        try {
            Imgproc.resize(small, tiny, Size(8.0, 8.0), 0.0, 0.0, Imgproc.INTER_AREA)
            val out = IntArray(64)
            val buf = ByteArray(64)
            tiny.get(0, 0, buf)
            for (i in 0 until 64) out[i] = buf[i].toInt() and 0xFF
            return out
        } catch (e: Exception) {
            return IntArray(64)
        } finally {
            tiny.release()
        }
    }

    /** 두 장면이 사실상 같은가(쪽을 안 넘겼는가). */
    fun sameScene(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return false
        var diff = 0
        for (i in a.indices) diff += abs(a[i] - b[i])
        return diff / a.size < 9
    }

    // ── 페이지 윤곽 ───────────────────────────────────────────────

    /**
     * 페이지 네 귀퉁이 찾기.
     *
     * 표지가 어두운 책, 밝은 책상 등 어떤 조합이든 잡히도록 세 가지로 찾아 보고
     * 가장 사각형다운 것을 고른다. ①경계선 ②밝은 종이 ③어두운 표지
     */
    private fun findQuad(small: Mat): Array<Point>? {
        val blurred = Mat()
        try {
            Imgproc.GaussianBlur(small, blurred, Size(5.0, 5.0), 0.0)
            val frame = (small.cols() * small.rows()).toDouble()
            val found = ArrayList<Pair<Double, Array<Point>>>()

            // ① 경계선 — 색과 무관하게 '물체의 테두리'를 본다
            val edges = Mat()
            try {
                val mid = Core.mean(blurred).`val`[0]
                Imgproc.Canny(blurred, edges, max(10.0, 0.60 * mid), min(255.0, 1.35 * mid))
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
                Imgproc.dilate(edges, edges, kernel)
                Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
                kernel.release()
                collectQuads(edges, frame, found)
            } finally {
                edges.release()
            }

            // ② 밝은 종이 / ③ 어두운 표지
            for (invert in booleanArrayOf(false, true)) {
                val binary = Mat()
                try {
                    val flag = if (invert) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY
                    Imgproc.threshold(blurred, binary, 0.0, 255.0, flag + Imgproc.THRESH_OTSU)
                    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
                    Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
                    kernel.release()
                    collectQuads(binary, frame, found)
                } finally {
                    binary.release()
                }
            }

            val best = found.maxByOrNull { it.first } ?: return null
            return orderCorners(best.second)
        } finally {
            blurred.release()
        }
    }

    /** 흑백 그림에서 사각형 후보를 뽑아 점수와 함께 모은다. */
    private fun collectQuads(
        binary: Mat, frame: Double, into: MutableList<Pair<Double, Array<Point>>>
    ) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(
                binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            contours.sortByDescending { Imgproc.contourArea(it) }
            for (contour in contours.take(4)) {
                val area = Imgproc.contourArea(contour)
                // 화면을 거의 다 덮으면 '못 찾은 것'과 같다(배경째 잡힌 경우)
                if (area < 0.18 * frame || area > 0.94 * frame) continue

                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
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
                        val box = Imgproc.minAreaRect(curve)
                        val pts = arrayOf(Point(), Point(), Point(), Point())
                        box.points(pts)
                        corners = pts
                    }
                    val quadArea = polygonArea(corners)
                    if (quadArea < 0.18 * frame || quadArea > 0.94 * frame) continue
                    // 외곽선이 사각형에 얼마나 들어맞는가(찌그러진 그림자 등을 걸러낸다)
                    val fitness = area / max(quadArea, 1.0)
                    if (fitness < 0.72) continue
                    into.add(quadArea * fitness * rectScore(corners) to corners)
                } finally {
                    curve.release(); approx.release()
                }
            }
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** 색 있는 사진에서 네 귀퉁이를 찾는다(찍은 사진 다듬기에 쓴다 — 가장 정확). */
    fun detectQuadInColor(rgba: Mat): FloatArray? {
        val width = rgba.cols()
        if (width < 200 || rgba.rows() < 200) return null
        val scale = (WORK_WIDTH / width).coerceAtMost(1.0)
        val colorSmall = Mat()
        val graySmall = Mat()
        try {
            Imgproc.resize(rgba, colorSmall, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.cvtColor(colorSmall, graySmall, Imgproc.COLOR_RGBA2GRAY)

            val found = ArrayList<Pair<Double, Array<Point>>>()
            val frame = (colorSmall.cols() * colorSmall.rows()).toDouble()
            val colorMask = centerColorMask(colorSmall)
            try {
                collectQuads(colorMask, frame, found)
            } finally {
                colorMask.release()
            }
            val quad = (found.maxByOrNull { it.first }?.second?.let { orderCorners(it) })
                ?: findQuad(graySmall)
                ?: return null

            val out = FloatArray(8)
            for (i in 0 until 4) {
                out[i * 2] = (quad[i].x / scale).toFloat()
                out[i * 2 + 1] = (quad[i].y / scale).toFloat()
            }
            return out
        } catch (e: Throwable) {
            return null
        } finally {
            colorSmall.release(); graySmall.release()
        }
    }

    /**
     * 거칠게 찾은 네 귀퉁이를 **페이지 테두리 직선**에 맞춰 다듬는다.
     *
     * 외곽선 근사는 모서리가 몇 픽셀씩 밀리는데, 각 변 근처의 직선 조각들을 모아
     * 직선을 다시 맞추고 그 교점을 모서리로 삼으면 훨씬 정확해진다
     * (시험: 겹침 98.3% → 99.5%). 직선을 못 찾은 변은 원래 값을 그대로 둔다.
     */
    fun refineWithLines(gray: Mat, quad: FloatArray): FloatArray {
        val height = gray.rows()
        val width = gray.cols()
        val shorter = min(height, width).toDouble()
        val band = max(8.0, shorter * 0.035)

        val blurred = Mat()
        val edges = Mat()
        val segments = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 40.0, 120.0)
            Imgproc.HoughLinesP(
                edges, segments, 1.0, Math.PI / 180, 45, shorter * 0.18, 18.0
            )
            if (segments.rows() < 4) return quad

            val corners = Array(4) { i -> Point(quad[i * 2].toDouble(), quad[i * 2 + 1].toDouble()) }
            // 변마다 [x0, y0, dx, dy]
            val lines = Array(4) { DoubleArray(4) }

            for (e in 0 until 4) {
                val a = corners[e]
                val b = corners[(e + 1) % 4]
                val edgeAngle = Math.atan2(b.y - a.y, b.x - a.x)
                val nx = -(b.y - a.y)
                val ny = (b.x - a.x)
                val nlen = hypot(nx, ny).coerceAtLeast(1e-6)

                val inliers = ArrayList<Point>()
                for (i in 0 until segments.rows()) {
                    val v = segments.get(i, 0) ?: continue
                    val angle = Math.atan2(v[3] - v[1], v[2] - v[0])
                    var diff = Math.abs((angle - edgeAngle + Math.PI / 2) % Math.PI - Math.PI / 2)
                    if (diff > Math.toRadians(12.0)) continue
                    for (k in 0 until 2) {
                        val px = v[k * 2]
                        val py = v[k * 2 + 1]
                        val dist = Math.abs(((px - a.x) * nx + (py - a.y) * ny) / nlen)
                        if (dist < band) inliers.add(Point(px, py))
                    }
                }

                if (inliers.size >= 6) {
                    val pts = MatOfPoint2f(*inliers.toTypedArray())
                    val fitted = Mat()
                    try {
                        Imgproc.fitLine(pts, fitted, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
                        val f = DoubleArray(4)
                        fitted.get(0, 0, f)
                        lines[e] = doubleArrayOf(f[2], f[3], f[0], f[1])  // 점(x0,y0) + 방향(vx,vy)
                    } catch (e2: Exception) {
                        lines[e] = doubleArrayOf(a.x, a.y, b.x - a.x, b.y - a.y)
                    } finally {
                        pts.release(); fitted.release()
                    }
                } else {
                    lines[e] = doubleArrayOf(a.x, a.y, b.x - a.x, b.y - a.y)
                }
            }

            val refined = FloatArray(8)
            var maxMove = 0.0
            for (i in 0 until 4) {
                val p = lines[(i + 3) % 4]
                val q = lines[i]
                val det = p[2] * (-q[3]) - (-q[2]) * p[3]
                if (Math.abs(det) < 1e-6) {
                    refined[i * 2] = quad[i * 2]
                    refined[i * 2 + 1] = quad[i * 2 + 1]
                    continue
                }
                val bx = q[0] - p[0]
                val by = q[1] - p[1]
                val t = (bx * (-q[3]) - (-q[2]) * by) / det
                val x = p[0] + t * p[2]
                val y = p[1] + t * p[3]
                refined[i * 2] = x.toFloat()
                refined[i * 2 + 1] = y.toFloat()
                maxMove = max(maxMove, hypot(x - quad[i * 2], y - quad[i * 2 + 1]))
            }
            // 너무 멀리 튀면(엉뚱한 직선을 물었으면) 원래 값을 쓴다
            return if (maxMove > shorter * 0.12) quad else refined
        } catch (e: Throwable) {
            return quad
        } finally {
            blurred.release(); edges.release(); segments.release()
        }
    }

    /**
     * 가운데 색 덩어리의 사각 범위. 글자가 거의 없는 표지에서 마지막 수단으로 쓴다.
     * (좌표는 원본 크기 기준)
     */
    fun centerRegionBox(rgba: Mat): Rect? {
        val width = rgba.cols()
        if (width < 200 || rgba.rows() < 200) return null
        val scale = (WORK_WIDTH / width).coerceAtMost(1.0)
        val small = Mat()
        var mask: Mat? = null
        try {
            Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
            mask = centerColorMask(small)
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()
            val biggest = contours.maxByOrNull { Imgproc.contourArea(it) }
            val box = biggest?.let { Imgproc.boundingRect(it) }
            contours.forEach { it.release() }
            if (box == null) return null
            val frame = small.cols().toDouble() * small.rows()
            if (box.width.toDouble() * box.height < 0.15 * frame) return null
            return Rect(
                (box.x / scale).toInt(), (box.y / scale).toInt(),
                (box.width / scale).toInt(), (box.height / scale).toInt()
            )
        } catch (e: Throwable) {
            return null
        } finally {
            small.release(); mask?.release()
        }
    }

    /** 이미 흑백으로 만든 그림에서 바로 네 귀퉁이를 찾는다. */
    fun detectQuadIn(gray: Mat): FloatArray? {
        val width = gray.cols()
        if (width < 200 || gray.rows() < 200) return null
        val scale = (WORK_WIDTH / width).coerceAtMost(1.0)
        val small = Mat()
        try {
            Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
            val quad = findQuad(small) ?: return null
            val out = FloatArray(8)
            for (i in 0 until 4) {
                out[i * 2] = (quad[i].x / scale).toFloat()
                out[i * 2 + 1] = (quad[i].y / scale).toFloat()
            }
            return out
        } catch (e: Throwable) {
            return null
        } finally {
            small.release()
        }
    }

    /** 마주 보는 변 길이가 비슷할수록 1에 가깝다(배경까지 물린 사다리꼴을 걸러낸다). */
    private fun rectScore(corners: Array<Point>): Double {
        val o = orderCorners(corners)
        fun d(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val top = d(o[0], o[1])
        val bottom = d(o[3], o[2])
        val left = d(o[0], o[3])
        val right = d(o[1], o[2])
        val h = min(top, bottom) / max(max(top, bottom), 1e-6)
        val v = min(left, right) / max(max(left, right), 1e-6)
        return Math.pow(h * v, 1.5)
    }

    /**
     * 화면 **가운데 색과 비슷한 덩어리**를 책으로 본다.
     *
     * 책을 겨냥해 찍으므로 가운데는 늘 책이다. 표지가 청록색이든 흰 종이든 상관없이
     * 잡히고, 배경(책상·선반)이 책과 붙어 한 덩어리가 되는 문제도 막아 준다.
     */
    private fun centerColorMask(colorSmall: Mat): Mat {
        val lab = Mat()
        val mask = Mat()
        try {
            val rgb = Mat()
            Imgproc.cvtColor(colorSmall, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            rgb.release()

            val w = lab.cols()
            val h = lab.rows()
            val patch = Mat(lab, Rect((w * 0.38).toInt(), (h * 0.38).toInt(), (w * 0.24).toInt(), (h * 0.24).toInt()))
            val mean = Core.mean(patch)
            patch.release()

            val tol = doubleArrayOf(30.0, 16.0, 16.0)
            val lo = Scalar(
                max(0.0, mean.`val`[0] - tol[0]),
                max(0.0, mean.`val`[1] - tol[1]),
                max(0.0, mean.`val`[2] - tol[2])
            )
            val hi = Scalar(
                min(255.0, mean.`val`[0] + tol[0]),
                min(255.0, mean.`val`[1] + tol[1]),
                min(255.0, mean.`val`[2] + tol[2])
            )
            Core.inRange(lab, lo, hi, mask)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(11.0, 11.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 3)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            kernel.release()

            // 가운데를 품은 덩어리만 남기고, 글자 구멍은 볼록껍질로 메운다
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            var keep = labels.get(h / 2, w / 2)?.firstOrNull()?.toInt() ?: 0
            if (keep == 0 && count > 1) {
                var bestArea = 0.0
                for (i in 1 until count) {
                    val a = stats.get(i, Imgproc.CC_STAT_AREA)?.firstOrNull() ?: 0.0
                    if (a > bestArea) { bestArea = a; keep = i }
                }
            }
            if (keep > 0) {
                Core.compare(labels, Scalar(keep.toDouble()), mask, Core.CMP_EQ)
            }
            labels.release(); stats.release(); centroids.release()

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()
            val biggest = contours.maxByOrNull { Imgproc.contourArea(it) }
            if (biggest != null) {
                val hull = MatOfInt()
                Imgproc.convexHull(biggest, hull)
                val pts = biggest.toArray()
                val hullPoints = hull.toArray().map { pts[it] }.toTypedArray()
                mask.setTo(Scalar(0.0))
                Imgproc.fillConvexPoly(mask, MatOfPoint(*hullPoints), Scalar(255.0))
                hull.release()
            }
            contours.forEach { it.release() }
            return mask
        } catch (e: Throwable) {
            return mask
        } finally {
            lab.release()
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
