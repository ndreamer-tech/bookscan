package com.bookscan.app

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File

/**
 * **쪽마다 읽어 둔 글자** — 한 번 읽으면 파일로 남겨 두고 다시 쓴다.
 *
 * ```
 * <앱 전용 폴더>/글자/<책>/001.txt
 * ```
 *
 * 인식은 폰 안에서(ML Kit 한국어) 이뤄지고, 한 쪽에 0.3초 안팎 걸린다.
 */
object PageText {

    private fun dir(context: Context, book: String): File =
        File(context.getExternalFilesDir("글자"), book).apply { mkdirs() }

    private fun file(context: Context, book: String, page: String): File =
        File(dir(context, book), page.substringBeforeLast('.') + ".txt")

    /** 읽어 둔 글자(없으면 null). */
    fun cached(context: Context, book: String, page: String): String? {
        val path = file(context, book, page)
        return if (path.exists()) runCatching { path.readText() }.getOrNull() else null
    }

    /** 이 쪽을 다시 찍었으면 읽어 둔 글자를 버린다. */
    fun forget(context: Context, book: String, page: String) {
        runCatching { file(context, book, page).delete() }
    }

    /** 이 쪽의 글자 — 없으면 지금 읽어서 남긴다. */
    fun read(context: Context, book: String, page: String, uri: Uri, again: Boolean = false): String {
        if (!again) cached(context, book, page)?.let { return it }
        val text = try {
            val reader = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            val image = InputImage.fromFilePath(context, uri)
            Tasks.await(reader.process(image)).text
        } catch (e: Throwable) {
            ""
        }
        runCatching { file(context, book, page).writeText(text) }
        return text
    }

    /** 책 전체를 한 벌로 묶은 글(쪽 구분선을 넣는다). */
    fun wholeBook(context: Context, book: String, pages: List<Library.Page>): String {
        val out = StringBuilder()
        for ((i, page) in pages.withIndex()) {
            out.append("── ").append(i + 1).append("쪽 ──").append(System.lineSeparator())
            out.append(cached(context, book, page.name).orEmpty())
            out.append(System.lineSeparator()).append(System.lineSeparator())
        }
        return out.toString()
    }
}
