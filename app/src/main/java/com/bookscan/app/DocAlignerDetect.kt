package com.bookscan.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.opencv.core.Point
import java.nio.FloatBuffer

/**
 * **DocAligner** — 문서 네 귀퉁이를 직접 찍어 주는 학습 모델(DocsaidLab, Apache-2.0).
 *
 * 256×256으로 줄인 그림을 넣으면 네 점의 비율 좌표와 「찾았는가」를 돌려준다.
 * 신분증·서류로 학습된 모델이라 책 페이지에서 어떨지는 직접 견줘 봐야 안다.
 * 그래서 SmartCropper와 나란히 두고 고를 수 있게 했다.
 */
object DocAlignerDetect {

    private const val ASSET = "docaligner_point.onnx"
    private const val SIDE = 256

    private var session: OrtSession? = null
    private var broken = false

    val usable get() = session != null

    /** 모델을 한 번만 올린다(실패해도 앱은 그대로 돈다). */
    @Synchronized
    fun prepare(context: Context) {
        if (session != null || broken) return
        try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            session = OrtEnvironment.getEnvironment().createSession(bytes, options)
        } catch (e: Throwable) {
            broken = true
        }
    }

    /** 사진에서 페이지 네 귀퉁이(좌상 → 우상 → 우하 → 좌하). 못 찾으면 null. */
    fun quad(bitmap: Bitmap): Array<Point>? {
        val run = session ?: return null
        var small: Bitmap? = null
        return try {
            small = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
            val pixels = IntArray(SIDE * SIDE)
            small.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)

            // 모델은 채널이 앞에 오는 BGR 순서를 쓴다(학습 때 OpenCV로 읽었다)
            val data = FloatBuffer.allocate(3 * SIDE * SIDE)
            val plane = SIDE * SIDE
            val buffer = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                buffer[i] = ((p shr 16) and 0xFF) / 255f          // B 자리에 R… 아래에서 맞춘다
                buffer[plane + i] = ((p shr 8) and 0xFF) / 255f   // G
                buffer[2 * plane + i] = (p and 0xFF) / 255f       // R
            }
            // 위에서 채운 순서는 R,G,B 이므로 B와 R을 맞바꿔 BGR로 만든다
            for (i in 0 until plane) {
                val r = buffer[i]
                buffer[i] = buffer[2 * plane + i]
                buffer[2 * plane + i] = r
            }
            data.put(buffer).rewind()

            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(
                env, data, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())
            ).use { tensor ->
                run.run(mapOf("img" to tensor)).use { out ->
                    val found = (out.get("has_obj").get().value as Array<*>)
                        .firstOrNull()?.let { (it as FloatArray)[0] } ?: 0f
                    if (found <= 0.5f) return null
                    val points = (out.get("points").get().value as Array<*>)
                        .firstOrNull() as? FloatArray ?: return null
                    if (points.size < 8) return null
                    val w = bitmap.width.toDouble()
                    val h = bitmap.height.toDouble()
                    val quad = Array(4) { i ->
                        Point(points[i * 2] * w, points[i * 2 + 1] * h)
                    }
                    SmartDetect.check(quad, bitmap.width, bitmap.height)
                }
            }
        } catch (e: Throwable) {
            null
        } finally {
            if (small !== bitmap) small?.recycle()
        }
    }
}
