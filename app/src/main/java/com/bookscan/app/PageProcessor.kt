package com.bookscan.app

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 찍은 사진 한 장을 **책의 한 쪽**으로 만든다.
 *
 * 1. 페이지 네모 찾기 — 사진에서 가장 크고 밝은 면(=종이)의 테두리를 잡는다
 * 2. 네모를 반듯하게 — 네 귀퉁이가 뚜렷하면 펴고, 아니면 글줄에 맞춰 **회전만** 한다
 * 3. 한 쪽 고르기 — 펼친 책이면 가름선에서 나눠 글이 많은 쪽만 남긴다
 * 4. 기준 크기로 확대 — 가로 1800px
 *
 * **네모 안은 손대지 않는다.** 그림·아이콘·쪽번호·머리말이 다 그대로 남는다.
 * 글줄은 네모를 찾고 각도를 재는 데만 쓰고, 무엇을 지울지 정하는 데는 쓰지 않는다.
 */
object PageProcessor {

    const val OUT_WIDTH = 1800

    class Line(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0
        val centerX get() = (x0 + x1) / 2.0
    }

    /** rgba 사진 → 다듬은 새 Mat(호출한 쪽이 release). 어디까지 했는지 note에 남긴다. */
    fun finish(rgba: Mat, note: StringBuilder, pickPage: Boolean = true): Mat {
        var work = rgba.clone()
        try {
            if (eraseHand(work)) note.append(" 손O")

            val flat = flattenPaper(work, note)
            if (flat != null) {
                work.release(); work = flat
            } else {
                deskew(work, note)?.let { rotated -> work.release(); work = rotated }
                cropToPaper(work)?.let { cropped ->
                    work.release(); work = cropped; note.append(" 종이O")
                }
            }

            if (pickPage) {
                onePage(work)?.let { page ->
                    work.release(); work = page; note.append(" 한쪽O")
                }
            }

            val scale = OUT_WIDTH.toDouble() / max(work.cols(), 1)
            if (scale < 0.98 || scale > 1.02) {
                val resized = Mat()
                Imgproc.resize(
                    work, resized, Size(), scale, scale,
                    if (scale > 1) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA
                )
                work.release(); work = resized
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
            Imgproc.threshold(gray, mask, percentile(gray, 0.85), 255.0, Imgproc.THRESH_BINARY)
            Core.mean(rgba, mask)
        } catch (e: Throwable) {
            Scalar(255.0, 255.0, 255.0, 255.0)
        } finally {
            mask.release()
        }
    }

    // ── 1. 종이 면 찾기 ───────────────────────────────────────────

    /** 사진에서 가장 크고 밝은 덩어리(=종이) 자국. 못 찾으면 null. */
    private fun paperMask(rgba: Mat): Mat? {
        val gray = Mat()
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(9.0, 9.0), 0.0)
            val level = max(110.0, percentile(gray, 0.92) - 50)
            Imgproc.threshold(gray, mask, level, 255.0, Imgproc.THRESH_BINARY)
            handMask(rgba)?.let { hand ->        // 책을 잡은 손은 종이가 아니다
                Core.bitwise_not(hand, hand)
                Core.bitwise_and(mask, hand, mask)
                hand.release()
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
            if (best == 0 || bestArea < 0.15 * rgba.total()) return null
            val out = Mat()
            Core.compare(labels, Scalar(best.toDouble()), out, Core.CMP_EQ)
            return out
        } catch (e: Throwable) {
            return null
        } finally {
            gray.release(); mask.release(); labels.release(); stats.release(); centroids.release()
        }
    }

    /** 종이 자국의 네모 자리. */
    private fun paperBox(rgba: Mat): Rect? {
        val mask = paperMask(rgba) ?: return null
        val points = MatOfPoint()
        try {
            Core.findNonZero(mask, points)
            if (points.total() < 100) return null
            val box = Imgproc.boundingRect(points)
            val padX = (rgba.cols() * 0.006).toInt()
            val padY = (rgba.rows() * 0.006).toInt()
            val x0 = max(0, box.x - padX)
            val y0 = max(0, box.y - padY)
            val x1 = min(rgba.cols(), box.x + box.width + padX)
            val y1 = min(rgba.rows(), box.y + box.height + padY)
            if (x1 - x0 < rgba.cols() * 0.25 || y1 - y0 < rgba.rows() * 0.25) return null
            return Rect(x0, y0, x1 - x0, y1 - y0)
        } catch (e: Throwable) {
            return null
        } finally {
            mask.release(); points.release()
        }
    }

    private fun cropToPaper(rgba: Mat): Mat? {
        val box = paperBox(rgba) ?: return null
        if (box.width >= rgba.cols() * 0.99 && box.height >= rgba.rows() * 0.99) return null
        return Mat(rgba, box).clone()
    }

    // ── 2. 네모를 반듯하게 펴기 ───────────────────────────────────

    /**
     * 종이의 **네 귀퉁이**가 뚜렷하면 그 네모를 직사각형으로 편다.
     *
     * 귀퉁이가 미덥지 않으면(찌그러진 다각형·지나친 기울기) 아무것도 하지 않고
     * 회전만 하는 쪽으로 넘긴다 — 어설프게 펴면 글자가 휘기 때문이다.
     */
    private fun flattenPaper(rgba: Mat, note: StringBuilder): Mat? {
        val mask = paperMask(rgba) ?: return null
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.findContours(
                mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            val outline = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val area = Imgproc.contourArea(outline)
            if (area < rgba.total() * 0.15) return null

            val curve = MatOfPoint2f(*outline.toArray())
            val approx = MatOfPoint2f()
            val perimeter = Imgproc.arcLength(curve, true)
            var quad: Array<Point>? = null
            var eps = 0.01
            while (eps <= 0.05) {
                Imgproc.approxPolyDP(curve, approx, perimeter * eps, true)
                if (approx.total() == 4L) { quad = approx.toArray(); break }
                eps += 0.005
            }
            curve.release(); approx.release()
            val corners = quad ?: return null

            // 네 점이 정말 종이 네모인지 따진다
            val hull = MatOfPoint(*corners)
            val quadArea = Imgproc.contourArea(hull)
            hull.release()
            if (quadArea < area * 0.88) return null          // 다각형이 실제 면과 어긋난다

            val sorted = orderCorners(corners)
            val topWidth = dist(sorted[0], sorted[1])
            val bottomWidth = dist(sorted[3], sorted[2])
            val leftHeight = dist(sorted[0], sorted[3])
            val rightHeight = dist(sorted[1], sorted[2])
            if (min(topWidth, bottomWidth) < rgba.cols() * 0.25) return null
            if (min(leftHeight, rightHeight) < rgba.rows() * 0.25) return null
            if (max(topWidth, bottomWidth) / min(topWidth, bottomWidth) > 1.30) return null
            if (max(leftHeight, rightHeight) / min(leftHeight, rightHeight) > 1.30) return null
            val tilt = Math.toDegrees(
                kotlin.math.atan2(sorted[1].y - sorted[0].y, sorted[1].x - sorted[0].x)
            )
            if (abs(tilt) > 20) return null                  // 이만큼 돌아갔으면 잘못 잡은 것이다

            val width = ((topWidth + bottomWidth) / 2).toInt()
            val height = ((leftHeight + rightHeight) / 2).toInt()
            if (width < 200 || height < 200) return null

            val source = MatOfPoint2f(sorted[0], sorted[1], sorted[2], sorted[3])
            val target = MatOfPoint2f(
                Point(0.0, 0.0), Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0)
            )
            val matrix = Imgproc.getPerspectiveTransform(source, target)
            val fixed = Mat()
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.warpPerspective(
                rgba, fixed, matrix, Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, paperColor(rgba, gray)
            )
            source.release(); target.release(); matrix.release(); gray.release()
            note.append(" 네모O")
            return fixed
        } catch (e: Throwable) {
            return null
        } finally {
            mask.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** 네 점을 좌상 → 우상 → 우하 → 좌하 차례로 세운다. */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        val byY = points.sortedBy { it.y }
        val top = byY.take(2).sortedBy { it.x }
        val bottom = byY.drop(2).sortedBy { it.x }
        return arrayOf(top[0], top[1], bottom[1], bottom[0])
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    // ── 2-대체. 회전만으로 수평 맞추기 ────────────────────────────

    /**
     * 글줄이 가장 또렷하게 겹치는 각도를 찾아 그만큼만 돌린다.
     *
     * 줄 하나하나의 기울기를 재서 중간값을 쓰면 글자 모양에 휘둘리는데,
     * 가로로 눌러 본 밝기 그래프는 각도가 맞을 때 골과 마루가 가장 깊어진다.
     */
    private fun deskew(rgba: Mat, note: StringBuilder): Mat? {
        val gray = Mat()
        val small = Mat()
        val binary = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val body = lineBoxes(gray)
            if (body.size < 5) return null

            val x0 = max(0, body.minOf { it.x0 })
            val y0 = max(0, body.minOf { it.y0 })
            val x1 = min(gray.cols(), body.maxOf { it.x1 })
            val y1 = min(gray.rows(), body.maxOf { it.y1 })
            if (x1 - x0 < 100 || y1 - y0 < 100) return null
            val roi = Mat(gray, Rect(x0, y0, x1 - x0, y1 - y0))
            val scale = min(1.0, 900.0 / roi.cols())
            Imgproc.resize(roi, small, Size(), scale, scale, Imgproc.INTER_AREA)
            roi.release()
            binarize(small, binary)

            var best = 0.0
            var bestScore = lineSharpness(binary, 0.0)
            var angle = -8.0
            while (angle <= 8.0001) {                    // 굵게 훑고
                if (abs(angle) > 0.001) {
                    val score = lineSharpness(binary, angle)
                    if (score > bestScore) { bestScore = score; best = angle }
                }
                angle += 0.5
            }
            var fine = best - 0.4
            while (fine <= best + 0.4001) {              // 곱게 다듬는다
                val score = lineSharpness(binary, fine)
                if (score > bestScore) { bestScore = score; best = fine }
                fine += 0.1
            }
            if (abs(best) < 0.2) return null

            val center = Point(rgba.cols() / 2.0, rgba.rows() / 2.0)
            val matrix = Imgproc.getRotationMatrix2D(center, best, 1.0)
            val rotated = Mat()
            Imgproc.warpAffine(
                rgba, rotated, matrix, rgba.size(),
                Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, paperColor(rgba, gray)
            )
            matrix.release()
            note.append(" 회전O(").append(String.format("%+.1f", best)).append("도)")
            return rotated
        } catch (e: Throwable) {
            return null
        } finally {
            gray.release(); small.release(); binary.release()
        }
    }

    /** 이 각도로 돌렸을 때 글줄이 얼마나 또렷하게 겹치는지(가로 합의 들쭉날쭉함). */
    private fun lineSharpness(binary: Mat, angle: Double): Double {
        val turned = Mat()
        val rows = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            if (abs(angle) < 0.001) {
                Core.reduce(binary, rows, 1, Core.REDUCE_SUM, CvType.CV_32F)
            } else {
                val center = Point(binary.cols() / 2.0, binary.rows() / 2.0)
                val matrix = Imgproc.getRotationMatrix2D(center, angle, 1.0)
                Imgproc.warpAffine(
                    binary, turned, matrix, binary.size(),
                    Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0)
                )
                matrix.release()
                Core.reduce(turned, rows, 1, Core.REDUCE_SUM, CvType.CV_32F)
            }
            Core.meanStdDev(rows, mean, stddev)
            return stddev.toArray().firstOrNull() ?: 0.0
        } catch (e: Throwable) {
            return 0.0
        } finally {
            turned.release(); rows.release(); mean.release(); stddev.release()
        }
    }

    // ── 3. 펼친 책에서 한 쪽 고르기 ───────────────────────────────

    /**
     * 글이 두 무리로 갈라져 있으면(펼친 책) 가름선에서 잘라 **글이 많은 쪽**만 남긴다.
     * 자르는 자리는 두 무리 사이의 빈 곳 한가운데이므로, 남는 쪽은 여백까지 온전하다.
     */
    private fun onePage(rgba: Mat): Mat? {
        val gray = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val lines = lineBoxes(gray)
            if (lines.size < 6) return null
            val groups = columnGroups(lines, rgba.cols())
            if (groups.size < 2) return null

            val scored = groups.map { g ->
                g to lines.filter { it.centerX >= g[0] && it.centerX <= g[1] }
                    .sumOf { it.width.toDouble() * it.height }
            }.sortedByDescending { it.second }
            val (bestGroup, bestScore) = scored[0]
            val (nextGroup, nextScore) = scored[1]
            if (bestScore < nextScore * 1.15) return null      // 어느 쪽이 본문인지 애매하다

            val cut = if (bestGroup[0] > nextGroup[0]) {
                ((bestGroup[0] + nextGroup[1]) / 2).toInt()    // 고른 쪽이 오른쪽
            } else {
                ((bestGroup[1] + nextGroup[0]) / 2).toInt()    // 고른 쪽이 왼쪽
            }
            val keepRight = bestGroup[0] > nextGroup[0]
            val x0 = if (keepRight) cut.coerceIn(0, rgba.cols() - 1) else 0
            val x1 = if (keepRight) rgba.cols() else cut.coerceIn(1, rgba.cols())
            if (x1 - x0 < rgba.cols() * 0.25) return null
            return Mat(rgba, Rect(x0, 0, x1 - x0, rgba.rows())).clone()
        } catch (e: Throwable) {
            return null
        } finally {
            gray.release()
        }
    }

    /** 글줄이 모여 있는 가로 구간들(면이 여럿이면 여럿 나온다). */
    private fun columnGroups(lines: List<Line>, width: Int): List<DoubleArray> {
        if (lines.size < 4 || width <= 0) return emptyList()
        val bins = 240
        val covered = BooleanArray(bins)
        for (line in lines) {
            val a = (line.x0.toDouble() / width * bins).toInt().coerceIn(0, bins - 1)
            val b = (line.x1.toDouble() / width * bins).toInt().coerceIn(0, bins - 1)
            for (i in a..b) covered[i] = true
        }
        val gap = (bins * 0.045).toInt().coerceAtLeast(4)
        val out = ArrayList<DoubleArray>()
        var start = -1
        var last = -1
        for (i in 0 until bins) {
            if (covered[i]) {
                if (start < 0) start = i
                last = i
            } else if (start >= 0 && i - last > gap) {
                out.add(doubleArrayOf(start.toDouble() / bins * width, (last + 1.0) / bins * width))
                start = -1
            }
        }
        if (start >= 0) {
            out.add(doubleArrayOf(start.toDouble() / bins * width, (last + 1.0) / bins * width))
        }
        return out
    }

    // ── 글자줄 찾기(각도·쪽 나누기 판단에만 쓴다) ─────────────────

    private fun binarize(gray: Mat, dst: Mat) {
        Imgproc.adaptiveThreshold(
            gray, dst, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 41, 20.0
        )
    }

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
            binarize(gray, binary)
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
                if (box.width < w * 0.08 || box.height < 3 || box.height > h * 0.2) continue
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

    // ── 손가락 ────────────────────────────────────────────────────

    /**
     * 책을 **잡은 손**만 골라 낸다.
     *
     * 살색만 보고 지우면 표지의 갈색·베이지 글자까지 손으로 오인해 지워 버린다.
     * 손은 반드시 화면 가장자리에서 들어오므로, **테두리에 닿은 큰 덩어리**만 손으로 본다.
     */
    private fun handMask(rgba: Mat): Mat? {
        val rgb = Mat()
        val ycrcb = Mat()
        val raw = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val out = Mat()
        val piece = Mat()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
            Core.inRange(ycrcb, Scalar(40.0, 137.0, 80.0), Scalar(250.0, 175.0, 125.0), raw)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
            Imgproc.morphologyEx(raw, raw, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(raw, raw, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            kernel.release()

            val count = Imgproc.connectedComponentsWithStats(raw, labels, stats, centroids)
            if (count <= 1) return null
            out.create(raw.size(), CvType.CV_8UC1)
            out.setTo(Scalar(0.0))
            val total = rgba.total().toDouble()
            var found = false
            for (i in 1 until count) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)?.firstOrNull() ?: 0.0
                if (area < total * 0.006 || area > total * 0.30) continue
                val x = (stats.get(i, Imgproc.CC_STAT_LEFT)?.firstOrNull() ?: 0.0).toInt()
                val y = (stats.get(i, Imgproc.CC_STAT_TOP)?.firstOrNull() ?: 0.0).toInt()
                val w = (stats.get(i, Imgproc.CC_STAT_WIDTH)?.firstOrNull() ?: 0.0).toInt()
                val h = (stats.get(i, Imgproc.CC_STAT_HEIGHT)?.firstOrNull() ?: 0.0).toInt()
                val touches = x <= 2 || y <= 2 ||
                    x + w >= rgba.cols() - 2 || y + h >= rgba.rows() - 2
                if (!touches) continue                       // 면 한가운데 있는 것은 글자·그림이다
                Core.compare(labels, Scalar(i.toDouble()), piece, Core.CMP_EQ)
                Core.bitwise_or(out, piece, out)
                found = true
            }
            if (!found) return null
            return out.clone()
        } catch (e: Throwable) {
            return null
        } finally {
            rgb.release(); ycrcb.release(); raw.release()
            labels.release(); stats.release(); centroids.release()
            out.release(); piece.release()
        }
    }

    /** 종이 밖에서 들어온 손가락만 종이색으로 덮는다(면 안의 그림은 건드리지 않는다). */
    private fun eraseHand(rgba: Mat): Boolean {
        val hand = handMask(rgba) ?: return false
        val gray = Mat()
        return try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(13.0, 13.0))
            Imgproc.dilate(hand, hand, kernel)
            kernel.release()
            rgba.setTo(paperColor(rgba, gray), hand)
            true
        } catch (e: Throwable) {
            false
        } finally {
            hand.release(); gray.release()
        }
    }
}
