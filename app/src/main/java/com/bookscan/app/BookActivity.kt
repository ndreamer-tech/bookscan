package com.bookscan.app

import android.app.AlertDialog
import android.content.Intent
import android.content.ContentValues
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bookscan.app.databinding.ActivityBookBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * **책 한 권** — 찍어 둔 쪽들을 번호 순으로 보여주고, 순서를 고치거나 다시 다듬는다.
 *
 * 쪽을 누르면 아래에 「◀ 앞으로 / 뒤로 ▶ / 삭제」가 뜬다. 순서를 바꾸면 파일 이름이
 * 001.jpg부터 다시 매겨지므로, PC로 옮겨도 그대로 읽힌다.
 */
class BookActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK = "book"
    }

    private lateinit var ui: ActivityBookBinding
    private var book = ""
    private val pages = ArrayList<Library.Page>()
    private var chosen = -1
    private val workers = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        ui = ActivityBookBinding.inflate(layoutInflater)
        setContentView(ui.root)
        book = intent.getStringExtra(EXTRA_BOOK).orEmpty()
        ui.bookTitle.text = book

        ui.pageGrid.layoutManager = GridLayoutManager(this, 3)
        ui.pageGrid.adapter = PageAdapter()

        ui.bookBack.setOnClickListener { finish() }
        ui.bookShoot.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_BOOK, book)
            )
        }
        ui.bookRedo.setOnClickListener { askRedo() }
        ui.bookPdf.setOnClickListener { makePdf() }
        ui.pageUp.setOnClickListener { movePage(-1) }
        ui.pageDown.setOnClickListener { movePage(1) }
        ui.pageDelete.setOnClickListener { askDelete() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        workers.execute {
            val found = Library.pages(this, book)
            main.post {
                pages.clear()
                pages.addAll(found)
                chosen = -1
                ui.pageTools.visibility = View.GONE
                ui.pageGrid.adapter?.notifyDataSetChanged()
                ui.bookStatus.text = "${pages.size}쪽 · ${Library.folderHint(book)}"
            }
        }
    }

    // ── 순서 고치기 ───────────────────────────────────────────────

    private fun movePage(step: Int) {
        val from = chosen
        val to = from + step
        if (from < 0 || to !in pages.indices) return
        ui.bookStatus.text = "순서 바꾸는 중…"
        workers.execute {
            val ok = Library.move(this, book, from, to)
            val found = Library.pages(this, book)
            main.post {
                pages.clear(); pages.addAll(found)
                chosen = if (ok) to else from
                ui.pageGrid.adapter?.notifyDataSetChanged()
                ui.bookStatus.text =
                    if (ok) "${to + 1}쪽으로 옮겼습니다" else "옮기지 못했습니다"
            }
        }
    }

    private fun askDelete() {
        val at = chosen
        if (at !in pages.indices) return
        AlertDialog.Builder(this)
            .setTitle("${at + 1}쪽 지우기")
            .setMessage("이 쪽을 지우고 뒤 번호를 당깁니다.")
            .setPositiveButton("지우기") { _, _ ->
                workers.execute {
                    Library.remove(this, book, at)
                    val found = Library.pages(this, book)
                    main.post {
                        pages.clear(); pages.addAll(found)
                        chosen = -1
                        ui.pageTools.visibility = View.GONE
                        ui.pageGrid.adapter?.notifyDataSetChanged()
                        ui.bookStatus.text = "${pages.size}쪽 남았습니다"
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 일괄 다시 다듬기 ──────────────────────────────────────────

    private fun askRedo() {
        val raw = Library.pagesIn(this, book, PhotoStore.RAW)
        if (raw.isEmpty()) {
            Toast.makeText(this, "원본 사진이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("다시 다듬기")
            .setMessage("원본 ${raw.size}장을 처음부터 다시 잘라 다듬습니다. 지금 처리된 사진은 새것으로 바뀝니다.")
            .setPositiveButton("시작") { _, _ -> redoAll(raw) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun redoAll(raw: List<Library.Page>) {
        ui.bookRedo.isEnabled = false
        workers.execute {
            // 지금 처리본을 먼저 비운다(번호가 어긋나지 않게)
            for (page in Library.pagesIn(this, book, PhotoStore.DONE)) {
                try {
                    contentResolver.delete(page.uri, null, null)
                } catch (e: Exception) {
                    // 지우지 못한 것은 아래에서 덮어쓴다
                }
            }
            var done = 0
            for ((i, page) in raw.withIndex()) {
                main.post { ui.bookStatus.text = "다시 다듬는 중… ${i + 1}/${raw.size}" }
                val target = PhotoStore.newUri(this, book, i + 1)
                val log = StringBuilder()
                val result = Cropper.autoCrop(
                    this, page.uri, target, book, i + 2, Cropper.PageMode.AUTO, log
                )
                if (result.cropped) done++
            }
            val found = Library.pages(this, book)
            main.post {
                pages.clear(); pages.addAll(found)
                chosen = -1
                ui.pageTools.visibility = View.GONE
                ui.pageGrid.adapter?.notifyDataSetChanged()
                ui.bookStatus.text = "다시 다듬기 끝 — ${done}/${raw.size}장"
                ui.bookRedo.isEnabled = true
            }
        }
    }

    // ── PDF ───────────────────────────────────────────────────────

    private fun makePdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "쪽이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        ui.bookPdf.isEnabled = false
        ui.bookStatus.text = "PDF 만드는 중… (${pages.size}쪽)"
        val snapshot = ArrayList(pages)
        workers.execute {
            val photos = snapshot.map { page ->
                { contentResolver.openInputStream(page.uri) }
            }
            val name = "책스캔_$book.pdf"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), name)
            val ok = PdfMaker.build(photos, file)
            val saved = ok && copyToDownloads(file, name)
            main.post {
                ui.bookStatus.text = when {
                    saved -> "다운로드 폴더에 $name 저장"
                    ok -> "PDF 준비 완료(내려받기 폴더 저장은 실패)"
                    else -> "PDF를 만들지 못했습니다"
                }
                ui.bookPdf.isEnabled = true
                if (ok) share(file)
            }
        }
    }

    /** 만든 PDF를 내려받기 폴더에 복사한다(폰 파일 앱에서 바로 보이게). */
    private fun copyToDownloads(file: File, name: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun share(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "PDF 보내기"
                )
            )
        } catch (e: Exception) {
            // 공유는 못 해도 파일은 이미 저장돼 있다
        }
    }

    // ── 목록 ──────────────────────────────────────────────────────

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): PageHolder =
            PageHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
            )

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = pages[position]
            holder.number.text = "${position + 1}"
            holder.box.setBackgroundColor(
                if (position == chosen) Color.parseColor("#2F6FEB") else Color.TRANSPARENT
            )
            holder.image.setImageDrawable(null)
            holder.image.tag = page.uri
            workers.execute {
                val bitmap = Library.thumbnail(this@BookActivity, page.uri)
                main.post { if (holder.image.tag === page.uri) holder.image.setImageBitmap(bitmap) }
            }
            holder.itemView.setOnClickListener {
                chosen = if (chosen == position) -1 else position
                ui.pageTools.visibility = if (chosen >= 0) View.VISIBLE else View.GONE
                notifyDataSetChanged()
            }
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val box: View = view.findViewById(R.id.pageBox)
        val image: ImageView = view.findViewById(R.id.pageImage)
        val number: TextView = view.findViewById(R.id.pageNumber)
    }
}
