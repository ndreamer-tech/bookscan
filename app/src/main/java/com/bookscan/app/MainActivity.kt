package com.bookscan.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bookscan.app.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        /** 서재에서 고른 책 이름(없으면 새 묶음을 만든다) */
        const val EXTRA_BOOK = "book"
    }

    private lateinit var ui: ActivityMainBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var sessionName = ""
    private var shotCount = 0

    private var lastShotSignature: IntArray? = null
    private var pendingSignature: IntArray? = null
    private var lastUri: Uri? = null
    private var pageMode = Cropper.PageMode.AUTO
    private var smoothQuad: FloatArray? = null
    private var cvReady = false
    private var cvError = ""
    private var goodSince = 0L
    private var lastShotAt = 0L
    private var lastFocusAt = 0L
    private var capturing = false

    private val askCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        cvReady = loadOpenCv()

        newSession()
        writeDiagnostic("시작 v" + BuildConfig.VERSION_NAME + " CV=" +
            (if (cvReady) "O" else "X (" + cvError + ")"))
        if (!cvReady) {
            Toast.makeText(this, "영상처리 모듈 적재 실패: " + cvError, Toast.LENGTH_LONG).show()
        }

        ui.shutter.setOnClickListener { capture() }
        ui.newSession.setOnClickListener { newSession() }
        ui.makePdf.setOnClickListener { makePdf() }
        ui.thumb.setOnClickListener { showLastPhoto() }
        ui.openFolder.setOnClickListener { openFolder() }
        ui.pageMode.setOnClickListener { cyclePageMode() }
        ui.openFolder.setOnLongClickListener { shareDiagnostic(); true }
        ui.preview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) focusAt(event.x, event.y)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    /** 영상처리 모듈을 올린다. 실패하면 그 이유를 그대로 남긴다. */
    private fun loadOpenCv(): Boolean {
        try {
            System.loadLibrary("opencv_java4")
            return true
        } catch (e: Throwable) {
            cvError = e.message?.take(120) ?: e.javaClass.simpleName
        }
        return try {
            val ok = OpenCVLoader.initLocal()
            if (!ok && cvError.isEmpty()) cvError = "initLocal=false"
            ok
        } catch (e: Throwable) {
            if (cvError.isEmpty()) cvError = e.message?.take(120) ?: "알 수 없음"
            false
        }
    }

    // ── 촬영 묶음(세션) ───────────────────────────────────────────

    private fun newSession() {
        val given = intent.getStringExtra(EXTRA_BOOK)
        sessionName = given ?: SimpleDateFormat("yyMMdd_HHmm", Locale.KOREA).format(Date())
        // 서재에서 「이어 찍기」로 들어오면 이미 찍힌 장수 뒤부터 번호를 잇는다
        shotCount = if (given != null) PhotoStore.photosIn(this, sessionName, PhotoStore.RAW).size else 0
        lastShotSignature = null
        lastUri = null
        ui.thumb.visibility = View.GONE
        ui.thumbBadge.visibility = View.GONE
        updateCount()
        ui.status.text = if (shotCount > 0) {
            "「$sessionName」 이어 찍기 — ${shotCount}쪽 다음부터"
        } else {
            "새 책 — ${PhotoStore.folderHint(sessionName)} (원본/처리)"
        }
    }

    private fun updateCount() {
        ui.count.text = "$sessionName · ${shotCount}장"
        ui.makePdf.isEnabled = shotCount > 0
    }

    /** 쪽 모드: 자동 → 한 쪽 → 두 쪽 → 자동 … */
    private fun cyclePageMode() {
        pageMode = when (pageMode) {
            Cropper.PageMode.AUTO -> Cropper.PageMode.SINGLE
            Cropper.PageMode.SINGLE -> Cropper.PageMode.SPREAD
            Cropper.PageMode.SPREAD -> Cropper.PageMode.AUTO
        }
        ui.pageMode.setText(
            when (pageMode) {
                Cropper.PageMode.AUTO -> R.string.mode_auto
                Cropper.PageMode.SINGLE -> R.string.mode_single
                Cropper.PageMode.SPREAD -> R.string.mode_spread
            }
        )
        ui.status.text = when (pageMode) {
            Cropper.PageMode.AUTO -> "가로로 넓으면 두 쪽으로 나눠 저장합니다"
            Cropper.PageMode.SINGLE -> "한 쪽씩 찍습니다 — 나누지 않습니다"
            Cropper.PageMode.SPREAD -> "펼친 책을 책등에서 좌·우로 나눕니다"
        }
    }

    // ── 카메라 ────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(selector)
                .build()
                .also { it.setSurfaceProvider(ui.preview.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, this::analyze) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture
                )
                ui.preview.scaleType = PreviewView.ScaleType.FIT_CENTER
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 열지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 화면의 한 점에 초점·노출을 맞춘다(화면을 누르거나, 흐릿할 때 자동으로). */
    private fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = ui.preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        control.startFocusAndMetering(action)
        lastFocusAt = System.currentTimeMillis()
    }

    // ── 실시간 판정 ───────────────────────────────────────────────

    private fun analyze(image: androidx.camera.core.ImageProxy) {
        try {
            val result = PageDetector.analyze(image)
            runOnUiThread { onResult(result) }
        } catch (e: Throwable) {
            // 한 프레임 실패는 넘어간다
        } finally {
            image.close()
        }
    }

    private fun onResult(r: PageDetector.Result) {
        // 화면 윤곽은 조금씩 흔들리므로 부드럽게 이어 그린다(눈에 안정적이고 판정도 덜 튄다)
        ui.overlay.update(smoothed(r), r.srcWidth, r.srcHeight, r.allOk)

        val marks = buildString {
            append("v")
            append(BuildConfig.VERSION_NAME)
            append(if (cvReady) "(CV✓)  " else "(CV✗)  ")
            append(if (r.hasQuad) "윤곽 ✓" else "윤곽 ✗")
            append(if (r.fillOk) "  채움 ✓" else "  채움 ✗")
            append(if (r.sharpOk) "  초점 ✓" else "  초점 ✗")
            append(if (r.brightOk) "  밝기 ✓" else "  밝기 ✗")
            append(if (r.skewOk) "  각도 ✓" else "  각도 ✗")
        }
        ui.marks.text = marks
        ui.status.text = when {
            !r.hasQuad -> "책 페이지가 다 보이도록 비춰 주세요"
            !r.fillOk -> "조금 더 가까이 — 페이지로 네모칸을 채우세요"
            !r.sharpOk -> "잠깐 멈춰 주세요 (초점 맞추는 중)"
            !r.brightOk -> if (r.brightness < PageDetector.BRIGHT_MIN) "더 밝은 곳에서 찍어 주세요"
            else "빛이 너무 셉니다 — 반사를 피해 주세요"
            !r.skewOk -> "책 위에서 똑바로 내려다보세요"
            PageDetector.sameScene(r.signature, lastShotSignature) -> "이미 찍은 쪽입니다 — 다음 쪽으로 넘기세요"
            else -> "지금 찍으세요 ✓"
        }
        val green = ContextCompat.getColor(this, R.color.ok)
        val amber = ContextCompat.getColor(this, R.color.wait)
        ui.status.setTextColor(if (r.allOk) green else amber)

        val now = System.currentTimeMillis()
        if (r.allOk) {
            if (goodSince == 0L) goodSince = now
            val newPage = !PageDetector.sameScene(r.signature, lastShotSignature)
            if (ui.autoShot.isChecked && !capturing && newPage &&
                now - goodSince > 800 && now - lastShotAt > 1500
            ) capture(r.signature)
        } else {
            goodSince = 0L
            // 흐릿하면 페이지 가운데에 초점을 다시 맞춘다
            if (!r.sharpOk && r.hasQuad && now - lastFocusAt > 2200) {
                val q = r.quad!!
                val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
                val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
                val scale = minOf(
                    ui.preview.width.toFloat() / r.srcWidth,
                    ui.preview.height.toFloat() / r.srcHeight
                )
                val dx = (ui.preview.width - r.srcWidth * scale) / 2f
                val dy = (ui.preview.height - r.srcHeight * scale) / 2f
                focusAt(cx * scale + dx, cy * scale + dy)
            }
        }
    }

    /** 직전 윤곽과 크게 다르지 않으면 살짝 섞어 떨림을 줄인다. */
    private fun smoothed(r: PageDetector.Result): FloatArray? {
        val quad = r.quad
        if (quad == null) {
            smoothQuad = null
            return null
        }
        val prev = smoothQuad
        if (prev == null || prev.size != quad.size) {
            smoothQuad = quad.copyOf()
            return smoothQuad
        }
        val limit = 0.12f * maxOf(r.srcWidth, r.srcHeight)
        var moved = 0f
        for (i in 0 until 4) {
            val dx = quad[i * 2] - prev[i * 2]
            val dy = quad[i * 2 + 1] - prev[i * 2 + 1]
            moved = maxOf(moved, kotlin.math.hypot(dx, dy))
        }
        if (moved > limit) {          // 다른 곳을 비추기 시작했다 — 새로 잡는다
            smoothQuad = quad.copyOf()
            return smoothQuad
        }
        val alpha = 0.35f
        val out = FloatArray(8)
        for (i in 0 until 8) out[i] = prev[i] * (1 - alpha) + quad[i] * alpha
        smoothQuad = out
        return out
    }

    // ── 촬영 ──────────────────────────────────────────────────────

    private fun capture(signature: IntArray? = null) {
        val capture = imageCapture ?: return
        pendingSignature = signature ?: pendingSignature
        if (capturing) return
        capturing = true
        val options = try {
            PhotoStore.outputOptions(this, sessionName, shotCount + 1)
        } catch (e: Exception) {
            capturing = false
            Toast.makeText(this, "저장 위치를 준비하지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        capture.takePicture(
            options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    lastShotAt = System.currentTimeMillis()
                    goodSince = 0L
                    shotCount++
                    lastShotSignature = pendingSignature
                    // 저장 뒤 처리에서 나는 오류로 앱이 꺼지지 않게 감싼다
                    try {
                        updateCount()
                        buzz()
                        finishShot(output.savedUri)
                    } catch (e: Throwable) {
                        ui.status.text = "${shotCount}장째 저장"
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(this@MainActivity, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun buzz() {
        try {
            val effect = VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(effect)
            }
        } catch (e: Throwable) {
            // 진동이 안 되는 폰이어도 촬영은 계속된다
        }
    }

    /** 저장된 사진을 윤곽대로 잘라내고 오른쪽 아래 미리보기를 갱신한다. */
    private fun finishShot(uri: Uri?) {
        lastUri = uri
        ui.status.text = "${shotCount}장째 저장 — 다듬는 중…"
        val index = shotCount

        Thread {
            // 원본은 그대로 두고, 다듬은 결과는 '처리' 폴더에 새 파일로 저장한다
            val log = StringBuilder("[").append(index).append("] ")
            val target = uri?.let { PhotoStore.newUri(this, sessionName, index) }
            log.append(if (uri != null) " 원본O" else " 원본X")
            log.append(if (target != null) " 자리O" else " 자리X")
            log.append(if (cvReady) " CV O" else " CV X")
            val result = if (uri != null) {
                Cropper.autoCrop(this, uri, target, sessionName, shotCount + 1, pageMode, log)
            } else Cropper.Result(false, false, "사진없음")
            log.append(" → ").append(result.how)
            val diagnosis = log.toString()
            writeDiagnostic(diagnosis)
            runOnUiThread {
                if (!result.cropped) {
                    Toast.makeText(this, diagnosis, Toast.LENGTH_LONG).show()
                }
            }
            if (result.split) shotCount++
            if (result.cropped && target != null) lastUri = target
            val thumb = (if (result.cropped) target else uri)?.let { loadThumb(it) }
            runOnUiThread {
                if (thumb != null) {
                    ui.thumb.setImageBitmap(thumb)
                    ui.thumb.visibility = View.VISIBLE
                    ui.thumbBadge.text = shotCount.toString()
                    ui.thumbBadge.visibility = View.VISIBLE
                }
                updateCount()
                ui.status.text = when {
                    result.split -> "두 쪽 저장 (${shotCount - 1}·${shotCount}쪽) [${result.how}]"
                    result.cropped -> "${shotCount}장째 잘라 저장 [${result.how}] · 다음 쪽으로"
                    else -> "${shotCount}장째 — 다듬기 실패 [${result.how}] · 원본만 저장됨"
                }
            }
        }.start()
    }

    /** 무엇이 왜 안 됐는지 남긴다(폴더 버튼을 길게 누르면 이 파일을 보낼 수 있다). */
    private fun writeDiagnostic(line: String) {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return
            dir.mkdirs()
            File(dir, "진단_$sessionName.txt").appendText(line + System.lineSeparator())
        } catch (e: Throwable) {
            // 진단 기록 실패는 촬영에 영향을 주지 않는다
        }
    }

    private fun loadThumb(uri: Uri): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 400) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Throwable) {
            null
        }
    }

    /** 미리보기를 누르면 방금 찍은 사진을 크게 본다. */
    private fun showLastPhoto() {
        val uri = lastUri ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "사진을 열 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /** 진단 기록을 보낸다(무엇이 왜 안 잘렸는지 확인용). */
    private fun shareDiagnostic() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val file = File(dir, "진단_$sessionName.txt")
        if (!file.exists()) {
            Toast.makeText(this, "아직 기록이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "진단 기록 보내기"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "보내지 못했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 이번 묶음이 저장된 폴더를 연다(안 되면 갤러리를 연다). */
    private fun openFolder() {
        val path = "Pictures/${PhotoStore.ROOT}/$sessionName/${PhotoStore.DONE}"
        val tries = mutableListOf<Intent>()
        try {
            val docUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$path"
            )
            tries += Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            tries += Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
            }
        } catch (e: Exception) {
            // 문서 앱이 없는 폰
        }
        tries += Intent(Intent.ACTION_VIEW).setType("image/*")

        for (intent in tries) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // 다음 방법으로
            }
        }
        Toast.makeText(this, "저장 위치: ${PhotoStore.folderHint(sessionName)}", Toast.LENGTH_LONG).show()
    }

    // ── PDF로 묶기 ────────────────────────────────────────────────

    private fun makePdf() {
        val photos = PhotoStore.photosOf(this, sessionName)
        if (photos.isEmpty()) {
            Toast.makeText(this, "찍은 사진이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        ui.status.text = "PDF 만드는 중… (${photos.size}장)"
        val name = "책스캔_$sessionName.pdf"
        val target = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), name)

        Thread {
            val ok = PdfMaker.build(photos, target)
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "PDF를 만들지 못했습니다.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val saved = copyToDownloads(target, name)
                ui.status.text = if (saved) "다운로드 폴더에 $name 저장" else "PDF 준비 완료"
                sharePdf(target)
            }
        }.start()
    }

    /** 폰의 다운로드 폴더에도 넣어 준다(파일 앱·USB로 바로 꺼내가기 좋게). */
    private fun copyToDownloads(pdf: File, name: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/책스캔")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                contentResolver.openOutputStream(uri)?.use { out -> pdf.inputStream().use { it.copyTo(out) } }
                true
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                pdf.copyTo(File(dir, name), overwrite = true)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun sharePdf(pdf: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "PDF 보내기"))
    }
}
