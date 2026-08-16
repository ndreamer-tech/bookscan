package com.bookscan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bookscan.app.databinding.ActivityPageBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.Executors

/**
 * **쪽 보기** — 한 쪽을 크게 보고, **이미지 ↔ 텍스트**를 오간다.
 *
 * 텍스트는 그 쪽을 처음 열 때 한 번 읽어 두고(폰 안에서, 한국어), 다음부터는 바로 뜬다.
 * 원본과 견주며 잘못 읽은 곳을 짚어 보라고 두 가지를 나란히 두었다.
 */
class PageViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK = "book"
        const val EXTRA_AT = "at"
        const val EXTRA_TEXT_FIRST = "text_first"
    }

    private lateinit var ui: ActivityPageBinding
    private var book = ""
    private var at = 0
    private var showText = false
    private val pages = ArrayList<Library.Page>()
    private val workers = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        ui = ActivityPageBinding.inflate(layoutInflater)
        setContentView(ui.root)

        book = intent.getStringExtra(EXTRA_BOOK).orEmpty()
        at = intent.getIntExtra(EXTRA_AT, 0)
        showText = intent.getBooleanExtra(EXTRA_TEXT_FIRST, false)
        ui.pvTitle.text = book

        ui.pvBack.setOnClickListener { finish() }
        ui.pvPrev.setOnClickListener { move(-1) }
        ui.pvNext.setOnClickListener { move(1) }
        ui.pvMode.setOnClickListener {
            showText = !showText
            render()
        }
        ui.pvDelete.setOnClickListener { askDelete() }
        ui.pvRetake.setOnClickListener { retake() }
        ui.pvCopy.setOnClickListener { copyText() }
        ui.pvShare.setOnClickListener { shareImage() }
        ui.pvMore.setOnClickListener { showMore() }

        // 손가락으로 좌우로 밀어 쪽을 넘긴다
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                down: MotionEvent?, up: MotionEvent, alongX: Float, alongY: Float
            ): Boolean {
                val start = down ?: return false
                val moved = up.x - start.x
                if (kotlin.math.abs(moved) < 100 ||
                    kotlin.math.abs(moved) < kotlin.math.abs(up.y - start.y)
                ) {
                    return false
                }
                move(if (moved < 0) 1 else -1)     // 왼쪽으로 밀면 다음 쪽
                return true
            }
        })
        val listen = View.OnTouchListener { view, event ->
            val handled = swipe.onTouchEvent(event)
            if (!handled) view.performClick()
            handled
        }
        ui.pvImage.setOnTouchListener(listen)
        ui.pvTextBox.setOnTouchListener { view, event ->
            swipe.onTouchEvent(event)             // 글자는 스크롤도 해야 하므로 가로챌 때만
            false
        }

        workers.execute {
            val found = Library.pages(this, book)
            main.post {
                pages.clear(); pages.addAll(found)
                if (pages.isEmpty()) {
                    Toast.makeText(this, "쪽이 없습니다", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    at = at.coerceIn(0, pages.size - 1)
                    render()
                }
            }
        }
    }

    /** 이 쪽을 지운다(뒤 번호가 당겨진다). */
    private fun askDelete() {
        if (pages.isEmpty()) return
        val at = this.at
        AlertDialog.Builder(this)
            .setTitle("${at + 1}쪽 지우기")
            .setMessage("이 쪽을 지우고 뒤 번호를 당깁니다.")
            .setPositiveButton("지우기") { _, _ ->
                val gone = pages[at]
                workers.execute {
                    Library.remove(this, book, at)
                    PageText.forget(this, book, gone.name)
                    val found = Library.pages(this, book)
                    main.post {
                        pages.clear(); pages.addAll(found)
                        if (pages.isEmpty()) {
                            Toast.makeText(this, "쪽이 모두 없어졌습니다", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            this.at = at.coerceIn(0, pages.size - 1)
                            render()
                        }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 이 쪽만 다시 찍어 **같은 번호 자리에** 덮어쓴다. */
    private val retaker = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val shot = DocScan.pagesOf(result.data).firstOrNull()
        if (shot == null) {
            Toast.makeText(this, "다시 찍기를 그만두었습니다", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val page = pages.getOrNull(at) ?: return@registerForActivityResult
        ui.pvBusy.visibility = View.VISIBLE
        workers.execute {
            val ok = try {
                val bytes = contentResolver.openInputStream(shot)?.use { it.readBytes() }
                if (bytes == null) false
                else {
                    contentResolver.openOutputStream(page.uri, "wt")?.use { it.write(bytes) }
                    true
                }
            } catch (e: Exception) {
                false
            }
            if (ok) PageText.forget(this, book, page.name)   // 글자도 다시 읽어야 한다
            main.post {
                ui.pvBusy.visibility = View.GONE
                Toast.makeText(
                    this,
                    if (ok) "${at + 1}쪽을 다시 찍었습니다" else "덮어쓰지 못했습니다",
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }
    }

    private fun retake() {
        if (pages.isEmpty()) return
        DocScan.start(
            this,
            onReady = { sender ->
                retaker.launch(IntentSenderRequest.Builder(sender).build())
            },
            onFail = { message ->
                Toast.makeText(this, "정밀 촬영을 열지 못했습니다 — $message", Toast.LENGTH_LONG).show()
            },
        )
    }

    // ── 도구줄 ────────────────────────────────────────────────────

    /** 이 쪽의 글자를 통째로 복사한다. */
    private fun copyText() {
        val page = pages.getOrNull(at) ?: return
        workers.execute {
            val text = PageText.read(this, book, page.name, page.uri)
            main.post {
                if (text.isBlank()) {
                    Toast.makeText(this, "복사할 글자가 없습니다", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val clip = getSystemService(ClipboardManager::class.java)
                clip?.setPrimaryClip(ClipData.newPlainText("책스캔", text))
                Toast.makeText(this, "${at + 1}쪽 글자를 복사했습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 이 쪽 그림을 보낸다. */
    private fun shareImage() {
        val page = pages.getOrNull(at) ?: return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, page.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "${at + 1}쪽 보내기"))
        } catch (e: Exception) {
            Toast.makeText(this, "보내지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /** 이 쪽 글자를 보낸다. */
    private fun shareText() {
        val page = pages.getOrNull(at) ?: return
        workers.execute {
            val text = PageText.read(this, book, page.name, page.uri)
            main.post {
                if (text.isBlank()) {
                    Toast.makeText(this, "보낼 글자가 없습니다", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "${at + 1}쪽 글자 보내기"))
            }
        }
    }

    /** 아래에서 올라오는 더보기 판. */
    private fun showMore() {
        val view = layoutInflater.inflate(R.layout.sheet_more, null)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(view)

        fun on(id: Int, action: () -> Unit) {
            view.findViewById<View>(id).setOnClickListener {
                sheet.dismiss()
                action()
            }
        }
        on(R.id.moreReread) { reread() }
        on(R.id.moreTxt) { savePageText() }
        on(R.id.moreRetake) { retake() }
        on(R.id.moreLeft) { rotate(-90) }
        on(R.id.moreRight) { rotate(90) }
        on(R.id.moreShareText) { shareText() }
        on(R.id.moreBookTxt) { saveBookText() }
        on(R.id.moreDelete) { askDelete() }
        sheet.show()
    }

    /** 이 쪽 글자를 새로 인식한다(잘못 읽었을 때). */
    private fun reread() {
        val page = pages.getOrNull(at) ?: return
        showText = true
        ui.pvBusy.visibility = View.VISIBLE
        workers.execute {
            val text = PageText.read(this, book, page.name, page.uri, again = true)
            main.post {
                ui.pvBusy.visibility = View.GONE
                render()
                Toast.makeText(
                    this,
                    if (text.isBlank()) "글자를 찾지 못했습니다" else "다시 읽었습니다",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun savePageText() {
        val page = pages.getOrNull(at) ?: return
        val label = "${book}_${at + 1}쪽.txt"
        workers.execute {
            val text = PageText.read(this, book, page.name, page.uri)
            val ok = saveToDownloads(label, text.toByteArray())
            main.post {
                Toast.makeText(
                    this,
                    if (ok) "내려받기 폴더에 $label" else "저장하지 못했습니다",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun saveBookText() {
        val snapshot = ArrayList(pages)
        val label = "책스캔_$book.txt"
        ui.pvBusy.visibility = View.VISIBLE
        workers.execute {
            for (page in snapshot) PageText.read(this, book, page.name, page.uri)
            val ok = saveToDownloads(label, PageText.wholeBook(this, book, snapshot).toByteArray())
            main.post {
                ui.pvBusy.visibility = View.GONE
                Toast.makeText(
                    this,
                    if (ok) "내려받기 폴더에 $label" else "저장하지 못했습니다",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun saveToDownloads(name: String, bytes: ByteArray): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 이 쪽 그림을 돌려 그대로 저장한다. */
    private fun rotate(degrees: Int) {
        val page = pages.getOrNull(at) ?: return
        ui.pvBusy.visibility = View.VISIBLE
        workers.execute {
            val ok = turn(page, degrees)
            if (ok) PageText.forget(this, book, page.name)
            main.post {
                ui.pvBusy.visibility = View.GONE
                if (!ok) Toast.makeText(this, "돌리지 못했습니다", Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private fun turn(page: Library.Page, degrees: Int): Boolean {
        return try {
            val source = contentResolver.openInputStream(page.uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return false
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val turned = Bitmap.createBitmap(
                source, 0, 0, source.width, source.height, matrix, true
            )
            contentResolver.openOutputStream(page.uri, "wt")?.use { out ->
                turned.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            if (turned !== source) turned.recycle()
            source.recycle()
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun move(step: Int) {
        if (pages.isEmpty()) return
        at = (at + step).coerceIn(0, pages.size - 1)
        render()
    }

    private fun render() {
        if (pages.isEmpty()) return
        val page = pages[at]
        ui.pvCount.text = "${at + 1} / ${pages.size}"
        ui.pvMode.text = if (showText) "이미지 보기" else "텍스트 보기"
        ui.pvPrev.isEnabled = at > 0
        ui.pvNext.isEnabled = at < pages.size - 1

        if (!showText) {
            ui.pvTextBox.visibility = View.GONE
            ui.pvImage.visibility = View.VISIBLE
            ui.pvBusy.visibility = View.VISIBLE
            ui.pvImage.setImageDrawable(null)
            val want = page.uri
            workers.execute {
                val bitmap = Library.thumbnail(this, want, 1600)
                main.post {
                    if (pages.getOrNull(at)?.uri == want) {
                        ui.pvImage.setImageBitmap(bitmap)
                        ui.pvBusy.visibility = View.GONE
                    }
                }
            }
            return
        }

        // 텍스트 — 읽어 둔 것이 있으면 바로, 없으면 지금 읽는다
        ui.pvImage.visibility = View.GONE
        ui.pvTextBox.visibility = View.VISIBLE
        val ready = PageText.cached(this, book, page.name)
        if (ready != null) {
            ui.pvText.text = ready.ifBlank { "(글자를 찾지 못했습니다)" }
            ui.pvBusy.visibility = View.GONE
            return
        }
        ui.pvText.text = ""
        ui.pvBusy.visibility = View.VISIBLE
        val want = page.uri
        workers.execute {
            val text = PageText.read(this, book, page.name, want)
            main.post {
                if (pages.getOrNull(at)?.uri == want && showText) {
                    ui.pvText.text = text.ifBlank { "(글자를 찾지 못했습니다)" }
                    ui.pvBusy.visibility = View.GONE
                }
            }
        }
    }
}
