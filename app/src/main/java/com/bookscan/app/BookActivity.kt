package com.bookscan.app

import androidx.appcompat.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bookscan.app.databinding.ActivityBookBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * **책 한 권** — 찍어 둔 쪽들을 번호 순으로 보여주고, 아래 도구줄로 한꺼번에 처리한다.
 *
 * 쪽을 누르면 「◀ 앞으로 / 뒤로 ▶ / 삭제」가 뜬다. 순서를 바꾸면 파일 이름이
 * 001.jpg부터 다시 매겨지므로 PC로 옮겨도 그대로 읽힌다.
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

    /** 갤러리에서 사진을 골라 이 책에 넣는다. */
    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) importPhotos(uris) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        ui = ActivityBookBinding.inflate(layoutInflater)
        setContentView(ui.root)
        book = intent.getStringExtra(EXTRA_BOOK).orEmpty()
        ui.bookTitle.text = book

        ui.pageGrid.layoutManager = GridLayoutManager(this, 3)
        ui.pageGrid.adapter = PageAdapter()

        ui.bookBack.setOnClickListener { finish() }
        ui.bookShoot.setOnClickListener { askHowToShoot() }
        ui.pageUp.setOnClickListener { movePage(-1) }
        ui.pageDown.setOnClickListener { movePage(1) }
        ui.pageDelete.setOnClickListener { askDelete() }

        ui.actImport.setOnClickListener { picker.launch(arrayOf("image/*")) }
        ui.actText.setOnClickListener { askReadText() }
        ui.actShare.setOnClickListener { sharePages() }
        ui.actPdf.setOnClickListener { makePdf() }
        ui.actMore.setOnClickListener { showMore() }
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

    private fun say(text: String) = main.post { ui.bookStatus.text = text }

    /** 쪽 하나를 크게 본다(텍스트로 열지도 고를 수 있다). */
    private fun openPage(index: Int, asText: Boolean) {
        startActivity(
            Intent(this, PageViewActivity::class.java)
                .putExtra(PageViewActivity.EXTRA_BOOK, book)
                .putExtra(PageViewActivity.EXTRA_AT, index)
                .putExtra(PageViewActivity.EXTRA_TEXT_FIRST, asText)
        )
    }

    // ── 순서 고치기 ───────────────────────────────────────────────

    private fun movePage(step: Int) {
        val from = chosen
        val to = from + step
        if (from < 0 || to !in pages.indices) return
        say("순서 바꾸는 중…")
        workers.execute {
            val ok = Library.move(this, book, from, to)
            val found = Library.pages(this, book)
            main.post {
                pages.clear(); pages.addAll(found)
                chosen = if (ok) to else from
                ui.pageGrid.adapter?.notifyDataSetChanged()
                ui.bookStatus.text = if (ok) "${to + 1}쪽으로 옮겼습니다" else "옮기지 못했습니다"
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

    // ── 촬영 ──────────────────────────────────────────────────────

    /** 정밀 촬영(구글 스캐너)이 돌려준 쪽들을 받는다. */
    private val scanner = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pages = DocScan.pagesOf(result.data)
        if (pages.isEmpty()) return@registerForActivityResult
        say("찍은 ${pages.size}쪽을 넣는 중…")
        workers.execute {
            val added = DocScan.store(this, book, pages)
            val found = Library.pages(this, book)
            main.post {
                this.pages.clear(); this.pages.addAll(found)
                ui.pageGrid.adapter?.notifyDataSetChanged()
                ui.bookStatus.text = "${added}쪽 추가 — 모두 ${this.pages.size}쪽"
            }
        }
    }

    private fun askHowToShoot() {
        AlertDialog.Builder(this)
            .setTitle("어떻게 찍을까요")
            .setItems(arrayOf("정밀 촬영 (구글 · ⊕로 계속)", "연속 촬영 (우리 화면)")) { _, which ->
                if (which == 0) startPrecision() else startActivity(
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_BOOK, book)
                )
            }
            .show()
    }

    private fun startPrecision() {
        DocScan.start(
            this,
            onReady = { sender ->
                scanner.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
            },
            onFail = { message -> say("정밀 촬영을 열지 못했습니다 — $message") },
        )
    }

    // ── 가져오기 ──────────────────────────────────────────────────

    /** 갤러리·파일에서 고른 사진을 이 책 뒤에 붙이고 곧바로 다듬는다. */
    private fun importPhotos(uris: List<Uri>) {
        workers.execute {
            val start = Library.pagesIn(this, book, PhotoStore.RAW).size
            var added = 0
            for ((i, source) in uris.withIndex()) {
                say("가져오는 중… ${i + 1}/${uris.size}")
                val index = start + i + 1
                val raw = PhotoStore.newUri(this, book, index, PhotoStore.RAW) ?: continue
                val copied = try {
                    contentResolver.openInputStream(source)?.use { input ->
                        contentResolver.openOutputStream(raw)?.use { out ->
                            input.copyTo(out); true
                        } ?: false
                    } ?: false
                } catch (e: Exception) {
                    false
                }
                if (!copied) continue
                val target = PhotoStore.newUri(this, book, index)
                Cropper.autoCrop(
                    this, raw, target, book, index + 1, Cropper.PageMode.AUTO, StringBuilder()
                )
                added++
            }
            val found = Library.pages(this, book)
            main.post {
                pages.clear(); pages.addAll(found)
                ui.pageGrid.adapter?.notifyDataSetChanged()
                ui.bookStatus.text = "${added}장 가져와 다듬었습니다 — 모두 ${pages.size}쪽"
            }
        }
    }

    // ── 텍스트 인식 ───────────────────────────────────────────────

    private fun askReadText() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "쪽이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("텍스트 인식")
            .setMessage(
                "${pages.size}쪽의 글자를 읽습니다(인터넷 없이 폰에서). 끝나면 쪽마다 "
                    + "이미지↔텍스트를 오가며 볼 수 있고, 한 벌짜리 txt도 내려받기 폴더에 저장됩니다."
            )
            .setPositiveButton("시작") { _, _ -> readText() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun readText() {
        val snapshot = ArrayList(pages)
        workers.execute {
            var done = 0
            for ((i, page) in snapshot.withIndex()) {
                say("글자 읽는 중… ${i + 1}/${snapshot.size}")
                val text = PageText.read(this, book, page.name, page.uri)
                if (text.isNotBlank()) done++
            }
            // 한 벌짜리 txt 도 함께 남긴다(PC로 옮겨 쓰기 좋게)
            val name = "책스캔_$book.txt"
            val saved = saveToDownloads(
                name, "text/plain", PageText.wholeBook(this, book, snapshot).toByteArray()
            )
            main.post {
                ui.bookStatus.text =
                    "글자 읽기 끝 — ${done}/${snapshot.size}쪽" +
                        (if (saved) " · 내려받기 폴더에 $name" else "")
                if (snapshot.isNotEmpty()) openPage(0, true)   // 바로 텍스트로 열어 준다
            }
        }
    }

    // ── 공유·PDF ─────────────────────────────────────────────────

    private fun sharePages() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "쪽이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val one = chosen in pages.indices
        val list = if (one) arrayListOf(pages[chosen].uri) else ArrayList(pages.map { it.uri })
        try {
            val intent = if (list.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, list[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, if (one) "이 쪽 보내기" else "${list.size}쪽 보내기"))
        } catch (e: Exception) {
            Toast.makeText(this, "보내지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun makePdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "쪽이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        say("PDF 만드는 중… (${pages.size}쪽)")
        val snapshot = ArrayList(pages)
        workers.execute {
            val photos = snapshot.map { page -> { contentResolver.openInputStream(page.uri) } }
            val name = "책스캔_$book.pdf"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), name)
            val ok = PdfMaker.build(photos, file)
            val saved = ok && saveToDownloads(name, "application/pdf", file.readBytes())
            main.post {
                ui.bookStatus.text = when {
                    saved -> "내려받기 폴더에 $name 저장"
                    ok -> "PDF는 만들었지만 내려받기 폴더 저장은 실패"
                    else -> "PDF를 만들지 못했습니다"
                }
                if (ok) share(file)
            }
        }
    }

    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
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

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
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

    // ── 더보기 ────────────────────────────────────────────────────

    private fun showMore() {
        val items = arrayOf("다시 다듬기(원본 전체)", "책 이름 바꾸기", "책 지우기")
        AlertDialog.Builder(this)
            .setTitle(book)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askRedo()
                    1 -> askRename()
                    2 -> askRemoveBook()
                }
            }
            .show()
    }

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
        workers.execute {
            for (page in Library.pagesIn(this, book, PhotoStore.DONE)) {
                try {
                    contentResolver.delete(page.uri, null, null)
                } catch (e: Exception) {
                    // 못 지운 것은 아래에서 덮어쓴다
                }
            }
            var done = 0
            for ((i, page) in raw.withIndex()) {
                say("다시 다듬는 중… ${i + 1}/${raw.size}")
                val target = PhotoStore.newUri(this, book, i + 1)
                val result = Cropper.autoCrop(
                    this, page.uri, target, book, i + 2, Cropper.PageMode.AUTO, StringBuilder()
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
            }
        }
    }

    private fun askRename() {
        val input = android.widget.EditText(this).apply {
            setText(book)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("책 이름 바꾸기")
            .setView(input)
            .setPositiveButton("바꾸기") { _, _ ->
                val wanted = input.text.toString().trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (wanted.isBlank() || wanted == book) return@setPositiveButton
                workers.execute {
                    val name = Library.freshName(this, wanted)
                    val ok = Library.renameBook(this, book, name)
                    main.post {
                        if (ok) {
                            book = name
                            ui.bookTitle.text = name
                            reload()
                        } else {
                            ui.bookStatus.text = "이름을 바꾸지 못했습니다"
                        }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askRemoveBook() {
        AlertDialog.Builder(this)
            .setTitle("「$book」 지우기")
            .setMessage("이 책의 사진을 모두 지웁니다. 되돌릴 수 없습니다.")
            .setPositiveButton("지우기") { _, _ ->
                workers.execute {
                    Library.removeBook(this, book)
                    main.post { finish() }
                }
            }
            .setNegativeButton("그대로 두기", null)
            .show()
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
            // 누르면 크게 보기(이미지↔텍스트), 길게 누르면 순서 고치기
            holder.itemView.setOnClickListener { openPage(position, false) }
            holder.itemView.setOnLongClickListener {
                chosen = if (chosen == position) -1 else position
                ui.pageTools.visibility = if (chosen >= 0) View.VISIBLE else View.GONE
                notifyDataSetChanged()
                true
            }
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val box: View = view.findViewById(R.id.pageBox)
        val image: ImageView = view.findViewById(R.id.pageImage)
        val number: TextView = view.findViewById(R.id.pageNumber)
    }
}
