package com.bookscan.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * **정밀 촬영** — 구글 ML Kit 문서 스캐너를 빌려 쓴다.
 *
 * 구글 드라이브·픽셀 카메라가 쓰는 것과 같은 엔진이라 테두리 검출과 자동 촬영이
 * 우리 규칙 기반 검출보다 훨씬 정확하다. 모델과 화면은 플레이 서비스가 내려주므로
 * 앱 용량은 거의 늘지 않고, 처리는 모두 폰 안에서 이뤄진다.
 *
 * 찍은 결과는 **그대로 우리 서재로 들어온다** — 책 폴더에 번호를 매겨 저장하므로
 * PDF·텍스트 인식·순서 바꾸기가 지금처럼 동작한다.
 */
object DocScan {

    /** 스캐너를 여는 데 쓸 IntentSender를 만든다(실패하면 onFail). */
    fun start(
        activity: Activity,
        onReady: (android.content.IntentSender) -> Unit,
        onFail: (String) -> Unit,
    ) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)          // 갤러리에서 가져오기도 허용
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            // 가장 단순한 화면 — 확인 화면에 「자르기 및 회전」만 남는다.
            // 필터·지우기 줄이 사라져 ⊕(다음 장)까지 손이 바로 간다.
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .build()
        try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(activity)
                .addOnSuccessListener { sender -> onReady(sender) }
                .addOnFailureListener { error ->
                    onFail(error.message ?: "정밀 촬영을 열지 못했습니다")
                }
        } catch (e: Throwable) {
            onFail(e.message ?: "정밀 촬영을 쓸 수 없는 기기입니다")
        }
    }

    /** 스캐너가 돌려준 쪽 그림들. */
    fun pagesOf(data: Intent?): List<Uri> {
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return emptyList()
        return result.pages.orEmpty().map { it.imageUri }
    }

    /**
     * 찍은 쪽들을 책 뒤에 붙인다.
     *
     * 이미 반듯하게 잘린 결과이므로 **처리 폴더에 그대로** 넣고, 나중에 다시 다듬을 수
     * 있도록 원본 폴더에도 같은 그림을 남긴다.
     */
    /** 마지막으로 저장이 어긋난 까닭 */
    var lastError: String = ""
        private set

    fun store(activity: Activity, book: String, pages: List<Uri>): Int {
        var added = 0
        lastError = ""
        val start = Library.pagesIn(activity, book, PhotoStore.RAW).size
        for ((i, source) in pages.withIndex()) {
            val index = start + i + 1
            val bytes = try {
                activity.contentResolver.openInputStream(source)?.use { it.readBytes() }
            } catch (e: Exception) {
                lastError = "사진 읽기 — " + (e.message ?: e.javaClass.simpleName)
                null
            } ?: continue
            var ok = false
            for (kind in listOf(PhotoStore.RAW, PhotoStore.DONE)) {
                val target = PhotoStore.newUri(activity, book, index, kind)
                if (target == null) {
                    lastError = "자리 만들기 — " + PhotoStore.lastError
                    continue
                }
                try {
                    activity.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                    ok = true
                } catch (e: Exception) {
                    lastError = "쓰기 — " + (e.message ?: e.javaClass.simpleName)
                }
            }
            if (ok) added++
        }
        return added
    }
}
