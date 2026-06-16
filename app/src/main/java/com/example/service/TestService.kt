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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.example.service.BelloTestStrategy
import com.example.service.AbashaTestStrategy
import com.example.service.MotasemTestStrategy
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
    private val webViewPool = mutableListOf<WebView>()
    private val pageLoadedDeferredMap = mutableMapOf<WebView, CompletableDeferred<Unit>?>()
    private var currentWebViewIndex = 0

    private val activeWebView: WebView?
        get() = webViewPool.getOrNull(currentWebViewIndex)

    private var testJob: Job? = null
    private var screenshotJob: Job? = null
    private val binder = ServiceBinder(this)
    private lateinit var notificationHelper: NotificationHelper

    private var retryDeferred: CompletableDeferred<Boolean>? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        notificationHelper = NotificationHelper(this)
    }

    private fun createWebViewInstance(): WebView {
        return WebView(applicationContext).apply {
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
                    
                    // Auto-click reload button if present on page load
                    val autoReloadJs = """
                        (function() {
                            var buttons = document.querySelectorAll('button, a, input[type="button"]');
                            for (var i = 0; i < buttons.length; i++) {
                                var text = (buttons[i].innerText || buttons[i].value || '').toLowerCase();
                                if (text.indexOf('إعادة تحميل') !== -1 || text.indexOf('retry') !== -1 || text.indexOf('reload') !== -1 || text.indexOf('تحديث') !== -1) {
                                    buttons[i].click();
                                    return true;
                                }
                            }
                            return false;
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(autoReloadJs) { result ->
                        if (result == "true") {
                            Timber.d("Auto-clicked reload button")
                        }
                    }

                    view?.let { pageLoadedDeferredMap[it]?.complete(Unit) }
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
                        view?.let { pageLoadedDeferredMap[it]?.completeExceptionally(Exception(errMsg)) }
                    }
                }
            }
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

                // Check and logout for each webview in the pool
                webViewPool.forEach { wv ->
                    // Stop any ongoing load and reset deferred
                    pageLoadedDeferredMap[wv] = null
                    wv.stopLoading()
                    delay(200)

                    val def = CompletableDeferred<Unit>()
                    pageLoadedDeferredMap[wv] = def
                    wv.loadUrl(url)
                    try {
                        kotlinx.coroutines.withTimeout(15000) {
                            def.await()
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error loading page to check logout state in webview.")
                        return@forEach
                    }

                    // Wait for page scripts
                    delay(1500)
                    
                    val checkJs = InjectionManager.buildCheckResultJs(router.successIndicator, router.failureIndicator, router.submitSelector, router.logoutSelector)
                    val state = evaluateJsSafely(wv, checkJs)
                    
                    if (state == "success") {
                        Timber.d("User is already logged in prior to test! Logging out...")
                        val lJs = InjectionManager.buildLogoutJs(router.logoutSelector)
                        evaluateJsSafely(wv, lJs)
                        delay(2500) // wait for logout to process
                        
                        // Load again just to be safe it's on the login page now
                        val relDef = CompletableDeferred<Unit>()
                        pageLoadedDeferredMap[wv] = relDef
                        wv.loadUrl(url)
                        try {
                            kotlinx.coroutines.withTimeout(15000) { relDef.await() }
                        } catch (e: Exception) {
                            Timber.e(e, "Error loading login page after forced logout.")
                        }
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during ensureLoggedOut")
            }
        }
    }

    private suspend fun evaluateJsSafely(webViewToUse: WebView?, js: String): String {
        return kotlin.coroutines.suspendCoroutine { continuation ->
            if (webViewToUse == null) {
                continuation.resume("unknown")
                return@suspendCoroutine
            }
            webViewToUse.evaluateJavascript(js) { res ->
                val cleanRes = res?.trim('"')?.trim() ?: "unknown"
                continuation.resume(cleanRes)
            }
        }
    }

    private fun startTestLoop(routerId: Long, cardList: List<String>, delayMs: Long) {
        testJob?.cancel()
        testJob = serviceScope.launch {
            try {
                val poolSize = appPreferences.threadCount.first()
                
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
                
                val url = "${router.protocol}://${router.ip}${router.loginPath}"

                // Initialize WebView Pool
                withContext(Dispatchers.Main) {
                    webViewPool.forEach { it.destroy() }
                    webViewPool.clear()
                    pageLoadedDeferredMap.clear()
                    
                    for (i in 0 until poolSize) {
                        val wv = createWebViewInstance()
                        webViewPool.add(wv)
                    }
                }

                // Preload and ensure logged out for all WebViews
                withContext(Dispatchers.Main) {
                    webViewPool.forEach { 
                        val def = CompletableDeferred<Unit>()
                        pageLoadedDeferredMap[it] = def
                        it.loadUrl(url) 
                    }
                }
                
                // Perform initial logout check after pool is initialized and let them load
                ensureLoggedOut(router)
                
                // We don't need to re-preload unless ensureLoggedOut re-loaded them, 
                // but ensureLoggedOut now operates on all web views so we are good.
                
                delay(2000)

                val cardQueue = Channel<String>(Channel.UNLIMITED)
                cardList.forEach { cardQueue.send(it) }
                cardQueue.close()

                val progressCounter = AtomicInteger(0)
                val isBlockedBySuccess = AtomicBoolean(false)
                val stateMutex = Mutex()
                val injectionMutex = Mutex()

                val workers = webViewPool.mapIndexed { wvIndex, webView ->
                    launch {
                        for (card in cardQueue) {
                            // Check pause states
                            while (_serviceState.value.isPaused || isBlockedBySuccess.get()) {
                                delay(500)
                            }

                            val currentProgress = progressCounter.incrementAndGet()
                            stateMutex.withLock {
                                _serviceState.update {
                                    it.copy(
                                        currentCard = card,
                                        progress = currentProgress
                                    )
                                }
                                notificationHelper.updateNotification(_serviceState.value)
                            }

                            currentWebViewIndex = wvIndex // For screenshots
                            
                            val startTime = SystemClock.elapsedRealtime()
                            val result = try {
                                // Double check page is loaded (wait if needed)
                                var def = pageLoadedDeferredMap[webView]
                                if (def != null) {
                                    try {
                                        kotlinx.coroutines.withTimeout(20000) { def.await() }
                                    } catch (_: Exception) {
                                        Timber.w("Timeout waiting for initial page load, trying to proceed anyway")
                                    }
                                }
                                
                                // Extra safety: wait for DOM to be stable and check for reload buttons again
                                delay(1500)
                                val checkReloadJs = """
                                    (function() {
                                        if (document.readyState !== 'complete') return 'not_ready';
                                        var buttons = document.querySelectorAll('button, a, input[type="button"]');
                                        for (var i = 0; i < buttons.length; i++) {
                                            var text = (buttons[i].innerText || buttons[i].value || '').toLowerCase();
                                            if (text.indexOf('إعادة تحميل') !== -1 || text.indexOf('retry') !== -1 || text.indexOf('reload') !== -1) {
                                                buttons[i].click();
                                                return 'clicked_reload';
                                            }
                                        }
                                        return 'ok';
                                    })();
                                """.trimIndent()
                                val reloadStatus = evaluateJsSafely(webView, checkReloadJs)
                                if (reloadStatus == "clicked_reload") {
                                    Timber.d("Clicked reload button during test loop, waiting for reload...")
                                    val newDef = CompletableDeferred<Unit>()
                                    pageLoadedDeferredMap[webView] = newDef
                                    try {
                                        kotlinx.coroutines.withTimeout(15000) { newDef.await() }
                                    } catch (_: Exception) {}
                                    delay(1000)
                                }
                                
                                injectionMutex.withLock {
                                    testCard(card, router, true, webView) { isBlockedBySuccess.get() }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "Worker $wvIndex failed for card: $card")
                                false
                            }
                            val duration = SystemClock.elapsedRealtime() - startTime

                            var isTrueSuccess = false
                            if (result) {
                                isTrueSuccess = isBlockedBySuccess.compareAndSet(false, true)
                                if (!isTrueSuccess) {
                                    Timber.w("Card $card reported success, but discarded as false positive (another worker claimed success first)")
                                }
                            }

                            withContext(Dispatchers.IO) {
                                testResultRepository.insertResult(
                                    TestResultEntity(
                                        sessionId = sessionId,
                                        cardCode = card,
                                        routerId = routerId,
                                        routerName = router.name,
                                        state = if (isTrueSuccess) "Success" else "Failure",
                                        message = if (isTrueSuccess) "تم اختبار البطاقة بنجاح" else if (result) "تم إلغاء النتيجة لاكتشاف نجاح بطاقة أخرى أولاً" else "فشلت عملية الاختبار",
                                        durationMs = duration,
                                        testedAt = System.currentTimeMillis()
                                    )
                                )
                            }

                            if (isTrueSuccess) {
                                stateMutex.withLock {
                                    _serviceState.update { it.copy(successCount = it.successCount + 1) }
                                }
                                notificationHelper.showResultNotification(card, true)
                                
                                delay(1000)
                                withContext(Dispatchers.Main) {
                                    val lJs = InjectionManager.buildLogoutJs(router.logoutSelector)
                                    evaluateJsSafely(webView, lJs)
                                    delay(4000) // Time to process logout
                                }
                                isBlockedBySuccess.set(false)
                            } else {
                                stateMutex.withLock {
                                    _serviceState.update { it.copy(failureCount = it.failureCount + 1) }
                                }
                            }

                            // Reload WebView for next card
                            withContext(Dispatchers.Main) {
                                webView.evaluateJavascript("document.body.innerHTML = '';", null)
                                webView.clearHistory()
                                webView.clearFormData()
                                val reloadDef = CompletableDeferred<Unit>()
                                pageLoadedDeferredMap[webView] = reloadDef
                                webView.loadUrl(url)
                            }
                            
                            delay(delayMs)
                        }
                    }
                }

                workers.forEach { it.join() }

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
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Error during test loop")
                _serviceState.update { it.copy(status = "LOAD_ERROR", error = e.localizedMessage) }
            }
        }
    }

    private suspend fun testCard(card: String, router: RouterProfileEntity, isPreloaded: Boolean, webViewToUse: WebView? = activeWebView, isBlockedBySuccess: () -> Boolean = { false }): Boolean {
        val wv = webViewToUse ?: activeWebView
        if (router.name.contains("معتصم", ignoreCase = true) || router.name.contains("motasem", ignoreCase = true)) {
            return testMotasemCard(card, router, isPreloaded, wv, isBlockedBySuccess)
        }
        if (router.name.contains("بيلو", ignoreCase = true) || router.name.contains("bello", ignoreCase = true)) {
            return testBelloCard(card, router, isPreloaded, wv, isBlockedBySuccess)
        }
        if (router.name.contains("اباشا", ignoreCase = true) || router.name.contains("abasha", ignoreCase = true) || router.name.contains("الباشا", ignoreCase = true)) {
            return testAbashaCard(card, router, isPreloaded, wv, isBlockedBySuccess)
        }
        
        return withContext(Dispatchers.Main) {
            try {
                // Load Login page
                val url = "${router.protocol}://${router.ip}${router.loginPath}"
                Timber.d("Testing URL: $url preloaded: $isPreloaded")
                
                if (isBlockedBySuccess()) return@withContext false

                if (!isPreloaded) {
                    if (wv == null) return@withContext false
                    try {
                        android.webkit.CookieManager.getInstance().apply {
                            removeAllCookies(null)
                            flush()
                        }
                        android.webkit.WebStorage.getInstance().deleteAllData()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to clear WebView data")
                    }

                    pageLoadedDeferredMap[wv] = null
                    wv.stopLoading()
                    delay(500)

                    val def = CompletableDeferred<Unit>()
                    pageLoadedDeferredMap[wv] = def
                    wv.loadUrl(url)
                    
                    try {
                        kotlinx.coroutines.withTimeout(15000) {
                            def.await()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        return@withContext false
                    }
                    delay(1000)
                }

                if (isBlockedBySuccess()) return@withContext false

                val ensureFormJs = """
                (function() {
                    if (document.readyState !== 'complete') return 'not_ready';
                    
                    var html = document.documentElement.innerHTML || '';
                    if (html.toLowerCase().indexOf('logout') !== -1 || html.indexOf('تسجيل الخروج') !== -1) {
                         var links = document.querySelectorAll('a, button, input[type="submit"], input[type="button"]');
                         for (var i = 0; i < links.length; i++) {
                             var text = (links[i].textContent || '').toLowerCase();
                             var val = (links[i].value || '').toLowerCase();
                             if (text.indexOf('تسجيل الخروج') !== -1 || val.indexOf('تسجيل الخروج') !== -1 || text.indexOf('logout') !== -1 || val.indexOf('logout') !== -1) {
                                 links[i].click();
                                 return 'clicked_logout';
                             }
                         }
                    }
                    
                    var u1 = document.querySelector('input[name="username"]');
                    var u2 = document.querySelector('input[type="text"]:not([type="hidden"])');
                    if (u1 || u2) return 'form_ready';
                    
                    var links2 = document.querySelectorAll('a, button, input[type="submit"]');
                    for (var i = 0; i < links2.length; i++) {
                        var text = links2[i].textContent || '';
                        var val = links2[i].value || '';
                        if (text.indexOf('تسجيل الدخول') !== -1 || val.indexOf('تسجيل الدخول') !== -1 || text.toLowerCase().indexOf('login') !== -1) {
                            links2[i].click();
                            return 'clicked_login_redirect';
                        }
                    }
                    return 'no_form';
                })();
                """.trimIndent()
                
                var formReadyRetries = 0
                while (formReadyRetries < 20) {
                    if (isBlockedBySuccess()) return@withContext false
                    val formState = evaluateJsSafely(wv, ensureFormJs)
                    if (formState == "form_ready") break
                    if (formState == "clicked_logout") {
                        Timber.d("Found logout button before injecting. Clicked it. Waiting for reload...")
                        delay(2500)
                    } else if (formState == "clicked_login_redirect") {
                        Timber.d("Found login redirect. Clicked it.")
                        delay(2000)
                    }
                    delay(1500)
                    formReadyRetries++
                }

                if (isBlockedBySuccess()) return@withContext false

                val js = InjectionManager.buildInjectionJs(
                    card = card,
                    usernameSel = router.usernameSelector,
                    passwordSel = router.passwordSelector,
                    submitSel = router.submitSelector
                )
                evaluateJsSafely(wv, js)

                val checkJs = InjectionManager.buildCheckResultJs(router.successIndicator, router.failureIndicator, router.submitSelector, router.logoutSelector)
                var resolvedState = "unknown"
                var retries = 0
                val maxRetries = 15
                
                while (retries < maxRetries) {
                    if (isBlockedBySuccess()) return@withContext false
                    delay(1000)
                    val currentState = evaluateJsSafely(wv, checkJs)
                    
                    if (currentState == "success") {
                        resolvedState = "success"
                        break
                    } else if (currentState == "failure") {
                        resolvedState = "failure"
                        break
                    }
                    retries++
                }

                resolvedState == "success"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "testCard failed")
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
                            Timber.e(e, "Failed to compress screenshot")
                        }
                    }
                }
            }
        }
    }

    private fun captureScreenshot(): Bitmap? {
        val view = activeWebView ?: return null
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
            null
        }
    }

    private suspend fun testMotasemCard(card: String, router: RouterProfileEntity, isPreloaded: Boolean, webViewToUse: WebView?, isBlockedBySuccess: () -> Boolean): Boolean {
        return MotasemTestStrategy.testMotasemCard(
            card = card,
            router = router,
            webView = webViewToUse,
            evaluateJsSafely = { js -> evaluateJsSafely(webViewToUse, js) },
            pauseCondition = { while (_serviceState.value.isPaused) { kotlinx.coroutines.delay(500) } },
            isPreloaded = isPreloaded,
            isBlockedBySuccess = isBlockedBySuccess
        )
    }

    private suspend fun testBelloCard(card: String, router: RouterProfileEntity, isPreloaded: Boolean, webViewToUse: WebView?, isBlockedBySuccess: () -> Boolean): Boolean {
        return BelloTestStrategy.testBelloCard(
            card = card,
            router = router,
            webView = webViewToUse,
            evaluateJsSafely = { js -> evaluateJsSafely(webViewToUse, js) },
            pauseCondition = { while (_serviceState.value.isPaused) { kotlinx.coroutines.delay(500) } },
            isPreloaded = isPreloaded,
            isBlockedBySuccess = isBlockedBySuccess
        )
    }

    private suspend fun testAbashaCard(card: String, router: RouterProfileEntity, isPreloaded: Boolean, webViewToUse: WebView?, isBlockedBySuccess: () -> Boolean): Boolean {
        return AbashaTestStrategy.testAbashaCard(
            card = card,
            router = router,
            webView = webViewToUse,
            evaluateJsSafely = { js -> evaluateJsSafely(webViewToUse, js) },
            pauseCondition = { while (_serviceState.value.isPaused) { kotlinx.coroutines.delay(500) } },
            isPreloaded = isPreloaded,
            isBlockedBySuccess = isBlockedBySuccess
        )
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
            webViewPool.forEach { it.destroy() }
            webViewPool.clear()
        } catch (e: Throwable) {
            Timber.e(e, "Error destroying webview pool in onDestroy")
        }
        _serviceState.value = ServiceState()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

