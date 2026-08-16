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
 * 찍은 사진을 폰의 **책스캔 폴더**에 저장한다.
 *
 * - Android 10 이상: 갤러리(MediaStore)의 `Pictures/책스캔/<묶음>` — 갤러리에 바로 보이고
 *   USB로 PC에 연결하면 `내장메모리/Pictures/책스캔/` 에서 그대로 꺼낼 수 있다.
 * - Android 9 이하: 같은 경로에 파일로 저장.
 */
object PhotoStore {

    const val ROOT = "책스캔"

    private val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun relativePath(session: String) = "${Environment.DIRECTORY_PICTURES}/$ROOT/$session"

    /** 사진 저장 위치를 카메라에 알려 준다. */
    fun outputOptions(context: Context, session: String, index: Int): ImageCapture.OutputFileOptions {
        val name = String.format("%03d.jpg", index)
        if (modern) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath(session))
            }
            return ImageCapture.OutputFileOptions
                .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                .build()
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "$ROOT/$session"
        )
        dir.mkdirs()
        return ImageCapture.OutputFileOptions.Builder(File(dir, name)).build()
    }

    /** 이 묶음에 저장된 사진을 이름 순서대로 돌려준다(앱을 껐다 켜도 찾을 수 있게 폴더에서 읽는다). */
    fun photosOf(context: Context, session: String): List<() -> InputStream?> {
        if (modern) {
            val uris = mutableListOf<Pair<String, Uri>>()
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%$ROOT/$session%")
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    uris.add(cursor.getString(nameCol) to uri)
                }
            }
            return uris.sortedBy { it.first }.map { (_, uri) ->
                { context.contentResolver.openInputStream(uri) }
            }
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "$ROOT/$session"
        )
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
        return files.map { file -> { file.inputStream() as InputStream? } }
    }

    fun count(context: Context, session: String): Int = photosOf(context, session).size

    /** 사람이 읽을 수 있는 저장 위치 안내. */
    fun folderHint(session: String) = "내장메모리 / Pictures / $ROOT / $session"
}
