package com.bookscan.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 책스캔 **서재** — 폴더 하나가 책 한 권이다.
 *
 * ```
 * Pictures/책스캔/<책 이름>/원본/001.jpg
 * Pictures/책스캔/<책 이름>/처리/001.jpg   ← 목록·PDF는 이걸 쓴다
 * ```
 */
object Library {

    class Book(val name: String, val pages: Int, val cover: Uri?)

    class Page(val uri: Uri, val name: String)

    private val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** 폴더를 만들려고 넣어 두는 표시용 파일(목록에서는 숨긴다) */
    const val MARKER = "_folder.jpg"

    private const val STORE = "bookscan"
    private const val KNOWN = "books"

    /**
     * 새로 만든 책 이름을 적어 둔다.
     *
     * MediaStore에는 **빈 폴더를 만들 수 없어서**, 첫 사진을 찍기 전에는 폴더가
     * 생기지 않는다. 그래도 서재에는 바로 보여야 하므로 이름만 따로 적어 둔다.
     */
    fun remember(context: Context, book: String) {
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val known = LinkedHashSet(store.getStringSet(KNOWN, emptySet()).orEmpty())
        known.add(book)
        store.edit().putStringSet(KNOWN, known).apply()
    }

    fun forget(context: Context, book: String) {
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val known = LinkedHashSet(store.getStringSet(KNOWN, emptySet()).orEmpty())
        known.remove(book)
        store.edit().putStringSet(KNOWN, known).apply()
    }

    /**
     * 책 폴더를 **미리 만든다**.
     *
     * 안드로이드 사진 저장소는 빈 폴더를 만들지 못한다. 그래서 작은 안내 파일을
     * 한 장씩 넣어 폴더가 실제로 생기게 한다 — 찍기 전에도 파일 앱에서 보인다.
     */
    fun createFolders(context: Context, book: String): Boolean {
        var made = false
        for (kind in listOf(PhotoStore.RAW, PhotoStore.DONE)) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, MARKER)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        PhotoStore.relativePath(book, kind)
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: continue
                // 1×1 짜리 그림 한 장 — 폴더를 만들기 위한 것일 뿐이다
                val dot = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    dot.compress(Bitmap.CompressFormat.JPEG, 50, out)
                }
                dot.recycle()
                made = true
            } catch (e: Exception) {
                // 이미 있거나 못 만들어도 촬영에는 지장이 없다
            }
        }
        return made
    }

    private fun knownBooks(context: Context): Set<String> =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getStringSet(KNOWN, emptySet()).orEmpty()

    /** 서재에 꽂힌 책들(최근에 찍은 것부터). */
    fun books(context: Context): List<Book> {
        val counts = LinkedHashMap<String, Int>()
        val covers = HashMap<String, Uri>()
        val order = HashMap<String, Long>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        try {
            context.contentResolver.query(
                images, projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%${PhotoStore.ROOT}/%"),
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val timeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathCol) ?: continue
                    val book = bookOf(path) ?: continue
                    val fileName = cursor.getString(nameCol) ?: ""
                    val kind = kindOf(path)
                    val uri = Uri.withAppendedPath(images, cursor.getLong(idCol).toString())
                    val time = cursor.getLong(timeCol)
                    if (time > (order[book] ?: 0L)) order[book] = time
                    if (fileName == MARKER) {
                        counts.putIfAbsent(book, 0)        // 폴더만 있는 새 책
                    } else if (kind == PhotoStore.DONE) {
                        counts[book] = (counts[book] ?: 0) + 1
                        if (!covers.containsKey(book)) covers[book] = uri
                    } else {
                        counts.putIfAbsent(book, 0)
                        if (!covers.containsKey(book)) covers[book] = uri
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        // 사진이 아직 없는 새 책도 서재에 보여 준다
        val names = LinkedHashSet(counts.keys)
        names.addAll(knownBooks(context))
        return names
            .sortedByDescending { order[it] ?: Long.MAX_VALUE }
            .map { Book(it, pages(context, it).size, covers[it]) }
    }

    /** `Pictures/책스캔/<책>/처리` 에서 책 이름만 떼어낸다. */
    private fun bookOf(path: String): String? {
        val marker = "${PhotoStore.ROOT}/"
        val at = path.indexOf(marker)
        if (at < 0) return null
        val rest = path.substring(at + marker.length).trim('/')
        val name = rest.substringBefore('/')
        return name.ifBlank { null }
    }

    private fun kindOf(path: String): String =
        path.trim('/').substringAfterLast('/')

    /** 책의 쪽들(번호 순). 다듬은 것이 있으면 그것을, 없으면 원본을 보여준다. */
    fun pages(context: Context, book: String): List<Page> {
        val done = pagesIn(context, book, PhotoStore.DONE)
        return if (done.isNotEmpty()) done else pagesIn(context, book, PhotoStore.RAW)
    }

    fun pagesIn(context: Context, book: String, kind: String): List<Page> {
        val found = ArrayList<Page>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME
        )
        try {
            context.contentResolver.query(
                images, projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%${PhotoStore.ROOT}/$book/$kind%"),
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameCol) ?: ""
                    if (fileName == MARKER) continue     // 폴더 표시용 — 쪽이 아니다
                    found.add(
                        Page(
                            Uri.withAppendedPath(images, cursor.getLong(idCol).toString()),
                            fileName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return found.sortedBy { it.name }
    }

    /** 쪽 순서를 바꾼다 — 파일 이름을 001.jpg부터 다시 매긴다. */
    fun move(context: Context, book: String, from: Int, to: Int): Boolean {
        val pages = pages(context, book).toMutableList()
        if (from !in pages.indices || to !in pages.indices || from == to) return false
        pages.add(to, pages.removeAt(from))
        return renumber(context, pages)
    }

    /** 두 걸음으로 이름을 바꾼다(한 번에 바꾸면 이름이 부딪친다). */
    private fun renumber(context: Context, pages: List<Page>): Boolean {
        try {
            for ((i, page) in pages.withIndex()) {
                rename(context, page.uri, String.format("t%03d.jpg", i + 1))
            }
            for ((i, page) in pages.withIndex()) {
                rename(context, page.uri, String.format("%03d.jpg", i + 1))
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun rename(context: Context, uri: Uri, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    /** 한 쪽을 지우고 번호를 다시 매긴다. */
    fun remove(context: Context, book: String, index: Int): Boolean {
        val pages = pages(context, book).toMutableList()
        if (index !in pages.indices) return false
        return try {
            context.contentResolver.delete(pages[index].uri, null, null)
            pages.removeAt(index)
            renumber(context, pages)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 책 이름(폴더)을 바꾼다 — 사진들의 저장 경로를 옮긴다. */
    fun renameBook(context: Context, from: String, to: String): Boolean {
        var moved = 0
        for (kind in listOf(PhotoStore.RAW, PhotoStore.DONE)) {
            for (page in pagesIn(context, from, kind)) {
                try {
                    val values = ContentValues().apply {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            PhotoStore.relativePath(to, kind)
                        )
                    }
                    context.contentResolver.update(page.uri, values, null, null)
                    moved++
                } catch (e: Exception) {
                    return false
                }
            }
        }
        return moved > 0
    }

    /** 책 한 권을 통째로 지운다. */
    fun removeBook(context: Context, book: String): Boolean {
        forget(context, book)
        return try {
            for (kind in listOf(PhotoStore.DONE, PhotoStore.RAW)) {
                for (page in pagesIn(context, book, kind)) {
                    context.contentResolver.delete(page.uri, null, null)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 목록에 쓸 작은 그림. */
    fun thumbnail(context: Context, uri: Uri, target: Int = 400): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** 새 책 이름 — 같은 이름이 있으면 뒤에 번호를 붙인다. */
    fun freshName(context: Context, wanted: String): String {
        val taken = books(context).map { it.name }.toSet() + knownBooks(context)
        if (wanted !in taken) return wanted
        var i = 2
        while ("$wanted $i" in taken) i++
        return "$wanted $i"
    }

    fun folderHint(book: String) = "Pictures / ${PhotoStore.ROOT} / $book"

    fun diagnosticDir(context: Context): File? = context.getExternalFilesDir(null)
}
