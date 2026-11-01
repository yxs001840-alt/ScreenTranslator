package com.example.screentranslator
import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.os.Build
import android.util.LruCache
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

class ScreenTranslateService : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayContainer: FrameLayout
    private lateinit var floatingBall: TextView
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null
    private var isTranslating = false
    private val translationCache = LruCache<String, String>(1000)
    private val ignoredPackages = setOf("com.android.systemui", "com.google.android.inputmethod.latin")
    private val languageOptions = listOf(
        "EN -> ZH" to TranslateLanguage.ENGLISH,
        "JA -> ZH" to TranslateLanguage.JAPANESE,
        "KO -> ZH" to TranslateLanguage.KOREAN,
        "FR -> ZH" to TranslateLanguage.FRENCH,
        "RU -> ZH" to TranslateLanguage.RUSSIAN
    )
    private var currentLangIndex = 0
    private var currentTranslator: Translator? = null
    private var languageMenuView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayContainer = FrameLayout(this)
        windowManager.addView(overlayContainer, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ))
        setupFloatingControlBall()
        updateTranslatorLanguage(0)
    }

    private fun setupFloatingControlBall() {
        val ballParams = WindowManager.LayoutParams(
            150, 150, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 400 }

        floatingBall = TextView(this).apply {
            text = "..."; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC1E88E5")); textSize = 13f
            var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f
            var longPressJob: Job? = null; var isLongPressed = false

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = ballParams.x; initialY = ballParams.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY; isLongPressed = false
                        longPressJob = serviceScope.launch { delay(500); isLongPressed = true; showLanguageMenu(ballParams.x, ballParams.y) }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                        if (abs(dx) > 15 || abs(dy) > 15) { longPressJob?.cancel(); dismissLanguageMenu() }
                        ballParams.x = initialX + dx.toInt(); ballParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(this, ballParams); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressJob?.cancel()
                        if (!isLongPressed && abs(event.rawX - initialTouchX) < 15 && abs(event.rawY - initialTouchY) < 15) {
                            if (languageMenuView != null) dismissLanguageMenu() else toggleTranslation()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        windowManager.addView(floatingBall, ballParams)
    }

    private fun updateTranslatorLanguage(index: Int) {
        currentLangIndex = index
        currentTranslator?.close(); translationCache.evictAll()
        val client = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(languageOptions[index].second).setTargetLanguage(TranslateLanguage.CHINESE).build())
        currentTranslator = client; floatingBall.text = "..."
        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { updateFloatingBallUI(); if (isTranslating) triggerDebouncedScan() }
            .addOnFailureListener { floatingBall.text = "ERR" }
    }

    private fun updateFloatingBallUI() {
        val s = languageOptions[currentLangIndex].first.substring(0, 2)
        floatingBall.text = if (isTranslating) "$s:ON" else "$s:OFF"
        floatingBall.setBackgroundColor(Color.parseColor(if (isTranslating) "#CC43A047" else "#CC1E88E5"))
    }

    private fun toggleTranslation() {
        isTranslating = !isTranslating; updateFloatingBallUI()
        if (isTranslating) triggerDebouncedScan() else overlayContainer.removeAllViews()
    }

    private fun showLanguageMenu(ballX: Int, ballY: Int) {
        dismissLanguageMenu()
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F2263238")); setPadding(16, 16, 16, 16)
        }
        languageOptions.forEachIndexed { idx, (title, _) ->
            menu.addView(TextView(this).apply {
                text = if (idx == currentLangIndex) "* $title" else "  $title"
                setTextColor(if (idx == currentLangIndex) Color.parseColor("#81C784") else Color.WHITE)
                textSize = 14f; setPadding(28, 20, 40, 20)
                setOnClickListener { dismissLanguageMenu(); updateTranslatorLanguage(idx) }
            })
        }
        languageMenuView = menu
        windowManager.addView(menu, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = ballX + 160; y = ballY })
    }

    private fun dismissLanguageMenu() { languageMenuView?.let { windowManager.removeView(it); languageMenuView = null } }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isTranslating || event == null) return
        val pkg = event.packageName?.toString() ?: ""
        if (pkg == packageName || ignoredPackages.contains(pkg)) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) overlayContainer.removeAllViews()
        triggerDebouncedScan()
    }

    private fun triggerDebouncedScan() {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(250)
            val root = rootInActiveWindow ?: return@launch
            val nodes = mutableListOf<Pair<Rect, String>>()
            collectTextNodes(root, nodes)
            if (nodes.isEmpty()) return@launch
            overlayContainer.removeAllViews(); delay(16)
            val bmp = captureScreenBitmap()
            nodes.forEach { (rect, src) ->
                val colors = if (bmp != null) ColorExtractor.extractColors(bmp, rect) else ThemeColors(Color.WHITE, Color.BLACK)
                launch {
                    val dst = getTranslatedText(src)
                    if (dst.isNotBlank() && dst != src) drawOverlayBlock(rect, dst, colors)
                }
            }
            bmp?.recycle()
        }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, res: MutableList<Pair<Rect, String>>) {
        if (node == null || !node.isVisibleToUser) return
        val txt = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!txt.isNullOrEmpty() && shouldTranslate(txt)) {
            val r = Rect(); node.getBoundsInScreen(r)
            if (r.width() > 30 && r.height() > 20 && r.top >= 0) res.add(r to txt)
        }
        for (i in 0 until node.childCount) collectTextNodes(node.getChild(i), res)
    }

    private fun shouldTranslate(t: String): Boolean {
        if (t.length < 2 || t.startsWith("http")) return false
        return when (languageOptions[currentLangIndex].second) {
            TranslateLanguage.JAPANESE -> t.any { it.code in 0x3040..0x30FF }
            TranslateLanguage.KOREAN -> t.any { it.code in 0xAC00..0xD7AF || it.code in 0x3130..0x318F }
            TranslateLanguage.RUSSIAN -> t.any { it.code in 0x0400..0x04FF }
            else -> t.any { it in 'a'..'z' || it in 'A'..'Z' || it.code in 0x00C0..0x00FF }
        }
    }

    private suspend fun getTranslatedText(src: String): String {
        translationCache.get(src)?.let { return it }
        val c = currentTranslator ?: return ""
        return try { c.translate(src).await().also { translationCache.put(src, it) } } catch (e: Exception) { "" }
    }

    private suspend fun captureScreenBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cont.resume(null, null); return@suspendCancellableCoroutine }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(s: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle(); s.hardwareBuffer.close()
                if (cont.isActive) cont.resume(sw, null)
            }
            override fun onFailure(e: Int) { if (cont.isActive) cont.resume(null, null) }
        })
    }

    private fun drawOverlayBlock(rect: Rect, text: String, colors: ThemeColors) {
        val tv = TextView(this).apply {
            this.text = text; setBackgroundColor(colors.backgroundColor); setTextColor(colors.textColor)
            setPadding(4, 0, 4, 0); gravity = Gravity.CENTER_VERTICAL
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 9, 16, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        overlayContainer.addView(tv, FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            leftMargin = rect.left; topMargin = rect.top
        })
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy(); serviceScope.cancel(); currentTranslator?.close(); dismissLanguageMenu()
        if (::overlayContainer.isInitialized) windowManager.removeView(overlayContainer)
        if (::floatingBall.isInitialized) windowManager.removeView(floatingBall)
    }
}
