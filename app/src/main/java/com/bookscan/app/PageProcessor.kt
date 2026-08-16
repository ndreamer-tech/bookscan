package com.bookscan.app

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 찍은 사진 한 장을 **읽기 좋은 한 쪽**으로 만든다.
 *
 * PC(util 「글자평탄화인식」)에서 맞춰 놓은 순서를 그대로 옮긴 것이다.
 *
 * 1. 종이 영역만 남기기 — 사진에서 가장 크고 밝은 면(=종이) 밖은 잘라낸다
 * 2. 원근 바로잡기 — 글줄 양끝을 직선으로 맞춰 사다리꼴을 직사각형으로 되돌린다
 *    (기울기와 위·아래 글자 크기 차이가 함께 잡히고, 글자는 찌그러지지 않는다)
 *    측정이 안 되면 회전만으로 줄을 수평에 맞춘다
 * 3. 글자 범위만 남기기 — 본문 줄 무리만 골라 여백(글자 줄 높이의 배수)만 남기고 자른다
 * 4. 기준 크기로 확대 — 가로 1800px
 */
object PageProcessor {

    const val OUT_WIDTH = 1800
    /** 글자 둘레에 남길 여백 — 글자 줄 높이의 배수 */
    const val MARGIN_LINES = 3.0

    class Line(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0
        val centerX get() = (x0 + x1) / 2.0
        val centerY get() = (y0 + y1) / 2.0
    }

    /** rgba 사진 → 다듬은 새 Mat(호출한 쪽이 release). 어디까지 했는지 note에 남긴다. */
    fun finish(rgba: Mat, note: StringBuilder, margin: Double = MARGIN_LINES): Mat {
        var work = rgba.clone()
        try {
            cropToPaper(work)?.let { cropped ->
                work.release()
                work = cropped
                note.append(" 종이O")
            }

            if (eraseSkin(work)) note.append(" 손O")

            val straightened = textPerspective(work, margin, note)
            if (straightened != null) {
                work.release()
                work = straightened
            } else {
                deskew(work, note)?.let { rotated ->
                    work.release()
                    work = rotated
                }
            }

            cropOnePage(work, margin)?.let { cropped ->
                work.release()
                work = cropped
                note.append(" 글자O")
            }

            val scale = OUT_WIDTH.toDouble() / max(work.cols(), 1)
            if (scale < 0.98 || scale > 1.02) {
                val resized = Mat()
                Imgproc.resize(
                    work, resized, Size(), scale, scale,
                    if (scale > 1) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA
                )
                work.release()
                work = resized
            }
            return work
        } catch (e: Throwable) {
            note.append(" 오류")
            return work
        }
    }

    // ── 밝기 도우미 ───────────────────────────────────────────────

    /** 회색 그림에서 상위 q(0~1) 지점의 밝기. */
    private fun percentile(gray: Mat, q: Double): Double {
        val hist = Mat()
        val channels = MatOfInt(0)
        val size = MatOfInt(256)
        val ranges = MatOfFloat(0f, 256f)
        val empty = Mat()
        try {
            Imgproc.calcHist(listOf(gray), channels, empty, hist, size, ranges)
            val total = gray.total().toDouble()
            var sum = 0.0
            for (i in 0 until 256) {
                sum += hist.get(i, 0)?.firstOrNull() ?: 0.0
                if (sum / total >= q) return i.toDouble()
            }
            return 255.0
        } catch (e: Throwable) {
            return 200.0
        } finally {
            hist.release(); channels.release(); size.release(); ranges.release(); empty.release()
        }
    }

    private fun paperColor(rgba: Mat, gray: Mat): Scalar {
        val mask = Mat()
        return try {
            val level = percentile(gray, 0.85)
            Imgproc.threshold(gray, mask, level, 255.0, Imgproc.THRESH_BINARY)
            Core.mean(rgba, mask)
        } catch (e: Throwable) {
            Scalar(255.0, 255.0, 255.0, 255.0)
        } finally {
            mask.release()
        }
    }

    // ── 1. 종이 영역 ──────────────────────────────────────────────

    private fun cropToPaper(rgba: Mat): Mat? {
        val gray = Mat()
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(9.0, 9.0), 0.0)
            val level = max(120.0, percentile(gray, 0.92) - 45)
            Imgproc.threshold(gray, mask, level, 255.0, Imgproc.THRESH_BINARY)
            skinMask(rgba)?.let { skin ->        // 책을 잡은 손은 종이가 아니다
                Core.bitwise_not(skin, skin)
                Core.bitwise_and(mask, skin, mask)
                skin.release()
            }
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            kernel.release()

            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            if (count <= 1) return null
            var best = 0
            var bestArea = 0.0
            for (i in 1 until count) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)?.firstOrNull() ?: 0.0
                if (area > bestArea) { bestArea = area; best = i }
            }
            if (best == 0 || bestArea < 0.12 * rgba.total()) return null

            val x = (stats.get(best, Imgproc.CC_STAT_LEFT)?.firstOrNull() ?: 0.0).toInt()
            val y = (stats.get(best, Imgproc.CC_STAT_TOP)?.firstOrNull() ?: 0.0).toInt()
            val w = (stats.get(best, Imgproc.CC_STAT_WIDTH)?.firstOrNull() ?: 0.0).toInt()
            val h = (stats.get(best, Imgproc.CC_STAT_HEIGHT)?.firstOrNull() ?: 0.0).toInt()

            val padX = (rgba.cols() * 0.01).toInt()
            val padY = (rgba.rows() * 0.01).toInt()
            val x0 = max(0, x - padX)
            val y0 = max(0, y - padY)
            val x1 = min(rgba.cols(), x + w + padX)
            val y1 = min(rgba.rows(), y + h + padY)
            if (x1 - x0 < rgba.cols() * 0.25 || y1 - y0 < rgba.rows() * 0.25) return null
            if ((x1 - x0).toDouble() * (y1 - y0) > 0.97 * rgba.cols() * rgba.rows()) return null
            return Mat(rgba, Rect(x0, y0, x1 - x0, y1 - y0)).clone()
        } catch (e: Throwable) {
            return null
        } finally {
            gray.release(); mask.release(); labels.release(); stats.release(); centroids.release()
        }
    }

    // ── 손가락 ────────────────────────────────────────────────────

    /** 살색 부분(책을 잡은 손). 화면의 40%를 넘으면 잘못 잡은 것으로 보고 버린다. */
    private fun skinMask(rgba: Mat): Mat? {
        val rgb = Mat()
        val ycrcb = Mat()
        val mask = Mat()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
            Core.inRange(ycrcb, Scalar(40.0, 135.0, 80.0), Scalar(255.0, 178.0, 128.0), mask)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            kernel.release()
            val ratio = Core.countNonZero(mask).toDouble() / max(1, rgba.total().toInt())
            if (ratio < 0.004 || ratio > 0.40) { mask.release(); return null }
            return mask
        } catch (e: Throwable) {
            mask.release()
            return null
        } finally {
            rgb.release(); ycrcb.release()
        }
    }

    /** 손가락 자리를 종이색으로 덮는다(글자줄 찾기가 흔들리지 않게). */
    private fun eraseSkin(rgba: Mat): Boolean {
        val skin = skinMask(rgba) ?: return false
        val gray = Mat()
        return try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.dilate(skin, skin, kernel)
            kernel.release()
            rgba.setTo(paperColor(rgba, gray), skin)
            true
        } catch (e: Throwable) {
            false
        } finally {
            skin.release(); gray.release()
        }
    }

    // ── 글자줄 찾기 ───────────────────────────────────────────────

    /** 밝은 종이 위의 글자줄만 골라 낸다(키보드 줄무늬·흰 로고는 뺀다). */
    private fun lineBoxes(gray: Mat): List<Line> {
        val binary = Mat()
        val merged = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            val w = gray.cols()
            val h = gray.rows()
            val paperLevel = percentile(gray, 0.88)
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 41, 20.0
            )
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(max(9.0, w / 60.0), 3.0)
            )
            Imgproc.morphologyEx(binary, merged, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            kernel.release()
            Imgproc.findContours(
                merged, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val out = ArrayList<Line>()
            for (contour in contours) {
                val box = Imgproc.boundingRect(contour)
                if (box.width < w * 0.10 || box.height < 3 || box.height > h * 0.2) continue
                val x0 = max(0, box.x - 2)
                val y0 = max(0, box.y - 2)
                val x1 = min(w, box.x + box.width + 2)
                val y1 = min(h, box.y + box.height + 2)
                if (x1 - x0 < 4 || y1 - y0 < 4) continue
                val around = Mat(gray, Rect(x0, y0, x1 - x0, y1 - y0))
                try {
                    val bright = percentile(around, 0.85)
                    if (bright < paperLevel * 0.72) continue     // 어두운 바탕 = 종이가 아니다
                    val ink = Mat()
                    Imgproc.threshold(around, ink, bright * 0.62, 255.0, Imgproc.THRESH_BINARY_INV)
                    val ratio = Core.countNonZero(ink).toDouble() / max(1, ink.total().toInt())
                    ink.release()
                    if (ratio < 0.04 || ratio > 0.55) continue   // 흰 로고·빈 면 제외
                } finally {
                    around.release()
                }
                out.add(Line(box.x, box.y, box.x + box.width, box.y + box.height))
            }
            return out
        } catch (e: Throwable) {
            return emptyList()
        } finally {
            binary.release(); merged.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /** 세로 빈 띠로 열을 나눠 글자가 가장 많은 열만(펼친 책에서 한 쪽 고르기). */
    private fun mainColumn(lines: List<Line>, width: Int): List<Line> {
        if (lines.size < 4 || width <= 0) return lines
        val bins = 240
        val covered = BooleanArray(bins)
        for (line in lines) {
            val a = (line.x0.toDouble() / width * bins).toInt().coerceIn(0, bins - 1)
            val b = (line.x1.toDouble() / width * bins).toInt().coerceIn(0, bins - 1)
            for (i in a..b) covered[i] = true
        }
        val gap = (bins * 0.045).toInt().coerceAtLeast(4)
        val groups = ArrayList<IntArray>()
        var start = -1
        var last = -1
        for (i in 0 until bins) {
            if (covered[i]) {
                if (start < 0) start = i
                last = i
            } else if (start >= 0 && i - last > gap) {
                groups.add(intArrayOf(start, last)); start = -1
            }
        }
        if (start >= 0) groups.add(intArrayOf(start, last))
        if (groups.size < 2) return lines

        var best = lines
        var bestScore = -1.0
        for (group in groups) {
            val lo = group[0].toDouble() / bins * width
            val hi = (group[1] + 1).toDouble() / bins * width
            val inside = lines.filter { it.centerX in lo..hi }
            val score = inside.sumOf { it.width.toDouble() * it.height }
            if (score > bestScore) { bestScore = score; best = inside }
        }
        return if (best.size >= 4) best else lines
    }

    /** 세로로 이어지는 본문 줄 무리만(로고·표지 글씨처럼 동떨어진 줄 제거). */
    private fun mainRows(lines: List<Line>): List<Line> {
        if (lines.size < 4) return lines
        val rows = lines.sortedBy { it.y0 }
        val lineHeight = median(rows.map { it.height.toDouble() })
        val limit = max(lineHeight * 3.0, 12.0)

        val runs = ArrayList<MutableList<Line>>()
        var current = mutableListOf(rows[0])
        for (i in 1 until rows.size) {
            if (rows[i].y0 - rows[i - 1].y1 <= limit) {
                current.add(rows[i])
            } else {
                runs.add(current); current = mutableListOf(rows[i])
            }
        }
        runs.add(current)
        val best = runs.maxByOrNull { run -> run.sumOf { it.width.toDouble() } } ?: return lines
        return if (best.size >= 4) best else lines
    }

    private fun bodyLines(gray: Mat): List<Line> = mainRows(mainColumn(lineBoxes(gray), gray.cols()))

    // ── 2. 원근 바로잡기 ──────────────────────────────────────────

    private fun textPerspective(rgba: Mat, margin: Double, note: StringBuilder): Mat? {
        val gray = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val body = bodyLines(gray)
            if (body.size < 6) return null

            val widths = body.map { it.width.toDouble() }.sorted()
            val full = widths[(widths.size * 0.8).toInt().coerceAtMost(widths.size - 1)]
            val lines = body.filter { it.width >= full * 0.85 }
            if (lines.size < 5) return null

            val ys = lines.map { it.centerY }
            val span = lines.maxOf { it.centerY } - lines.minOf { it.centerY }
            if (span < rgba.rows() * 0.2) return null

            val leftFit = fitLine(ys, lines.map { it.x0.toDouble() }) ?: return null
            val rightFit = fitLine(ys, lines.map { it.x1.toDouble() }) ?: return null

            val lineHeight = median(lines.map { it.height.toDouble() })
            val pad = lineHeight * max(margin, 0.5)
            val yTop = lines.minOf { it.y0 }.toDouble() - pad
            val yBottom = lines.maxOf { it.y1 }.toDouble() + pad

            val topLeft = leftFit.first * yTop + leftFit.second - pad
            val topRight = rightFit.first * yTop + rightFit.second + pad
            val bottomLeft = leftFit.first * yBottom + leftFit.second - pad
            val bottomRight = rightFit.first * yBottom + rightFit.second + pad
            val topWidth = topRight - topLeft
            val bottomWidth = bottomRight - bottomLeft
            if (topWidth < 50 || bottomWidth < 50) return null

            val ratio = max(topWidth, bottomWidth) / min(topWidth, bottomWidth)
            val tilt = Math.toDegrees(atan((leftFit.first + rightFit.first) / 2))
            if (ratio < 1.02 && abs(tilt) < 0.3) return null   // 이미 반듯하다
            if (ratio > 1.6) return null                       // 측정이 틀린 것으로 본다

            val width = max(topWidth, bottomWidth).toInt()
            val height = (yBottom - yTop).toInt()
            if (width < 150 || height < 150) return null

            val source = MatOfPoint2f(
                Point(topLeft, yTop), Point(topRight, yTop),
                Point(bottomRight, yBottom), Point(bottomLeft, yBottom)
            )
            val target = MatOfPoint2f(
                Point(0.0, 0.0), Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0)
            )
            val matrix = Imgproc.getPerspectiveTransform(source, target)
            val fixed = Mat()
            Imgproc.warpPerspective(
                rgba, fixed, matrix, Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, paperColor(rgba, gray)
            )
            source.release(); target.release(); matrix.release()
            note.append(" 원근O(폭차").append(((ratio - 1) * 100).roundToInt()).append("%)")
            return fixed
        } catch (e: Throwable) {
            return null
        } finally {
            gray.release()
        }
    }

    /** 최소제곱 직선 맞춤 → (기울기, 절편) */
    private fun fitLine(xs: List<Double>, ys: List<Double>): Pair<Double, Double>? {
        val n = xs.size
        if (n < 2) return null
        val meanX = xs.average()
        val meanY = ys.average()
        var top = 0.0
        var bottom = 0.0
        for (i in 0 until n) {
            top += (xs[i] - meanX) * (ys[i] - meanY)
            bottom += (xs[i] - meanX) * (xs[i] - meanX)
        }
        if (abs(bottom) < 1e-6) return null
        val slope = top / bottom
        return slope to (meanY - slope * meanX)
    }

    // ── 2-대체. 회전만으로 수평 맞추기 ────────────────────────────

    private fun deskew(rgba: Mat, note: StringBuilder): Mat? {
        val gray = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val body = bodyLines(gray)
            if (body.size < 4) return null

            val angles = ArrayList<Double>()
            val binary = Mat()
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 41, 20.0
            )
            for (line in body) {
                val rect = Rect(line.x0, line.y0, line.width, line.height)
                if (rect.width < 8 || rect.height < 3) continue
                val piece = Mat(binary, rect)
                val points = MatOfPoint()
                try {
                    Core.findNonZero(piece, points)
                    if (points.total() < 20) continue
                    val rotated = Imgproc.minAreaRect(MatOfPoint2f(*points.toArray()))
                    var angle = rotated.angle
                    if (rotated.size.width < rotated.size.height) angle += 90.0
                    if (abs(angle) < 12) angles.add(angle)
                } finally {
                    piece.release(); points.release()
                }
            }
            binary.release()
            if (angles.size < 4) return null
            val skew = median(angles)
            if (abs(skew) < 0.25) return null

            val center = Point(rgba.cols() / 2.0, rgba.rows() / 2.0)
            val matrix = Imgproc.getRotationMatrix2D(center, skew, 1.0)
            val rotated = Mat()
            Imgproc.warpAffine(
                rgba, rotated, matrix, rgba.size(),
                Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, paperColor(rgba, gray)
            )
            matrix.release()
            note.append(" 회전O(").append(String.format("%+.1f", skew)).append("도)")
            return rotated
        } catch (e: Throwable) {
            return null
        } finally {
            gray.release()
        }
    }

    // ── 3. 글자 범위만 남기기 ─────────────────────────────────────

    private fun cropOnePage(rgba: Mat, margin: Double): Mat? {
        val gray = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val body = bodyLines(gray)
            if (body.size < 4) return null

            val lineHeight = median(body.map { it.height.toDouble() })
            val pad = (lineHeight * margin).toInt()
            val x0 = max(0, body.minOf { it.x0 } - pad)
            val y0 = max(0, body.minOf { it.y0 } - pad)
            val x1 = min(rgba.cols(), body.maxOf { it.x1 } + pad)
            val y1 = min(rgba.rows(), body.maxOf { it.y1 } + pad)
            if (x1 - x0 < 150 || y1 - y0 < 150) return null
            if ((x1 - x0) >= rgba.cols() && (y1 - y0) >= rgba.rows()) return null
            return Mat(rgba, Rect(x0, y0, x1 - x0, y1 - y0)).clone()
        } catch (e: Throwable) {
            return null
        } finally {
            gray.release()
        }
    }
}
