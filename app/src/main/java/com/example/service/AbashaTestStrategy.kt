package com.example.service

import android.webkit.WebView
import com.example.data.local.entity.RouterProfileEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

object AbashaTestStrategy {

    suspend fun testAbashaCard(
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

                    Timber.d("[Abasha] Loading url: $url")
                    
                    webView?.stopLoading()
                    delay(300)

                    webView?.loadUrl(url)
                    
                    delay(2500)
                } else {
                    Timber.d("[Abasha] Using preloaded page")
                }

                if (isBlockedBySuccess()) return@withContext false

                // PRE-FLIGHT CHECK
                val preFlightJs = """
                (function() {
                    var html = document.documentElement.innerHTML;
                    var bodyText = document.body.innerText || '';
                    if (html.indexOf('MikroTicket Status') !== -1) return 'logged_in';
                    if (bodyText.indexOf('عنوان IP') !== -1 || bodyText.indexOf('تنزيل') !== -1 || bodyText.indexOf('وقت الاتصال') !== -1) return 'logged_in';
                    if (html.indexOf('openLogout()') !== -1 && html.indexOf('تسجيل الدخول') === -1) return 'logged_in';
                    return 'login_page';
                })();
                """.trimIndent()
                
                val preFlightResult = evaluateJsSafely(preFlightJs)
                if (preFlightResult == "logged_in") {
                    if (isBlockedBySuccess()) return@withContext false
                    Timber.d("[Abasha] Pre-flight showed ALREADY LOGGED IN. Forcing logout...")
                    val forceLogoutJs = """
                    (function() {
                        if (typeof openLogout === 'function') { openLogout(); return; }
                        var logoutBtn = document.querySelector('form[name="logout"] button') || document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="Logout"]');
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
                val checkReadyJs = "(function() { return (document.readyState === 'complete' && document.querySelector('input[name=\"username\"]')) ? 'ready' : 'not_ready'; })();"
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
                        var u = document.querySelector('input[name="username"]');
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
                        
                        var submitBtn = document.querySelector('input[type="submit"], button[type="submit"], input[value="اتصال"], .button-submit, .btn-main');
                        if (submitBtn) {
                            if (submitBtn.tagName.toLowerCase() === 'button' || submitBtn.type === 'submit') {
                                submitBtn.click();
                            } else {
                                // Fallback for some strange implementations
                                var form = document.querySelector('form[name="login"]') || document.forms[0];
                                if (form) form.submit();
                            }
                            return 'injected_click_processed';
                        }
                        
                        var forms = document.getElementsByTagName('form');
                        for(var i=0; i<forms.length; i++) {
                            if(forms[i].name === 'login' || forms[i].action.indexOf('login') !== -1) {
                                forms[i].submit();
                                return 'injected_form_fallback';
                            }
                        }
                        
                        return 'injected_no_submit_found';
                    } catch(e) { return 'error: ' + e.message; }
                })();
                """.trimIndent()

                if (isBlockedBySuccess()) return@withContext false
                val injectResult = evaluateJsSafely(injectionJs)
                Timber.d("[Abasha] Injection result: $injectResult")

                delay(3000)

                if (isBlockedBySuccess()) return@withContext false
                val checkJs = """
                (function() {
                    var html = document.documentElement.innerHTML;
                    var bodyText = document.body.innerText || '';
                    if (html.indexOf('MikroTicket Status') !== -1) return 'success';
                    if (bodyText.indexOf('عنوان IP') !== -1 || bodyText.indexOf('تنزيل') !== -1 || bodyText.indexOf('رفع') !== -1 || bodyText.indexOf('وقت الاتصال') !== -1) return 'success';
                    if (document.querySelector('form[id="mForm"]') || document.querySelector('form[action*="logout"]')) return 'success';
                    
                    if (bodyText.indexOf('خطأ') !== -1 || bodyText.indexOf('فشل') !== -1 || bodyText.indexOf('غير صحيح') !== -1 || bodyText.indexOf('invalid') !== -1 || bodyText.indexOf('not found') !== -1) return 'failure';
                    
                    return 'unknown';
                })();
                """.trimIndent()

                var resultStr = evaluateJsSafely(checkJs)
                if (resultStr == "unknown") {
                    if (isBlockedBySuccess()) return@withContext false
                    delay(2500)
                    resultStr = evaluateJsSafely(checkJs)
                }
                
                Timber.d("[Abasha] Check result: $resultStr")

                if (resultStr == "success") {
                    if (isBlockedBySuccess()) return@withContext false
                    val logoutJs = """
                    (function() {
                        if (typeof openLogout === 'function') { openLogout(); return 'logout_called'; }
                        var logoutBtn = document.querySelector('form[name="logout"] button') || document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="Logout"]');
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
                Timber.e(e, "Error in Abasha specific test")
                return@withContext false
            }
        }
    }
}
