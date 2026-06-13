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
                    val forceLogoutJs = """
                    (function() {
                        if (typeof openLogout === 'function') { openLogout(); return; }
                        var logoutBtn = document.querySelector('form[name="logout"] button[type="submit"]') || document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="تسجيل الخروج"], button[value*="تسجيل الخروج"]');
                        if (logoutBtn) { logoutBtn.click(); }
                    })();
                    """.trimIndent()
                    evaluateJsSafely(forceLogoutJs)
                    
                    delay(3000) 
                    if (isBlockedBySuccess()) return@withContext false
                    val loginUrl = "${router.protocol}://${router.ip}${router.loginPath}"
                    webView?.loadUrl(loginUrl)
                    delay(2500)
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
                        var u = document.querySelector('input[name="username"], #uname, #username');
                        if (u) {
                            u.value = cardValue;
                            triggerEvents(u);
                        }
                        
                        var p = document.querySelector('input[name="password"]');
                        if (p) { 
                            p.value = ''; 
                            triggerEvents(p);
                        }
                        
                        // Try doLogin() if exists
                        if (typeof doLogin === 'function') {
                            try { 
                                doLogin(); 
                                return 'injected_dologin'; 
                            } catch (err) {
                                console.error('doLogin failed', err);
                            }
                        }
                        
                        var submitBtn = document.querySelector('button[type="submit"], input[type="submit"], .submit button, .btn-main');
                        if (submitBtn) {
                            submitBtn.click();
                            return 'injected_click_processed';
                        }
                        
                        var forms = document.getElementsByTagName('form');
                        if (forms.length > 0) {
                            forms[0].submit();
                            return 'injected_form0_fallback';
                        }
                        
                        return 'injected_no_submit_found';
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
                    var html = document.documentElement.innerHTML;
                    var bodyText = document.body.innerText || '';
                    if (bodyText.indexOf('تفاصيل الأستخدام') !== -1 || bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1) return 'success';
                    if (html.indexOf('تسجيل الخروج') !== -1 || bodyText.indexOf('تسجيل الخروج') !== -1 || bodyText.indexOf('logout') !== -1) return 'success';
                    if (document.querySelector('form[action*="logout"]') || document.querySelector('a[href*="logout"]') || document.querySelector('.info.blue')) return 'success';
                    
                    if (bodyText.indexOf('خطأ') !== -1 || bodyText.indexOf('فشل') !== -1 || bodyText.indexOf('غير صحيح') !== -1 || bodyText.indexOf('invalid') !== -1 || bodyText.indexOf('Incomplete') !== -1 || bodyText.indexOf('not found') !== -1) return 'failure';
                    
                    return 'unknown';
                })();
                """.trimIndent()

                var resultStr = evaluateJsSafely(checkJs)
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
