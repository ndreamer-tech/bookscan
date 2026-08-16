package com.bookscan.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer

/**
 * **내 모델** — 원장님 책 사진 186장으로 직접 학습시킨 작은 U-Net(겹침도 0.966).
 *
 * 범용 모델(SmartCropper·DocAligner)은 온갖 문서를 두루 맞히려 배웠지만,
 * 이 모델은 같은 책·같은 책상·같은 폰만 배웠다. 256×256으로 줄인 사진에서
 * **페이지 영역을 칠하고**, 그 자국의 테두리에서 네 귀퉁이를 뽑는다.
 */
object MyModelDetect {

    private const val ASSET = "page_seg.onnx"
    private const val SIDE = 256

    private var session: OrtSession? = null
    private var broken = false

    val usable get() = session != null

    @Synchronized
    fun prepare(context: Context) {
        if (session != null || broken) return
        try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            session = OrtEnvironment.getEnvironment().createSession(bytes, options)
        } catch (e: Throwable) {
            broken = true
        }
    }

    /** 사진에서 페이지 네 귀퉁이(좌상 → 우상 → 우하 → 좌하). 못 찾으면 null. */
    fun quad(bitmap: Bitmap): Array<Point>? {
        val run = session ?: return null
        var small: Bitmap? = null
        val mask = Mat(SIDE, SIDE, CvType.CV_8UC1)
        try {
            small = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
            val pixels = IntArray(SIDE * SIDE)
            small.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)

            // 학습 때와 같게 — RGB 순서, 0~1
            val plane = SIDE * SIDE
            val buffer = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                buffer[i] = ((p shr 16) and 0xFF) / 255f
                buffer[plane + i] = ((p shr 8) and 0xFF) / 255f
                buffer[2 * plane + i] = (p and 0xFF) / 255f
            }
            val data = FloatBuffer.wrap(buffer)

            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(
                env, data, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())
            ).use { tensor ->
                run.run(mapOf("img" to tensor)).use { out ->
                    val raw = out.get("mask").get().value
                    val rows = ((raw as? Array<*>)?.firstOrNull() as? Array<*>)
                        ?.firstOrNull() as? Array<*> ?: return null
                    val line = ByteArray(SIDE)
                    for (y in 0 until SIDE) {
                        val row = rows[y] as? FloatArray ?: return null
                        for (x in 0 until SIDE) {
                            // 내보낸 모델은 sigmoid 앞의 값이므로 0보다 크면 「페이지」다
                            line[x] = if (row[x] > 0f) 255.toByte() else 0
                        }
                        mask.put(y, 0, line)
                    }
                }
            }
            return cornersOf(mask, bitmap.width, bitmap.height)
        } catch (e: Throwable) {
            return null
        } finally {
            mask.release()
            if (small !== bitmap) small?.recycle()
        }
    }

    /** 칠해진 자국에서 네 귀퉁이를 뽑아 원래 사진 크기로 되돌린다. */
    private fun cornersOf(mask: Mat, width: Int, height: Int): Array<Point>? {
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            kernel.release()

            Imgproc.findContours(
                mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            val outline = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val area = Imgproc.contourArea(outline)
            if (area < SIDE * SIDE * 0.10) return null      // 너무 조금 칠했다 = 못 찾은 것

            val curve = MatOfPoint2f(*outline.toArray())
            val approx = MatOfPoint2f()
            val perimeter = Imgproc.arcLength(curve, true)
            var found: Array<Point>? = null
            var eps = 0.01
            while (eps <= 0.06) {
                Imgproc.approxPolyDP(curve, approx, perimeter * eps, true)
                if (approx.total() == 4L) { found = approx.toArray(); break }
                eps += 0.005
            }
            if (found == null) {
                val box = Mat()
                Imgproc.boxPoints(Imgproc.minAreaRect(curve), box)
                if (box.rows() == 4) {
                    found = Array(4) { i ->
                        Point(box.get(i, 0)[0], box.get(i, 1)[0])
                    }
                }
                box.release()
            }
            curve.release(); approx.release()
            val corners = found ?: return null

            val sx = width.toDouble() / SIDE
            val sy = height.toDouble() / SIDE
            val scaled = corners.map { Point(it.x * sx, it.y * sy) }.toTypedArray()
            return SmartDetect.check(scaled, width, height)
        } catch (e: Throwable) {
            return null
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }
}
