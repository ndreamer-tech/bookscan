package com.bookscan.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.InputStream

/** 찍은 사진들을 PDF 한 권으로 묶는다(PC의 통합독서분석에서 바로 열 수 있게). */
object PdfMaker {

    /** 너무 큰 사진은 줄여서 담는다 — OCR에는 이 정도면 충분하다. */
    private const val MAX_SIDE = 2200

    /** 사진 한 장을 여는 함수 목록(파일이든 갤러리든 같은 방식으로 다룬다). */
    fun build(pages: List<() -> InputStream?>, target: File): Boolean {
        if (pages.isEmpty()) return false
        val doc = PdfDocument()
        try {
            var number = 0
            pages.forEach { open ->
                val bitmap = decode(open) ?: return@forEach
                number++
                val info = PdfDocument.PageInfo
                    .Builder(bitmap.width, bitmap.height, number)
                    .create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), null)
                doc.finishPage(page)
                bitmap.recycle()
            }
            if (number == 0) return false
            target.parentFile?.mkdirs()
            target.outputStream().use { doc.writeTo(it) }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            doc.close()
        }
    }

    private fun decode(open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
