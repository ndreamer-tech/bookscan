package com.bookscan.app

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 한 쪽을 **정형화**한다 — 기울기를 바로잡고, 글자 영역만 남기고, 기준 크기로 키운다.
 *
 * 페이지 테두리를 못 찾은 사진도 글자 덩어리는 늘 찾을 수 있으므로,
 * 배경(책상·키보드·손)이 남아 있어도 이 단계에서 잘려 나간다.
 */
object PageFinisher {

    /** 결과 가로 크기(글자가 이 정도면 OCR에 충분하고 파일도 무겁지 않다). */
    const val OUT_WIDTH = 1800
    /** 글자 영역 둘레에 남길 여백 */
    private const val MARGIN = 0.045

    class Block(val rect: Rect, val skew: Double)

    /** rgba 사진 한 장 → 다듬은 새 Mat(호출한 쪽이 release). 실패하면 원본 복사본. */
    fun finish(rgba: Mat): Mat {
        var working = rgba.clone()
        try {
            var block = textBlock(working) ?: return working

            // ① 기울기 바로잡기
            if (abs(block.skew) > 0.3) {
                val rotated = Mat()
                val center = Point(working.cols() / 2.0, working.rows() / 2.0)
                val matrix = Imgproc.getRotationMatrix2D(center, block.skew, 1.0)
                Imgproc.warpAffine(
                    working, rotated, matrix, working.size(),
                    Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
                )
                matrix.release()
                working.release()
                working = rotated
                block = textBlock(working) ?: return working
            }

            // ② 글자 영역만 남기기(여백 조금)
            val r = block.rect
            val mx = (r.width * MARGIN).roundToInt()
            val my = (r.height * MARGIN).roundToInt()
            val x0 = max(0, r.x - mx)
            val y0 = max(0, r.y - my)
            val x1 = minOf(working.cols(), r.x + r.width + mx)
            val y1 = minOf(working.rows(), r.y + r.height + my)
            if (x1 - x0 < 100 || y1 - y0 < 100) return working

            val cut = Mat(working, Rect(x0, y0, x1 - x0, y1 - y0)).clone()
            working.release()

            // ③ 기준 크기로 맞추기
            val scale = OUT_WIDTH.toDouble() / cut.cols()
            if (scale < 0.98 || scale > 1.02) {
                val resized = Mat()
                Imgproc.resize(
                    cut, resized, Size(), scale, scale,
                    if (scale > 1) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA
                )
                cut.release()
                return resized
            }
            return cut
        } catch (e: Throwable) {
            return working
        }
    }

    /**
     * 세로로 빈 띠를 찾아 **본문이 있는 열만** 남긴다.
     *
     * 한 쪽만 찍어도 옆 페이지가 조금 걸쳐 들어오는데, 그대로 두면 그 조각까지
     * 함께 잘려 나가고 기울기 계산도 흐트러진다.
     */
    private fun keepMainColumn(boxes: List<Rect>, width: Int): List<Rect> {
        if (boxes.size < 4 || width <= 0) return boxes
        val bins = 200
        val covered = BooleanArray(bins)
        for (box in boxes) {
            val from = (box.x.toDouble() / width * bins).toInt().coerceIn(0, bins - 1)
            val to = ((box.x + box.width).toDouble() / width * bins).toInt().coerceIn(0, bins - 1)
            for (i in from..to) covered[i] = true
        }
        // 폭의 5% 이상 비어 있으면 다른 열로 본다
        val gap = (bins * 0.05).toInt().coerceAtLeast(4)
        val groups = ArrayList<IntArray>()
        var start = -1
        var lastSeen = -1
        for (i in 0 until bins) {
            if (covered[i]) {
                if (start < 0) start = i
                lastSeen = i
            } else if (start >= 0 && i - lastSeen > gap) {
                groups.add(intArrayOf(start, lastSeen))
                start = -1
            }
        }
        if (start >= 0) groups.add(intArrayOf(start, lastSeen))
        if (groups.size < 2) return boxes

        var best: List<Rect> = boxes
        var bestScore = -1.0
        for (group in groups) {
            val lo = group[0].toDouble() / bins * width
            val hi = (group[1] + 1).toDouble() / bins * width
            val inside = boxes.filter { (it.x + it.width / 2.0) in lo..hi }
            val score = inside.sumOf { it.width.toDouble() }
            if (score > bestScore) {
                bestScore = score
                best = inside
            }
        }
        return if (best.size >= 4) best else boxes
    }

    /** 글자줄들이 이루는 사각 범위와 기울기. */
    private fun textBlock(rgba: Mat): Block? {
        val gray = Mat()
        val binary = Mat()
        val merged = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val w = gray.cols()
            val h = gray.rows()
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 41, 20.0
            )
            // 낱글자를 가로로 이어 '줄'로 만든다
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(max(9.0, w / 60.0), 3.0)
            )
            Imgproc.morphologyEx(binary, merged, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            kernel.release()

            // 페이지 테두리가 큰 고리를 만들므로 RETR_LIST로 '안쪽 줄'까지 본다
            Imgproc.findContours(
                merged, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 줄 하나하나를 모은다(옆 페이지 조각이 섞여 있을 수 있다) — (사각형, 기울기)
            val found = ArrayList<Pair<Rect, Double?>>()
            for (contour in contours) {
                val box = Imgproc.boundingRect(contour)
                if (box.width < w * 0.12 || box.height < 3 || box.height > h * 0.2) continue
                val rotated = Imgproc.minAreaRect(
                    org.opencv.core.MatOfPoint2f(*contour.toArray())
                )
                var angle = rotated.angle
                if (rotated.size.width < rotated.size.height) angle += 90.0
                found.add(box to (if (abs(angle) < 12) angle else null))
            }
            val kept = keepMainColumn(found.map { it.first }, w).toHashSet()
            val lines = found.filter { kept.contains(it.first) }

            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = 0
            var bottom = 0
            val angles = ArrayList<Double>()
            for ((box, angle) in lines) {
                left = minOf(left, box.x)
                top = minOf(top, box.y)
                right = max(right, box.x + box.width)
                bottom = max(bottom, box.y + box.height)
                angle?.let { angles.add(it) }
            }
            if (lines.size < 4) return null

            angles.sort()
            val skew = if (angles.isEmpty()) 0.0 else angles[angles.size / 2]
            return Block(Rect(left, top, right - left, bottom - top), skew)
        } catch (e: Throwable) {
            return null
        } finally {
            gray.release(); binary.release(); merged.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }
}
