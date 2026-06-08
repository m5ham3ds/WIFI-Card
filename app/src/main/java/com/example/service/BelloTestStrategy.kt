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
        isPreloaded: Boolean = false
    ): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                pauseCondition()

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
                    
                    // Wait firmly for 2.5 seconds to ensure page loaded
                    delay(2500)
                } else {
                    Timber.d("[Bello] Using preloaded page, skipping loadUrl")
                }
                
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
                    webView?.loadUrl(url)
                    delay(2500)
                }
                
                val safeCard = JSONObject.quote(card).removeSurrounding("\"").replace("'", "\\'")
                
                val injectionJs = """
                (function() {
                    try {
                        var cardValue = '$safeCard';
                        var u = document.querySelector('input[name="username"], #uname, #username');
                        if (u) {
                            u.value = cardValue;
                            u.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        
                        var p = document.querySelector('input[name="password"]');
                        if (p) { p.value = ''; }
                        
                        // Try doLogin() if exists
                        if (typeof doLogin === 'function') {
                            try { doLogin(); return 'injected_dologin'; } catch (err) {}
                        }
                        
                        var submitBtn = document.querySelector('button[type="submit"], input[type="submit"]');
                        if (submitBtn) {
                            submitBtn.click();
                            return 'injected_click_fallback';
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

                val injectResult = evaluateJsSafely(injectionJs)
                Timber.d("[Bello] Injection result: $injectResult")

                delay(3000)

                val checkJs = """
                (function() {
                    var html = document.documentElement.innerHTML;
                    var bodyText = document.body.innerText || '';
                    if (bodyText.indexOf('تفاصيل الأستخدام') !== -1 || bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1) return 'success';
                    if (html.indexOf('تسجيل الخروج') !== -1 || bodyText.indexOf('تسجيل الخروج') !== -1) return 'success';
                    
                    if (bodyText.indexOf('خطأ') !== -1 || bodyText.indexOf('فشل') !== -1 || bodyText.indexOf('غير صحيح') !== -1 || bodyText.indexOf('invalid') !== -1) return 'failure';
                    
                    return 'unknown';
                })();
                """.trimIndent()

                var resultStr = evaluateJsSafely(checkJs)
                if (resultStr == "unknown") {
                    delay(2500)
                    resultStr = evaluateJsSafely(checkJs)
                }
                
                Timber.d("[Bello] Check result: $resultStr")

                if (resultStr == "success") {
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
