package com.bookscan.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import java.io.File
import java.io.InputStream

/**
 * 사진을 폰의 **책스캔 폴더**에 저장한다.
 *
 * ```
 * 내장메모리/Pictures/책스캔/<묶음>/원본/001.jpg   ← 찍은 그대로
 * 내장메모리/Pictures/책스캔/<묶음>/처리/001.jpg   ← 잘라 다듬은 결과(PDF는 이걸로 만든다)
 * ```
 *
 * 찍은 파일에 덮어쓰지 않고 **새 파일로 저장**한다. 덮어쓰기가 막히는 폰이 있어
 * 다듬은 결과가 사라지는 일을 막기 위해서다.
 */
object PhotoStore {

    const val ROOT = "책스캔"
    const val RAW = "원본"
    const val DONE = "처리"

    private val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 마지막으로 저장이 어긋난 까닭(화면에 보여 주려고 남겨 둔다) */
    var lastError: String = ""
        private set

    fun relativePath(session: String, kind: String) =
        "${Environment.DIRECTORY_PICTURES}/$ROOT/$session/$kind"

    private fun legacyDir(session: String, kind: String) = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "$ROOT/$session/$kind"
    )

    /** 찍은 사진이 저장될 자리(원본 폴더). */
    fun outputOptions(
        context: Context, session: String, index: Int
    ): ImageCapture.OutputFileOptions {
        val name = String.format("%03d.jpg", index)
        if (modern) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath(session, RAW))
            }
            return ImageCapture.OutputFileOptions
                .Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )
                .build()
        }
        val dir = legacyDir(session, RAW)
        dir.mkdirs()
        return ImageCapture.OutputFileOptions.Builder(File(dir, name)).build()
    }

    /** 다듬은 사진을 담을 새 자리(처리 폴더). */
    fun newUri(context: Context, session: String, index: Int, kind: String = DONE): Uri? {
        val name = String.format("%03d.jpg", index)
        return try {
            if (modern) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath(session, kind))
                }
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
            } else {
                val dir = legacyDir(session, kind)
                dir.mkdirs()
                Uri.fromFile(File(dir, name))
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    /** PDF·목록용 — 처리 폴더를 먼저 보고, 비어 있으면 원본 폴더를 쓴다. */
    fun photosOf(context: Context, session: String): List<() -> InputStream?> {
        val done = photosIn(context, session, DONE)
        return if (done.isNotEmpty()) done else photosIn(context, session, RAW)
    }

    fun photosIn(context: Context, session: String, kind: String): List<() -> InputStream?> {
        if (modern) {
            val found = mutableListOf<Pair<String, Uri>>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%$ROOT/$session/$kind%")
            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
                    "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val fileName = cursor.getString(nameCol) ?: ""
                        if (fileName == Library.MARKER) continue   // 폴더 표시용
                        val uri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol).toString()
                        )
                        found.add(fileName to uri)
                    }
                }
            } catch (e: Exception) {
                return emptyList()
            }
            return found.sortedBy { it.first }.map { (_, uri) ->
                { context.contentResolver.openInputStream(uri) }
            }
        }
        val files = legacyDir(session, kind)
            .listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
        return files.map { file -> { file.inputStream() as InputStream? } }
    }

    fun folderHint(session: String) = "Pictures / $ROOT / $session"
}
