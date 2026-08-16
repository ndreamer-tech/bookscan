package com.bookscan.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bookscan.app.databinding.ActivityPageBinding
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
