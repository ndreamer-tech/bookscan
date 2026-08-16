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
                    Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Core.BORDER_DEFAULT_VALUE
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

            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = 0
            var bottom = 0
            var lines = 0
            val angles = ArrayList<Double>()
            for (contour in contours) {
                val box = Imgproc.boundingRect(contour)
                if (box.width < w * 0.12 || box.height < 3 || box.height > h * 0.2) continue
                lines++
                left = minOf(left, box.x)
                top = minOf(top, box.y)
                right = max(right, box.x + box.width)
                bottom = max(bottom, box.y + box.height)

                val rotated = Imgproc.minAreaRect(org.opencv.core.MatOfPoint2f(*contour.toArray()))
                var angle = rotated.angle
                if (rotated.size.width < rotated.size.height) angle += 90.0
                if (abs(angle) < 12) angles.add(angle)
            }
            if (lines < 4) return null

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
