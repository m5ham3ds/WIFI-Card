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

                if (isBlockedBySuccess()) return@withContext false

                // --- PRE-FLIGHT CHECK ---
                // If the user's phone was ALREADY logged into the router from outside the app,
                // the router will redirect the login URL to the status/logout page automatically!
                // We must detect this, force a logout, and wait for the login page before injecting.
                val preFlightJs = """
                (function() {
                    var html = document.documentElement.innerHTML;
                    var bodyText = document.body.innerText || '';
                    if (bodyText.indexOf('\u062A\u0641\u0627\u0635\u064A\u0644 \u0627\u0644\u0623\u0633\u062A\u062E\u062F\u0627\u0645') !== -1 || bodyText.indexOf('\u0627\u0644\u0648\u0642\u062A \u0627\u0644\u0645\u062A\u0628\u0642\u064A') !== -1 || bodyText.indexOf('\u0627\u0644\u0631\u0635\u064A\u062F \u0627\u0644\u0645\u062A\u0628\u0642\u064A') !== -1) return 'logged_in';
                    var logoutBtn = document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C"], button[value*="\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C"]');
                    if (logoutBtn) return 'logged_in';
                    if (html.indexOf('\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C') !== -1) return 'logged_in';
                    return 'login_page';
                })();
                """.trimIndent()
                
                val preFlightResult = evaluateJsSafely(preFlightJs)
                if (preFlightResult == "logged_in") {
                    if (isBlockedBySuccess()) return@withContext false
                    Timber.d("[Motasem] Pre-flight showed ALREADY LOGGED IN. Forcing logout...")
                    if (onRequiresGlobalRelogin != null) {
                        onRequiresGlobalRelogin()
                    } else {
                        val forceLogoutJs = """
                        (function() {
                            var f = document.getElementById('mForm');
                            if (f) { f.submit(); return 'mForm'; }
                            var f2 = document.querySelector('form[name="logout"]');
                            if (f2) { f2.submit(); return 'logout_form'; }
                            var logoutBtn = document.querySelector('form[name="logout"] button') || document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="Logout"], input[value*="\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C"], button[value*="\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C"]');
                            if (logoutBtn) { logoutBtn.click(); return 'clicked'; }
                            if (typeof openLogout === 'function') { openLogout(); return 'openLogout'; }
                        })();
                        """.trimIndent()
                        evaluateJsSafely(forceLogoutJs)
                        
                        delay(3000) // Wait for logout redirect
                        if (isBlockedBySuccess()) return@withContext false
                        // Optional: hit the login URL again just to be sure we are back
                        webView?.loadUrl(url)
                        delay(2500)
                    }
                }

                // Wait for form to be ready
                var formRetries = 0
                val checkReadyJs = "(function() { return (document.readyState === 'complete' && (document.querySelector('input[name=\"username\"]') || document.querySelector('#username') || (document.login && document.login.username))) ? 'ready' : 'not_ready'; })();"
                while (formRetries < 20) {
                    if (isBlockedBySuccess()) return@withContext false
                    val readyState = evaluateJsSafely(checkReadyJs)
                    if (readyState == "ready") break
                    delay(1000)
                    formRetries++
                }
                
                if (isBlockedBySuccess()) return@withContext false
                delay(2000) // Extra wait for full load before injection
                
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
                            var u = document.querySelector('form[name="login"] input[name="username"]') || document.querySelector('#username') || document.querySelector('input[name="username"]:not([type="hidden"])');
                            if (u) {
                                u.value = cardValue;
                                triggerEvents(u);
                            }
                        }
                        
                        if (document.login && document.login.password) {
                            document.login.password.value = '';
                        }
                        
                        var pass = document.querySelector('form[name="login"] input[name="password"]') || document.querySelector('input[name="password"]:not([type="hidden"])');
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
                        var sendinForm = document.querySelector('form[name="sendin"]');
                        if (sendinForm) {
                            var su = sendinForm.querySelector('input[name="username"]');
                            if (su) su.value = cardValue;
                            var sp = sendinForm.querySelector('input[name="password"]');
                            if (sp) sp.value = '';
                            sendinForm.submit();
                            return 'injected_sendin_fallback';
                        }
                        
                        // 5. Fallback 2: click the submit button
                        var submitBtn = document.querySelector('form[name="login"] button[type=submit], form[name="login"] input[type=submit], .submit button, .btn-main, .button-submit');
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

                if (isBlockedBySuccess()) return@withContext false
                val injectResult = evaluateJsSafely(injectionJs)
                Timber.d("[Motasem] Injection result: $injectResult")

                // Wait firmly for 3.5 seconds to let the router respond and redirect
                delay(3500)

                if (isBlockedBySuccess()) return@withContext false

                // Motasem specific check logic using old app pattern
                val safeSuccess = JSONObject.quote(router.successIndicator).removeSurrounding("\"").replace("'", "\\'")
                val safeFailure = JSONObject.quote(router.failureIndicator).removeSurrounding("\"").replace("'", "\\'")
                
                val checkJs = """
                (function() {
                    var html = document.documentElement.innerHTML.toLowerCase();
                    if ('$safeSuccess' !== '' && '$safeSuccess' !== 'null' && html.indexOf('$safeSuccess'.toLowerCase()) !== -1) return 'success';
                    
                    var bodyText = (document.body.innerText || '').toLowerCase();
                    if (bodyText.indexOf('\u062A\u0641\u0627\u0635\u064A\u0644 \u0627\u0644\u0623\u0633\u062A\u062E\u062F\u0627\u0645') !== -1 || bodyText.indexOf('\u0627\u0644\u0648\u0642\u062A \u0627\u0644\u0645\u062A\u0628\u0642\u064A') !== -1 || bodyText.indexOf('\u0627\u0644\u0631\u0635\u064A\u062F \u0627\u0644\u0645\u062A\u0628\u0642\u064A') !== -1) return 'success';
                    
                    if (document.querySelector('form[action*="logout"]') || document.querySelector('a[href*="logout"]')) return 'success';
                    
                    var logoutBtn = document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C"], .info.blue');
                    if (logoutBtn) return 'success';
                    if (html.indexOf('\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C') !== -1 || html.indexOf('mikroticket status') !== -1) return 'success';
                    
                    if (bodyText.indexOf('already authorizing') !== -1 || html.indexOf('already authorizing') !== -1) return 'authorizing';
                    
                    if ('$safeFailure' !== '' && '$safeFailure' !== 'null' && html.indexOf('$safeFailure'.toLowerCase()) !== -1) return 'failure';
                    if (bodyText.indexOf('\u062E\u0637\u0623') !== -1 || bodyText.indexOf('\u0641\u0634\u0644') !== -1 || bodyText.indexOf('\u063A\u064A\u0631 \u0635\u062D\u064A\u062D') !== -1 || bodyText.indexOf('invalid') !== -1 || bodyText.indexOf('incomplete') !== -1 || bodyText.indexOf('not found') !== -1) return 'failure';
                    
                    return 'unknown';
                })();
                """.trimIndent()

                var resultStr = evaluateJsSafely(checkJs)
                
                if (resultStr == "authorizing") {
                    Timber.d("[Motasem] Router reports already authorizing. Waiting longer and retrying...")
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
                
                Timber.d("[Motasem] Check result: $resultStr")

                if (resultStr == "success") {
                    if (isBlockedBySuccess()) return@withContext false
                    val logoutJs = """
                    (function() {
                        if (typeof openLogout === 'function') { openLogout(); return 'logout_called'; }
                        var logoutBtn = document.querySelector('a[href*="logout"], button[onclick*="logout"], input[value*="\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C"], button[value*="\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C"]');
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
