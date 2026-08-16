package com.bookscan.app

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * **테두리 검출기 고르기** — 어느 모델로 페이지를 찾을지 정한다.
 *
 * 어느 것이 이 책·이 책상에서 제일 잘 맞는지는 직접 견줘 봐야 알기에,
 * 촬영 화면에서 바로 갈아 끼울 수 있게 했다.
 */
object Detectors {

    enum class Kind(val label: String) {
        SMART("검출: 스마트"),      // SmartCropper (HED 계열 TFLite)
        ALIGNER("검출: 얼라이너"),  // DocAligner (ONNX, 네 점 직접 예측)
        RULE("검출: 규칙"),         // 예전 방식(밝기·윤곽선)
    }

    private const val STORE = "bookscan"
    private const val KEY = "detector"

    var kind: Kind = Kind.SMART
        private set

    fun load(context: Context) {
        val name = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(KEY, Kind.SMART.name) ?: Kind.SMART.name
        kind = runCatching { Kind.valueOf(name) }.getOrDefault(Kind.SMART)
        prepare(context)
    }

    /** 다음 검출기로 넘긴다. */
    fun cycle(context: Context): Kind {
        kind = Kind.values()[(kind.ordinal + 1) % Kind.values().size]
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(KEY, kind.name).apply()
        prepare(context)
        return kind
    }

    fun prepare(context: Context) {
        when (kind) {
            Kind.SMART -> SmartDetect.prepare(context)
            Kind.ALIGNER -> DocAlignerDetect.prepare(context)
            Kind.RULE -> Unit
        }
    }

    /** 고른 검출기로 네 귀퉁이를 찾는다(규칙 방식이거나 실패하면 null). */
    fun quad(bitmap: Bitmap): Array<Point>? = when (kind) {
        Kind.SMART -> SmartDetect.quad(bitmap)
        Kind.ALIGNER -> DocAlignerDetect.quad(bitmap)
        Kind.RULE -> null
    }

    /** 미리보기용 — 회색 Mat에서 바로. */
    fun quadOfGray(gray: Mat): Array<Point>? {
        if (kind == Kind.RULE) return null
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

    /** 모델이 실제로 올라와 있는가(진단용). */
    fun ready(context: Context): Boolean {
        prepare(context)
        return when (kind) {
            Kind.SMART -> SmartDetect.usable
            Kind.ALIGNER -> DocAlignerDetect.usable
            Kind.RULE -> false
        }
    }

    /** 진단 기록에 남길 짧은 이름. */
    val tag get() = when (kind) {
        Kind.SMART -> "스마트"
        Kind.ALIGNER -> "얼라이너"
        Kind.RULE -> "규칙"
    }
}
