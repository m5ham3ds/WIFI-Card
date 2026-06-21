package com.example.service

import android.webkit.WebView
import com.example.data.local.entity.RouterProfileEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

object BelloTestStrategy {

    suspend fun testBelloCard(
        card: String,
        router: RouterProfileEntity,
        webView: WebView?,
        evaluateJsSafely: suspend (String) -> String,
        pauseCondition: suspend () -> Unit,
        isPreloaded: Boolean = false,
        onRequiresGlobalRelogin: (suspend () -> Unit)? = null,
        isBlockedBySuccess: () -> Boolean = { false }
    ): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                pauseCondition()
                if (isBlockedBySuccess()) return@withContext false

                val url = "${router.protocol}://${router.ip}${router.loginPath}"

                if (!isPreloaded) {
                    try {
                        android.webkit.CookieManager.getInstance().apply {
                            removeAllCookies(null)
                            flush()
                        }
                        android.webkit.WebStorage.getInstance().deleteAllData()
                    } catch (_: Exception) {}

                    webView?.clearCache(true)
                    webView?.clearHistory()
                    webView?.clearFormData()

                    Timber.d("[Bello] Loading url: $url")
                    
                    webView?.stopLoading()
                    delay(300)

                    webView?.loadUrl(url)
                    
                    delay(2500)
                } else {
                    Timber.d("[Bello] Using preloaded page")
                }

                if (isBlockedBySuccess()) return@withContext false

                // PRE-FLIGHT CHECK
                val preFlightJs = """
                (function() {
                    var html = document.documentElement.innerHTML;
                    var bodyText = document.body.innerText || '';
                    if (bodyText.indexOf('تفاصيل الأستخدام') !== -1 || bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1) return 'logged_in';
                    if (html.indexOf('تسجيل الخروج') !== -1 || bodyText.indexOf('تسجيل الخروج') !== -1) return 'logged_in';
                    return 'login_page';
                })();
                """.trimIndent()
                
                val preFlightResult = evaluateJsSafely(preFlightJs)
                if (preFlightResult == "logged_in") {
                    if (isBlockedBySuccess()) return@withContext false
                    Timber.d("[Bello] Pre-flight showed ALREADY LOGGED IN. Forcing logout...")
                    if (onRequiresGlobalRelogin != null) {
                        onRequiresGlobalRelogin()
                    } else {
                        val forceLogoutJs = """
                        (function() {
                            var f = document.getElementById('mForm');
                            if (f) { f.submit(); return 'mForm'; }
                            var f2 = document.querySelector('form[name="logout"]');
                            if (f2) { f2.submit(); return 'logout_form'; }
                            var logoutBtn = document.querySelector('form[name="logout"] button[type="submit"]') || document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="تسجيل الخروج"], button[value*="تسجيل الخروج"]');
                            if (logoutBtn) { logoutBtn.click(); return 'clicked'; }
                            if (typeof openLogout === 'function') { openLogout(); return 'openLogout'; }
                        })();
                        """.trimIndent()
                        evaluateJsSafely(forceLogoutJs)
                        
                        delay(3000) 
                        if (isBlockedBySuccess()) return@withContext false
                        val loginUrl = "${router.protocol}://${router.ip}${router.loginPath}"
                        webView?.loadUrl(loginUrl)
                        delay(2500)
                    }
                }

                // Wait for form to be ready
                var formRetries = 0
                val checkReadyJs = "(function() { return (document.readyState === 'complete' && (document.querySelector('input[name=\"username\"]') || document.querySelector('#uname'))) ? 'ready' : 'not_ready'; })();"
                while (formRetries < 20) {
                    if (isBlockedBySuccess()) return@withContext false
                    val readyState = evaluateJsSafely(checkReadyJs)
                    if (readyState == "ready") break
                    delay(1000)
                    formRetries++
                }
                
                if (isBlockedBySuccess()) return@withContext false
                delay(2000) // Ensure fully loaded
                
                val safeCard = JSONObject.quote(card).removeSurrounding("\"").replace("'", "\\'")
                
                val injectionJs = """
                (function() {
                    try {
                        function triggerEvents(el) {
                            if(!el) return;
                            try {
                                var ev1 = document.createEvent('Event'); ev1.initEvent('input', true, true); el.dispatchEvent(ev1);
                                var ev2 = document.createEvent('Event'); ev2.initEvent('change', true, true); el.dispatchEvent(ev2);
                            } catch(e) {}
                        }
                        
                        var cardValue = '$safeCard';
                        var u = document.querySelector('form[name="login"] input[name="username"]') || document.querySelector('#uname') || document.querySelector('#username') || document.querySelector('input[name="username"]:not([type="hidden"])');
                        if (u) {
                            u.value = cardValue;
                            triggerEvents(u);
                        }
                        
                        var p = document.querySelector('form[name="login"] input[name="password"]') || document.querySelector('#password') || document.querySelector('input[name="password"]:not([type="hidden"])');
                        if (p) { 
                            p.value = ''; 
                            triggerEvents(p);
                        }
                        
                        if (typeof doLogin === 'function') {
                            try { 
                                doLogin(); 
                                return 'injected_dologin'; 
                            } catch (err) {}
                        }
                        
                        var submitBtn = document.querySelector('form[name="login"] button[type="submit"], form[name="login"] input[type="submit"], .submit button, .btn-main');
                        if (submitBtn) {
                            submitBtn.click();
                            return 'injected_click';
                        }
                        
                        var form = document.querySelector('form[name="login"]') || (document.forms.length > 1 ? document.forms[1] : document.forms[0]);
                        if (form) {
                            form.submit();
                        }
                        return 'injected_form_fallback';
                    } catch(e) { return 'error: ' + e.message; }
                })();
                """.trimIndent()

                if (isBlockedBySuccess()) return@withContext false
                val injectResult = evaluateJsSafely(injectionJs)
                Timber.d("[Bello] Injection result: $injectResult")

                delay(3000)

                if (isBlockedBySuccess()) return@withContext false
                val checkJs = """
                (function() {
                    var html = document.documentElement.innerHTML.toLowerCase();
                    var bodyText = (document.body.innerText || '').toLowerCase();
                    
                    var hasLogout = (html.indexOf('logout') !== -1 || html.indexOf('تسجيل الخروج') !== -1 || html.indexOf('خروج') !== -1);
                    var hasSuccessText = (bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الميغبايت') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1 || bodyText.indexOf('المتبقي') !== -1);
                    
                    if (hasLogout && hasSuccessText) return 'success';
                    
                    if (bodyText.indexOf('already authorizing') !== -1 || html.indexOf('already authorizing') !== -1) return 'authorizing';
                    if (bodyText.indexOf('خطأ') !== -1 || bodyText.indexOf('فشل') !== -1 || bodyText.indexOf('غير صحيح') !== -1 || bodyText.indexOf('invalid') !== -1 || bodyText.indexOf('incomplete') !== -1 || bodyText.indexOf('not found') !== -1) return 'failure';
                    
                    return 'unknown';
                })();
                """.trimIndent()

                var resultStr = evaluateJsSafely(checkJs)
                
                if (resultStr == "authorizing") {
                    Timber.d("[Bello] Router reports already authorizing. Waiting longer and retrying...")
                    delay(4000)
                    resultStr = evaluateJsSafely(checkJs)
                    if (resultStr == "authorizing") {
                        if (onRequiresGlobalRelogin != null) {
                            onRequiresGlobalRelogin()
                        } else {
                            val fixAuthJs = """
                            (function() {
                                var logoutBtn = document.querySelector('form[name="logout"] button') || document.querySelector('a[href*="logout"]');
                                if (logoutBtn) logoutBtn.click();
                            })();
                            """.trimIndent()
                            evaluateJsSafely(fixAuthJs)
                            delay(2000)
                        }
                        resultStr = evaluateJsSafely(checkJs)
                    }
                }
                
                if (resultStr == "unknown") {
                    if (isBlockedBySuccess()) return@withContext false
                    delay(2500)
                    resultStr = evaluateJsSafely(checkJs)
                }
                
                Timber.d("[Bello] Check result: $resultStr")

                if (resultStr == "success") {
                    if (isBlockedBySuccess()) return@withContext false
                    val logoutJs = """
                    (function() {
                        if (typeof openLogout === 'function') { openLogout(); return 'logout_called'; }
                        var logoutBtn = document.querySelector('form[name="logout"] button[type="submit"]') || document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="تسجيل الخروج"], button[value*="تسجيل الخروج"]');
                        if (logoutBtn) { 
                            logoutBtn.click();
                            return 'logout_clicked';
                        }
                        return 'no_logout';
                    })();
                    """.trimIndent()

                    evaluateJsSafely(logoutJs)
                    delay(2500)
                    return@withContext true
                }

                return@withContext false
            } catch(e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Error in Bello specific test")
                return@withContext false
            }
        }
    }
}
