package com.bookscan.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File

/** 찍은 사진들을 PDF 한 권으로 묶는다(PC의 통합독서분석에서 바로 열 수 있게). */
object PdfMaker {

    /** 너무 큰 사진은 줄여서 담는다 — OCR에는 이 정도면 충분하다. */
    private const val MAX_SIDE = 2200

    fun build(photos: List<File>, target: File): Boolean {
        if (photos.isEmpty()) return false
        val doc = PdfDocument()
        try {
            photos.sortedBy { it.name }.forEachIndexed { index, file ->
                val bitmap = decode(file) ?: return@forEachIndexed
                val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(
                    bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), null
                )
                doc.finishPage(page)
                bitmap.recycle()
            }
            target.parentFile?.mkdirs()
            target.outputStream().use { doc.writeTo(it) }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            doc.close()
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        var side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
