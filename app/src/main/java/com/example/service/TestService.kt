package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.local.entity.RouterProfileEntity
import com.example.data.local.entity.TestResultEntity
import com.example.data.local.preferences.AppPreferences
import com.example.domain.model.LogLevel
import com.example.domain.repository.IRouterRepository
import com.example.domain.repository.ISessionRepository
import com.example.domain.repository.ITestResultRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class TestService : Service(), KoinComponent {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val ACTION_RETRY_LOAD = "ACTION_RETRY_LOAD"

        const val EXTRA_ROUTER_ID = "EXTRA_ROUTER_ID"
        const val EXTRA_CARD_LIST = "EXTRA_CARD_LIST"
        const val EXTRA_DELAY_MS = "EXTRA_DELAY_MS"

        private val _serviceState = MutableStateFlow(ServiceState())
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    private val routerRepository: IRouterRepository by inject()
    private val sessionRepository: ISessionRepository by inject()
    private val testResultRepository: ITestResultRepository by inject()
    private val appPreferences: AppPreferences by inject()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var webView: WebView? = null
    private var testJob: Job? = null
    private var screenshotJob: Job? = null
    private val binder = ServiceBinder(this)
    private lateinit var notificationHelper: NotificationHelper

    private var pageLoadedDeferred: CompletableDeferred<Unit>? = null
    private var retryDeferred: CompletableDeferred<Boolean>? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        notificationHelper = NotificationHelper(this)

        try {
            webView = WebView(applicationContext).apply {
                val dm: DisplayMetrics = resources.displayMetrics
                layout(0, 0, dm.widthPixels, dm.heightPixels)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Timber.d("WebView page loaded: $url")
                        pageLoadedDeferred?.complete(Unit)
                    }

                    override fun onReceivedSslError(
                        view: WebView?, handler: SslErrorHandler?, error: SslError?
                    ) {
                        Timber.d("Proceeding with self-signed SSL error: ${error?.primaryError}")
                        handler?.proceed()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        Timber.e("WebView error: ${error?.description} code: ${error?.errorCode}")
                        if (request?.isForMainFrame == true) {
                            if (error?.errorCode == WebViewClient.ERROR_UNKNOWN || error?.description?.contains("net::ERR_ABORTED", ignoreCase = true) == true) {
                                Timber.d("Ignoring ERR_ABORTED for main frame")
                                return
                            }
                            val errMsg = error?.description?.toString() ?: "فشل تحميل الصفحة"
                            pageLoadedDeferred?.completeExceptionally(Exception(errMsg))
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize WebView in TestService. Background loop might be unavailable.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }
        val action = intent.action
        Timber.d("onStartCommand action: $action")

        if (action == ACTION_START) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID,
                        notificationHelper.buildRunningNotification(_serviceState.value, forceStandard = false),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start foreground with custom view, falling back to standard view")
                    try {
                        startForeground(
                            NotificationHelper.NOTIFICATION_ID,
                            notificationHelper.buildRunningNotification(_serviceState.value, forceStandard = true),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } catch (firstLevelEx: Exception) {
                        Timber.e(firstLevelEx, "Critical: Start foreground failed even with standard layout")
                    }
                }
            } else {
                try {
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID,
                        notificationHelper.buildRunningNotification(_serviceState.value, forceStandard = false)
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start foreground with custom view, falling back to standard view")
                    try {
                        startForeground(
                            NotificationHelper.NOTIFICATION_ID,
                            notificationHelper.buildRunningNotification(_serviceState.value, forceStandard = true)
                        )
                    } catch (secondLevelEx: Exception) {
                        Timber.e(secondLevelEx, "Critical: Start foreground failed even with standard layout on older SDK")
                    }
                }
            }
        }

        when (action) {
            ACTION_START -> {
                val routerId = intent.getLongExtra(EXTRA_ROUTER_ID, -1L)
                val cardList = intent.getStringArrayListExtra(EXTRA_CARD_LIST) ?: emptyList()
                val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 500L)
                startTestLoop(routerId, cardList, delayMs)
            }
            ACTION_PAUSE -> {
                _serviceState.update { it.copy(isPaused = true, status = "PAUSED") }
                notificationHelper.updateNotification(_serviceState.value)
            }
            ACTION_RESUME -> {
                _serviceState.update { it.copy(isPaused = false, status = "RUNNING") }
                notificationHelper.updateNotification(_serviceState.value)
            }
            ACTION_CANCEL -> {
                if (retryDeferred != null) {
                    retryDeferred?.complete(false)
                } else {
                    cancelTest()
                }
            }
            ACTION_RETRY_LOAD -> {
                retryDeferred?.complete(true)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun ensureLoggedOut(router: RouterProfileEntity) {
        withContext(Dispatchers.Main) {
            try {
                val url = "${router.protocol}://${router.ip}${router.loginPath}"
                
                pageLoadedDeferred = null
                webView?.stopLoading()
                delay(500)
                
                pageLoadedDeferred = CompletableDeferred()
                webView?.loadUrl(url)
                try {
                    kotlinx.coroutines.withTimeout(15000) {
                        pageLoadedDeferred?.await()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error loading page to check logout state.")
                    return@withContext
                }

                // Wait for page scripts
                delay(1500)
                
                val checkJs = InjectionManager.buildCheckResultJs(router.successIndicator, router.failureIndicator, router.submitSelector, router.logoutSelector)
                val state = evaluateJsSafely(checkJs)
                
                if (state == "success") {
                    Timber.d("User is already logged in prior to test! Logging out...")
                    val lJs = InjectionManager.buildLogoutJs(router.logoutSelector)
                    evaluateJsSafely(lJs)
                    delay(3500) // wait for logout to process
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during ensureLoggedOut")
            }
        }
    }

    private suspend fun evaluateJsSafely(js: String): String {
        return kotlin.coroutines.suspendCoroutine { continuation ->
            webView?.evaluateJavascript(js) { res ->
                val cleanRes = res?.trim('"')?.trim() ?: "unknown"
                continuation.resume(cleanRes)
            }
        }
    }

    private fun startTestLoop(routerId: Long, cardList: List<String>, delayMs: Long) {
        testJob?.cancel()
        testJob = serviceScope.launch {
            try {
                val router = withContext(Dispatchers.IO) {
                    routerRepository.getById(routerId)
                } ?: run {
                    _serviceState.update { it.copy(status = "LOAD_ERROR", error = "Router profile not found") }
                    return@launch
                }

                val sessionId = withContext(Dispatchers.IO) {
                    sessionRepository.createSession(routerId, router.name)
                }

                _serviceState.update {
                    ServiceState(
                        total = cardList.size,
                        status = "RUNNING",
                        progress = 0,
                    )
                }

                startScreenshotLoop()
                
                // Clear state and perform logout if already logged in before we begin hitting cards
                ensureLoggedOut(router)

                cardList.forEachIndexed { index, card ->
                    while (_serviceState.value.isPaused) {
                        delay(200)
                    }

                    _serviceState.update {
                        it.copy(
                            currentCard = card,
                            progress = index + 1
                        )
                    }
                    notificationHelper.updateNotification(_serviceState.value)

                    val startTime = SystemClock.elapsedRealtime()
                    val result = try {
                        testCard(card, router)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Timber.d("Test loop cancelled due to user action in error state")
                        cancelTest()
                        return@launch
                    } catch (e: Exception) {
                        Timber.e(e, "Unexpected error testing card: $card")
                        false
                    }
                    val duration = SystemClock.elapsedRealtime() - startTime

                    withContext(Dispatchers.IO) {
                        testResultRepository.insertResult(
                            TestResultEntity(
                                sessionId = sessionId,
                                cardCode = card,
                                routerId = routerId,
                                routerName = router.name,
                                state = if (result) "Success" else "Failure",
                                message = if (result) "تم اختبار البطاقة بنجاح والاتصال موافق" else "فشلت عملية اختبار البطاقة المحددة",
                                durationMs = duration,
                                testedAt = System.currentTimeMillis()
                            )
                        )
                    }

                    if (result) {
                        _serviceState.update { it.copy(successCount = it.successCount + 1) }
                        notificationHelper.showResultNotification(card, true)
                        // Wait a bit to logout after success, so next card works
                        delay(1000)
                        withContext(Dispatchers.Main) {
                            val lJs = InjectionManager.buildLogoutJs(router.logoutSelector)
                            evaluateJsSafely(lJs)
                            delay(3500) // Extra time to process logout before next card
                        }
                    } else {
                        _serviceState.update { it.copy(failureCount = it.failureCount + 1) }
                    }

                    delay(delayMs)
                }

                withContext(Dispatchers.IO) {
                    sessionRepository.markFinished(
                        sessionId = sessionId,
                        successCount = _serviceState.value.successCount,
                        failureCount = _serviceState.value.failureCount
                    )
                }

                _serviceState.update { it.copy(status = "DONE") }
                notificationHelper.updateNotification(_serviceState.value)
                stopSelf()

            } catch (e: Exception) {
                Timber.e(e, "Error during test loop")
                _serviceState.update { it.copy(status = "LOAD_ERROR", error = e.localizedMessage) }
            }
        }
    }

    private suspend fun testCard(card: String, router: RouterProfileEntity): Boolean {
        if (router.name.contains("معتصم", ignoreCase = true) || router.name.contains("motasem", ignoreCase = true)) {
            return testMotasemCard(card, router)
        }
        
        return withContext(Dispatchers.Main) {
            try {
                // Load Login page
                val url = "${router.protocol}://${router.ip}${router.loginPath}"
                Timber.d("Loading URL: $url")
                
                // Keep cookies logic inside loop as backup, but ensureLoggedOut handled the state initially.
                try {
                    android.webkit.CookieManager.getInstance().apply {
                        removeAllCookies(null)
                        flush()
                    }
                    android.webkit.WebStorage.getInstance().deleteAllData()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to clear WebView data before testing card: $card")
                }

                // Clear any old deferred to avoid race conditions with previous page loads
                pageLoadedDeferred = null
                webView?.stopLoading()
                delay(500)

                pageLoadedDeferred = CompletableDeferred()
                webView?.loadUrl(url)
                
                // Wait for page finish
                try {
                    kotlinx.coroutines.withTimeout(15000) {
                        pageLoadedDeferred?.await()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val errMsg = e.localizedMessage ?: "فشل تحميل الصفحة"
                    _serviceState.update { it.copy(status = "LOAD_ERROR", error = errMsg) }
                    notificationHelper.updateNotification(_serviceState.value)
                    
                    val retry = CompletableDeferred<Boolean>()
                    retryDeferred = retry
                    val shouldRetry = retry.await()
                    retryDeferred = null
                    
                    if (shouldRetry) {
                        _serviceState.update { it.copy(status = "RUNNING", error = null) }
                        notificationHelper.updateNotification(_serviceState.value)
                        return@withContext testCard(card, router)
                    } else {
                        throw kotlinx.coroutines.CancellationException("User cancelled from retry error dialog")
                    }
                }
                
                delay(1000)

                // Ensure we are actually on a page with a login form.
                // If it's a redirect / logout conformation page with a "تسجيل الدخول" button but no form, click that button and wait.
                val ensureFormJs = """
                (function() {
                    var u1 = document.querySelector('input[name="username"]');
                    var u2 = document.querySelector('input[type="text"]:not([type="hidden"])');
                    if (u1 || u2) return 'form_ready';
                    
                    // First, check if we are on the status page (already logged in)
                    var links = document.querySelectorAll('a, button, input[type="submit"]');
                    for (var i = 0; i < links.length; i++) {
                        var text = links[i].textContent || '';
                        var val = links[i].value || '';
                        if (text.indexOf('تسجيل الخروج') !== -1 || val.indexOf('تسجيل الخروج') !== -1 || text.toLowerCase().indexOf('logout') !== -1) {
                            if (typeof openLogout === 'function') { openLogout(); return 'clicked_logout'; }
                            links[i].click();
                            return 'clicked_logout';
                        }
                    }
                    
                    // Look for orange login button (or any link/button that says تسجيل الدخول)
                    for (var i = 0; i < links.length; i++) {
                        var text = links[i].textContent || '';
                        var val = links[i].value || '';
                        if (text.indexOf('تسجيل الدخول') !== -1 || val.indexOf('تسجيل الدخول') !== -1) {
                            links[i].click();
                            return 'clicked_login_redirect';
                        }
                    }
                    
                    // Check if it has a generic form to submit? No, wait safely.
                    return 'no_form';
                })();
                """.trimIndent()
                
                var formReadyRetries = 0
                while (formReadyRetries < 8) {
                    val formState = evaluateJsSafely(ensureFormJs)
                    if (formState == "form_ready") break
                    delay(1500) // Wait for the redirect click to process or page to load
                    formReadyRetries++
                }

                // Inject JavaScript
                val js = InjectionManager.buildInjectionJs(
                    card = card,
                    usernameSel = router.usernameSelector,
                    passwordSel = router.passwordSelector,
                    submitSel = router.submitSelector
                )
                val injectResult = evaluateJsSafely(js)
                Timber.d("Injection returned: $injectResult")

                // Keep checking the DOM dynamically over ~12 seconds after clicking submit
                val checkJs = InjectionManager.buildCheckResultJs(router.successIndicator, router.failureIndicator, router.submitSelector, router.logoutSelector)
                var resolvedState = "unknown"
                var retries = 0
                val maxRetries = 15 // Up to 15 seconds wait
                
                while (retries < maxRetries) {
                    delay(1000)
                    val currentState = evaluateJsSafely(checkJs)
                    
                    if (currentState == "success") {
                        resolvedState = "success"
                        break
                    } else if (currentState == "failure") {
                        resolvedState = "failure"
                        break
                    } else if (currentState == "redirecting") {
                        Timber.d("Detected redirecting state... waiting longer")
                        // Wait a bit and continue polling, it should eventually land on 'success'
                    }
                    retries++
                }

                resolvedState == "success"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "testCard failed for card: $card")
                false
            }
        }
    }

    private fun startScreenshotLoop() {
        screenshotJob?.cancel()
        screenshotJob = serviceScope.launch {
            while (true) {
                delay(2000)
                val bitmap = captureScreenshot()
                if (bitmap != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                            bitmap.recycle()
                            val bytes = stream.toByteArray()
                            _serviceState.update { it.copy(screenshotBytes = bytes) }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to compress/recycle screenshot bitmap")
                        }
                    }
                }
            }
        }
    }

    private fun captureScreenshot(): Bitmap? {
        val view = webView ?: return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return try {
            val scale = 360f / w.coerceAtLeast(1)
            val targetWidth = 360
            val targetHeight = (h * scale).toInt().coerceIn(1, 800)
            
            val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            view.draw(canvas)
            bmp
        } catch (e: Throwable) {
            Timber.e(e, "Screenshot capture failed safely")
            null
        }
    }

    private suspend fun testMotasemCard(card: String, router: RouterProfileEntity): Boolean {
        return MotasemTestStrategy.testMotasemCard(
            card = card,
            router = router,
            webView = webView,
            evaluateJsSafely = ::evaluateJsSafely
        ) {
            while (_serviceState.value.isPaused) { kotlinx.coroutines.delay(500) }
        }
    }

    private fun cancelTest() {
        testJob?.cancel()
        screenshotJob?.cancel()
        _serviceState.update { it.copy(status = "CANCELLED") }
        notificationHelper.updateNotification(_serviceState.value)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        testJob?.cancel()
        screenshotJob?.cancel()
        serviceScope.cancel()
        try {
            webView?.destroy()
        } catch (e: Throwable) {
            Timber.e(e, "Error destroying webview in onDestroy")
        }
        webView = null
        _serviceState.value = ServiceState()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

