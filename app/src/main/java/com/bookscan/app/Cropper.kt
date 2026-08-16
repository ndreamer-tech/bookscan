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
import org.opencv.core.Mat
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
    fun autoCrop(context: Context, uri: Uri): Boolean {
        val bitmap = decodeUpright(context, uri) ?: return false
        val mat = Mat()
        val gray = Mat()
        val warped = Mat()
        try {
            Utils.bitmapToMat(bitmap, mat)
            if (mat.empty()) return false
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            val quad = PageDetector.detectQuadIn(gray) ?: return false
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
            if (width < 200 || height < 200) return false

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

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, out)
            val saved = write(context, uri, out)
            out.recycle()
            return saved
        } catch (e: Throwable) {
            return false
        } finally {
            mat.release(); gray.release(); warped.release(); bitmap.recycle()
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
