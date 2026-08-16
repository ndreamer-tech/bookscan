package com.bookscan.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
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
