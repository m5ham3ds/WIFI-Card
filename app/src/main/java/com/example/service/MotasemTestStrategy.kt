package com.example.service

import android.webkit.WebView
import com.example.data.local.entity.RouterProfileEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

object MotasemTestStrategy {

    suspend fun testMotasemCard(
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
                    // Clear session
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

                    Timber.d("[Motasem] Loading url: $url")
                    
                    var pageLoadedDeferred: CompletableDeferred<Unit>? = null
                    
                    webView?.stopLoading()
                    delay(300)

                    pageLoadedDeferred = CompletableDeferred()
                    
                    webView?.loadUrl(url)
                    
                    // Wait firmly for 2.5 seconds to ensure page loaded (Motasem is local router)
                    delay(2500)
                } else {
                    Timber.d("[Motasem] Using preloaded page, skipping loadUrl")
                }

                // --- PRE-FLIGHT CHECK ---
                // If the user's phone was ALREADY logged into the router from outside the app,
                // the router will redirect the login URL to the status/logout page automatically!
                // We must detect this, force a logout, and wait for the login page before injecting.
                val preFlightJs = """
                (function() {
                    var html = document.documentElement.innerHTML;
                    var bodyText = document.body.innerText || '';
                    if (bodyText.indexOf('تفاصيل الأستخدام') !== -1 || bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1) return 'logged_in';
                    var logoutBtn = document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="تسجيل الخروج"], button[value*="تسجيل الخروج"]');
                    if (logoutBtn) return 'logged_in';
                    if (html.indexOf('تسجيل الخروج') !== -1) return 'logged_in';
                    return 'login_page';
                })();
                """.trimIndent()
                
                val preFlightResult = evaluateJsSafely(preFlightJs)
                if (preFlightResult == "logged_in") {
                    Timber.d("[Motasem] Pre-flight showed ALREADY LOGGED IN. Forcing logout...")
                    val forceLogoutJs = """
                    (function() {
                        if (typeof openLogout === 'function') { openLogout(); return; }
                        var logoutBtn = document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="تسجيل الخروج"], button[value*="تسجيل الخروج"]');
                        if (logoutBtn) { logoutBtn.click(); }
                    })();
                    """.trimIndent()
                    evaluateJsSafely(forceLogoutJs)
                    
                    delay(3000) // Wait for logout redirect
                    // Optional: hit the login URL again just to be sure we are back
                    webView?.loadUrl(url)
                    delay(2500)
                }

                // Wait for form to be ready
                var formRetries = 0
                val checkReadyJs = "(function() { return (document.readyState === 'complete' && (document.querySelector('input[name=\"username\"]') || document.querySelector('#username') || (document.login && document.login.username))) ? 'ready' : 'not_ready'; })();"
                while (formRetries < 20) {
                    val readyState = evaluateJsSafely(checkReadyJs)
                    if (readyState == "ready") break
                    delay(1000)
                    formRetries++
                }
                
                // Inject specific Motasem script snippet from his old project
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
                        
                        // 1. Fill the visible form inputs if they exist
                        if (document.login && document.login.username) {
                            document.login.username.value = cardValue;
                        } else {
                            var u = document.querySelector('input[name="username"], #username');
                            if (u) {
                                u.value = cardValue;
                                triggerEvents(u);
                            }
                        }
                        
                        if (document.login && document.login.password) {
                            document.login.password.value = '';
                        }
                        
                        var pass = document.querySelector('input[name="password"]');
                        if (pass) {
                            pass.value = '';
                            triggerEvents(pass);
                        }
                        
                        // 3. Attempt first standard Mikrotik logic (doLogin)
                        if (typeof doLogin === 'function') {
                            try {
                                doLogin();
                                return 'injected_dologin';
                            } catch (err) {}
                        }
                        
                        // 4. Fallback 1: submit the hidden form (sendin) directly
                        if (document.sendin) {
                            document.sendin.username.value = cardValue;
                            if (document.sendin.password) document.sendin.password.value = '';
                            document.sendin.submit();
                            return 'injected_sendin_fallback';
                        }
                        
                        // 5. Fallback 2: click the submit button
                        var submitBtn = document.querySelector('button[type=submit], input[type=submit], .submit button, .btn-main, .button-submit');
                        if (submitBtn) {
                            submitBtn.click();
                            return 'injected_click_processed';
                        }
                        
                        // 6. Fallback 3: submit the first form
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
                Timber.d("[Motasem] Injection result: $injectResult")

                // Wait firmly for 3.5 seconds to let the router respond and redirect
                delay(3500)

                // Motasem specific check logic using old app pattern
                val safeSuccess = JSONObject.quote(router.successIndicator).removeSurrounding("\"").replace("'", "\\'")
                val safeFailure = JSONObject.quote(router.failureIndicator).removeSurrounding("\"").replace("'", "\\'")
                
                val checkJs = """
                (function() {
                    var html = document.documentElement.innerHTML;
                    if ('$safeSuccess' !== '' && '$safeSuccess' !== 'null' && html.indexOf('$safeSuccess') !== -1) return 'success';
                    
                    var bodyText = document.body.innerText || '';
                    if (bodyText.indexOf('تفاصيل الأستخدام') !== -1 || bodyText.indexOf('الوقت المتبقي') !== -1 || bodyText.indexOf('الرصيد المتبقي') !== -1) return 'success';
                    
                    if (document.querySelector('form[action*="logout"]') || document.querySelector('a[href*="logout"]')) return 'success';
                    
                    var logoutBtn = document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="تسجيل الخروج"], .info.blue');
                    if (logoutBtn) return 'success';
                    if (html.indexOf('تسجيل الخروج') !== -1 || html.indexOf('MikroTicket Status') !== -1) return 'success';
                    
                    if ('$safeFailure' !== '' && '$safeFailure' !== 'null' && html.indexOf('$safeFailure') !== -1) return 'failure';
                    if (bodyText.indexOf('خطأ') !== -1 || bodyText.indexOf('فشل') !== -1 || bodyText.indexOf('غير صحيح') !== -1 || bodyText.indexOf('invalid') !== -1 || bodyText.indexOf('Incomplete') !== -1 || bodyText.indexOf('not found') !== -1) return 'failure';
                    
                    return 'unknown';
                })();
                """.trimIndent()

                var resultStr = evaluateJsSafely(checkJs)
                if (resultStr == "unknown") {
                    delay(2500)
                    resultStr = evaluateJsSafely(checkJs)
                }
                
                Timber.d("[Motasem] Check result: $resultStr")

                if (resultStr == "success") {
                    val logoutJs = """
                    (function() {
                        if (typeof openLogout === 'function') { openLogout(); return 'logout_called'; }
                        var logoutBtn = document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="تسجيل الخروج"], button[value*="تسجيل الخروج"]');
                        if (logoutBtn) { 
                            logoutBtn.click();
                            return 'logout_clicked';
                        }
                        return 'no_logout';
                    })();
                    """.trimIndent()

                    evaluateJsSafely(logoutJs)
                    delay(2500) // Give it time to logout from the router side before next card
                    return@withContext true
                }

                return@withContext false
            } catch(e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Error in Motasem specific test")
                return@withContext false
            }
        }
    }
}
