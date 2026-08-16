package com.bookscan.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bookscan.app.databinding.ActivityLibraryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * **서재** — 찍어 둔 책(폴더)들을 늘어놓는 첫 화면.
 *
 * 책을 누르면 쪽 목록으로 들어가고, 「＋ 새 책」을 누르면 이름을 정한 뒤 촬영으로 넘어간다.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var ui: ActivityLibraryBinding
    private val books = ArrayList<Library.Book>()
    private val workers = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        ui = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.bookGrid.layoutManager = GridLayoutManager(this, 3)
        ui.bookGrid.adapter = BookAdapter()
        ui.newBook.setOnClickListener { askNewBook() }

        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** 카메라와 **사진 읽기** 권한 — 서재가 이미 찍어 둔 책을 보려면 읽기가 필요하다. */
    private fun askPermissions() {
        val wanted = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.CAMERA)
        }
        val readPhotos =
            if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, readPhotos)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(readPhotos)
        }
        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 11)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        reload()
    }

    private fun reload() {
        workers.execute {
            val found = Library.books(this)
            main.post {
                books.clear()
                books.addAll(found)
                ui.bookGrid.adapter?.notifyDataSetChanged()
                ui.libEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
                ui.libTitle.text = if (books.isEmpty()) "책스캔 서재" else "책스캔 서재 · ${books.size}권"
            }
        }
    }

    /** 새 책 이름을 묻고 촬영으로 넘어간다. */
    private fun askNewBook() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(SimpleDateFormat("yyMMdd_HHmm", Locale.KOREA).format(Date()))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("새 책 이름")
            .setMessage("이 이름으로 폴더가 만들어집니다.")
            .setView(input)
            .setPositiveButton("찍기 시작") { _, _ ->
                val wanted = input.text.toString().trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (wanted.isBlank()) {
                    Toast.makeText(this, "이름을 적어주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                workers.execute {
                    val name = Library.freshName(this, wanted)
                    Library.remember(this, name)          // 사진이 없어도 서재에 남는다
                    val made = Library.createFolders(this, name)   // 폴더부터 만든다
                    main.post {
                        Toast.makeText(
                            this,
                            if (made) "「$name」 폴더를 만들었습니다" else "「$name」 시작",
                            Toast.LENGTH_SHORT
                        ).show()
                        openCamera(name)
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 정밀 촬영 중인 책 이름.
     *
     * 구글 스캐너는 딴 화면이라, 그 사이 우리 화면이 정리되면 이 값이 날아간다.
     * 그러면 찍은 사진을 어디에 넣을지 몰라 그냥 버려졌다 — 그래서 적어 둔다.
     */
    private var shooting: String
        get() = getSharedPreferences("bookscan", MODE_PRIVATE).getString("shooting", "").orEmpty()
        set(value) {
            getSharedPreferences("bookscan", MODE_PRIVATE)
                .edit().putString("shooting", value).apply()
        }

    /** 정밀 촬영(구글 스캐너) 결과를 새 책에 담는다. */
    private val scanner = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pages = DocScan.pagesOf(result.data)
        val book = shooting
        if (book.isBlank()) {
            Toast.makeText(this, "어느 책인지 잃어버렸습니다 — 서재에서 다시 시작해 주세요", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (pages.isEmpty()) {
            Toast.makeText(this, "찍은 쪽이 없습니다", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        Toast.makeText(this, "${pages.size}쪽 저장 중…", Toast.LENGTH_SHORT).show()
        workers.execute {
            val added = DocScan.store(this, book, pages)
            main.post {
                Toast.makeText(
                    this,
                    if (added > 0) "「$book」에 ${added}쪽 저장" else "저장하지 못했습니다",
                    Toast.LENGTH_LONG
                ).show()
                reload()
                if (added > 0) openBook(book)
            }
        }
    }

    /** 새 책을 어떻게 찍을지 고른다. */
    private fun openCamera(book: String) {
        shooting = book
        AlertDialog.Builder(this)
            .setTitle("어떻게 찍을까요")
            .setItems(arrayOf("정밀 촬영 (구글 · ⊕로 계속)", "연속 촬영 (우리 화면)")) { _, which ->
                if (which == 0) {
                    DocScan.start(
                        this,
                        onReady = { sender ->
                            scanner.launch(
                                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                            )
                        },
                        onFail = { message ->
                            Toast.makeText(this, "정밀 촬영을 열지 못했습니다 — $message", Toast.LENGTH_LONG).show()
                        },
                    )
                } else {
                    startActivity(
                        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_BOOK, book)
                    )
                }
            }
            .show()
    }

    private fun openBook(book: String) {
        startActivity(
            Intent(this, BookActivity::class.java).putExtra(BookActivity.EXTRA_BOOK, book)
        )
    }

    private fun askRemove(book: Library.Book) {
        AlertDialog.Builder(this)
            .setTitle("「${book.name}」 지우기")
            .setMessage("이 책의 사진을 모두 지웁니다. 되돌릴 수 없습니다.")
            .setPositiveButton("지우기") { _, _ ->
                workers.execute {
                    val ok = Library.removeBook(this, book.name)
                    main.post {
                        Toast.makeText(
                            this, if (ok) "지웠습니다" else "지우지 못했습니다", Toast.LENGTH_SHORT
                        ).show()
                        reload()
                    }
                }
            }
            .setNegativeButton("그대로 두기", null)
            .show()
    }

    private inner class BookAdapter : RecyclerView.Adapter<BookHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): BookHolder =
            BookHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            )

        override fun getItemCount() = books.size

        override fun onBindViewHolder(holder: BookHolder, position: Int) {
            val book = books[position]
            holder.name.text = book.name
            holder.count.text = "${book.pages}쪽"
            holder.cover.setImageDrawable(null)
            holder.cover.tag = book.cover
            book.cover?.let { uri ->
                workers.execute {
                    val bitmap = Library.thumbnail(this@LibraryActivity, uri)
                    main.post { if (holder.cover.tag === uri) holder.cover.setImageBitmap(bitmap) }
                }
            }
            holder.itemView.setOnClickListener { openBook(book.name) }
            holder.itemView.setOnLongClickListener { askRemove(book); true }
        }
    }

    private class BookHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.bookCover)
        val name: TextView = view.findViewById(R.id.bookName)
        val count: TextView = view.findViewById(R.id.bookCount)
    }
}
