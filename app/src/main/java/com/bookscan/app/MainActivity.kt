package com.bookscan.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.MotionEvent
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

    private lateinit var ui: ActivityMainBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var sessionName = ""
    private var shotCount = 0

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

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "윤곽 인식 모듈을 불러오지 못했습니다.", Toast.LENGTH_LONG).show()
        }

        newSession()

        ui.shutter.setOnClickListener { capture() }
        ui.newSession.setOnClickListener { newSession() }
        ui.makePdf.setOnClickListener { makePdf() }
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

    // ── 촬영 묶음(세션) ───────────────────────────────────────────

    private fun newSession() {
        sessionName = SimpleDateFormat("yyMMdd_HHmm", Locale.KOREA).format(Date())
        shotCount = 0
        updateCount()
        ui.status.text = "새 묶음 — ${PhotoStore.folderHint(sessionName)}"
    }

    private fun updateCount() {
        ui.count.text = "$sessionName · ${shotCount}장"
        ui.makePdf.isEnabled = shotCount > 0
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
        ui.overlay.update(r.quad, r.srcWidth, r.srcHeight, r.allOk)

        val marks = buildString {
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
            else -> "지금 찍으세요 ✓"
        }
        val green = ContextCompat.getColor(this, R.color.ok)
        val amber = ContextCompat.getColor(this, R.color.wait)
        ui.status.setTextColor(if (r.allOk) green else amber)

        val now = System.currentTimeMillis()
        if (r.allOk) {
            if (goodSince == 0L) goodSince = now
            if (ui.autoShot.isChecked && !capturing &&
                now - goodSince > 800 && now - lastShotAt > 1800
            ) capture()
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

    // ── 촬영 ──────────────────────────────────────────────────────

    private fun capture() {
        val capture = imageCapture ?: return
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
                    // 저장 뒤 처리에서 나는 오류로 앱이 꺼지지 않게 감싼다
                    try {
                        updateCount()
                        buzz()
                        ui.status.text = "${shotCount}장째 저장 — 다음 쪽으로 넘기세요"
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
