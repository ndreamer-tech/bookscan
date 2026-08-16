package com.bookscan.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

/** 찍은 사진에서 **윤곽 안(책 페이지)만** 잘라 반듯하게 펴서 다시 저장한다. */
object Cropper {

    /** 너무 큰 사진은 이 크기로 줄여서 다룬다(메모리 보호, OCR에는 충분). */
    private const val MAX_SIDE = 2600
    /** 글자가 잘리지 않게 윤곽을 살짝 넓힌다. */
    private const val MARGIN = 0.015f

    /**
     * 찍은 사진에서 페이지 윤곽을 **다시 찾아** 그 안만 남긴다.
     *
     * 미리보기에서 찾은 윤곽을 쓰지 않고 사진 자체를 다시 보는 이유:
     * 사진이 훨씬 또렷하고, 수동으로 찍었을 때도 똑같이 잘리기 때문이다.
     */
    /** 쪽 나누기 방식 */
    enum class PageMode { AUTO, SINGLE, SPREAD }

    /** (잘랐는가, 두 쪽으로 나눴는가) */
    class Result(val cropped: Boolean, val split: Boolean)

    fun autoCrop(
        context: Context,
        uri: Uri,
        session: String,
        nextIndex: Int,
        mode: PageMode,
    ): Result {
        val bitmap = decodeUpright(context, uri) ?: return Result(false, false)
        val mat = Mat()
        val warped = Mat()
        try {
            Utils.bitmapToMat(bitmap, mat)
            if (mat.empty()) return Result(false, false)
            val rough = PageDetector.detectQuadInColor(mat) ?: return Result(false, false)
            // 테두리 직선에 맞춰 모서리를 정밀하게 다듬는다
            val gray = Mat()
            val quad = try {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                PageDetector.refineWithLines(gray, rough)
            } catch (e: Throwable) {
                rough
            } finally {
                gray.release()
            }
            val points = Array(4) { i -> Point(quad[i * 2].toDouble(), quad[i * 2 + 1].toDouble()) }

            val cx = points.sumOf { it.x } / 4
            val cy = points.sumOf { it.y } / 4
            for (p in points) {
                p.x = (cx + (p.x - cx) * (1 + MARGIN)).coerceIn(0.0, mat.cols() - 1.0)
                p.y = (cy + (p.y - cy) * (1 + MARGIN)).coerceIn(0.0, mat.rows() - 1.0)
            }

            val (tl, tr, br, bl) = points
            val width = max(dist(tl, tr), dist(bl, br)).toInt()
            val height = max(dist(tl, bl), dist(tr, br)).toInt()
            if (width < 200 || height < 200) return Result(false, false)

            val from = MatOfPoint2f(tl, tr, br, bl)
            val to = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0),
                Point(0.0, height - 1.0)
            )
            val transform = Imgproc.getPerspectiveTransform(from, to)
            Imgproc.warpPerspective(mat, warped, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC)
            from.release(); to.release(); transform.release()

            // 펼친 책이면 책등에서 좌·우 쪽으로 나눈다
            val spread = when (mode) {
                PageMode.SINGLE -> false
                PageMode.SPREAD -> true
                PageMode.AUTO -> width > height * 1.05
            }
            val cut = if (spread) findGutter(warped) else -1

            if (cut > 0) {
                val leftMat = Mat(warped, Rect(0, 0, cut, warped.rows()))
                val rightMat = Mat(warped, Rect(cut, 0, warped.cols() - cut, warped.rows()))
                try {
                    val savedLeft = writeMat(context, uri, leftMat)
                    val rightUri = PhotoStore.newImageUri(context, session, nextIndex)
                    val savedRight = rightUri != null && writeMat(context, rightUri, rightMat)
                    return Result(savedLeft, savedLeft && savedRight)
                } finally {
                    leftMat.release(); rightMat.release()
                }
            }
            return Result(writeMat(context, uri, warped), false)
        } catch (e: Throwable) {
            return Result(false, false)
        } finally {
            mat.release(); warped.release(); bitmap.recycle()
        }
    }

    /**
     * 책등(가름선) 자리를 찾는다. 펼친 책은 가운데가 그늘져 어둡다.
     * 뚜렷하지 않으면 한가운데로 자른다.
     */
    private fun findGutter(page: Mat): Int {
        val gray = Mat()
        try {
            Imgproc.cvtColor(page, gray, Imgproc.COLOR_RGBA2GRAY)
            val h = gray.rows()
            val w = gray.cols()
            val band = Mat(gray, Rect(0, (h * 0.15).toInt(), w, (h * 0.7).toInt()))
            val columns = Mat()
            Core.reduce(band, columns, 0, Core.REDUCE_AVG, CvType.CV_32F)
            band.release()

            val values = FloatArray(w)
            columns.get(0, 0, values)
            columns.release()

            val lo = (w * 0.35).toInt()
            val hi = (w * 0.65).toInt()
            var bestX = w / 2
            var bestValue = Float.MAX_VALUE
            for (x in lo until hi) {
                // 좁은 홈을 놓치지 않게 이웃 몇 열의 평균으로 본다
                var sum = 0f
                var n = 0
                for (k in -4..4) {
                    val i = x + k
                    if (i in 0 until w) { sum += values[i]; n++ }
                }
                val v = sum / n
                if (v < bestValue) { bestValue = v; bestX = x }
            }
            val middle = values.average().toFloat()
            // 그늘이 뚜렷하지 않으면(평균의 93% 미만이 아니면) 한가운데로
            return if (bestValue < middle * 0.93f) bestX else w / 2
        } catch (e: Throwable) {
            return page.cols() / 2
        } finally {
            gray.release()
        }
    }

    private fun writeMat(context: Context, uri: Uri, mat: Mat): Boolean {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        return try {
            Utils.matToBitmap(mat, bitmap)
            write(context, uri, bitmap)
        } catch (e: Throwable) {
            false
        } finally {
            bitmap.recycle()
        }
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    /** 사진을 읽어 **똑바로 세운** 비트맵으로 돌려준다(EXIF 회전 반영). */
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null

        var sample = 1
        val side = max(bounds.outWidth, bounds.outHeight)
        while (side / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    /** 잘라낸 사진으로 원본 파일을 덮어쓴다(갤러리 회전 정보도 초기화). */
    private fun write(context: Context, uri: Uri, bitmap: Bitmap): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            } ?: return false
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.ORIENTATION, 0)
                    put(MediaStore.Images.Media.WIDTH, bitmap.width)
                    put(MediaStore.Images.Media.HEIGHT, bitmap.height)
                }
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                // 갤러리 정보 갱신 실패는 무시(사진 자체는 이미 저장됨)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
