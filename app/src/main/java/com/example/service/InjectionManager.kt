package com.example.service

import org.json.JSONObject
import timber.log.Timber

object InjectionManager {

    fun buildInjectionJs(
        card: String,
        usernameSel: String,
        passwordSel: String,
        submitSel: String
    ): String {
        val safeCard = JSONObject.quote(card).removeSurrounding("\"").replace("'", "\\'")
        
        val js = """
        (function() {
            try {
                var uSel = '${usernameSel.replace("'", "\\'")}';
                var pSel = '${passwordSel.replace("'", "\\'")}';
                var sSel = '${submitSel.replace("'", "\\'")}';
                
                function triggerEvents(el) {
                    if(!el) return;
                    try {
                        var ev1 = document.createEvent('Event'); ev1.initEvent('input', true, true); el.dispatchEvent(ev1);
                        var ev2 = document.createEvent('Event'); ev2.initEvent('change', true, true); el.dispatchEvent(ev2);
                    } catch(e) {}
                }
                
                var uFound = false, pFound = false, sFound = null;

                // Try to fill all inputs that match username
                var uNodes = uSel ? document.querySelectorAll(uSel) : document.querySelectorAll('#username, input[type="text"]:not([type="hidden"]), input[name="username"]');
                for(var i=0; i<uNodes.length; i++) {
                    uNodes[i].value = '$safeCard';
                    triggerEvents(uNodes[i]);
                    uFound = true;
                }
                
                // Try to fill all inputs that match password
                var pNodes = pSel ? document.querySelectorAll(pSel) : document.querySelectorAll('#password, input[type="password"]:not([type="hidden"]), input[name="password"]');
                for(var i=0; i<pNodes.length; i++) {
                    pNodes[i].value = '';
                    triggerEvents(pNodes[i]);
                    pFound = true;
                }
                
                var s = sSel ? document.querySelector(sSel) : null;
                if (!s) s = document.querySelector('button[type="submit"], input[type="submit"], form input[type="submit"], .submit button');
                
                if (uFound) {
                    if (typeof doLogin === 'function') {
                        doLogin();
                    } else if (s) {
                        s.click();
                    } else if (uNodes.length > 0 && uNodes[0].form) {
                        // dispatch submit event to trigger onsubmit handlers before form.submit()
                        try {
                            var evt = document.createEvent('Event');
                            evt.initEvent('submit', true, true);
                            if (uNodes[0].form.dispatchEvent(evt)) {
                                uNodes[0].form.submit();
                            }
                        } catch(e) { uNodes[0].form.submit(); }
                    } else {
                        var anyBtn = document.querySelector('form button, .submit button');
                        if (anyBtn) anyBtn.click();
                    }
                    return 'injected';
                }
                
                // Fallback to calling doLogin directly if somehow fields weren't found by explicit selectors
                if (typeof doLogin === 'function') {
                   var u = document.querySelector('input[name="username"]');
                   var p = document.querySelector('input[name="password"]');
                   if (u) u.value = '$safeCard';
                   if (p) p.value = '';
                   doLogin();
                   return 'injected_using_dologin';
                }
                return 'selectors_not_found';
            } catch(e) { return 'error:' + e.message; }
        })();
        """.trimIndent()
        Timber.v("Constructed injection JS: $js")
        return js
    }

    fun buildLogoutJs(logoutSel: String): String {
        return """
        (function() {
            try {
                var lSel = '${logoutSel.replace("'", "\\'")}';
                var btn = lSel ? document.querySelector(lSel) : null;
                if (btn) {
                    btn.click();
                    return 'clicked';
                }
                
                var f = document.getElementById('mForm');
                if (f) { f.submit(); return 'mForm'; }
                var f2 = document.querySelector('form[name="logout"]');
                if (f2) { f2.submit(); return 'logout_form'; }
                
                // Fallback: search for logout link if selector fails or is empty
                var links = document.querySelectorAll('a, button, input[type="button"], input[type="submit"]');
                for (var i = 0; i < links.length; i++) {
                    var text = (links[i].textContent || '').toLowerCase();
                    var value = (links[i].value || '').toLowerCase();
                    if (text.indexOf('logout') !== -1 || text.indexOf('تسجيل الخروج') !== -1 || text.indexOf('خروج') !== -1 || value.indexOf('logout') !== -1 || value.indexOf('تسجيل الخروج') !== -1) {
                        links[i].click();
                        return 'clicked_fallback';
                    }
                }
                if (typeof openLogout === 'function') { openLogout(); return 'openLogout'; }
                return 'not_found';
            } catch(e) { return 'error:' + e.message; }
        })();
        """.trimIndent()
    }
    
    fun buildCheckResultJs(successInd: String, failureInd: String, loginSel: String, logoutSel: String): String {
        val safeSuccess = JSONObject.quote(successInd).removeSurrounding("\"").replace("'", "\\'")
        val safeFailure = JSONObject.quote(failureInd).removeSurrounding("\"").replace("'", "\\'")
        
        return """
        (function() {
            try {
                var html = document.documentElement.innerHTML;
                var bodyText = document.body.innerText || '';
                
                // 1. Explicit Failure Indicators
                if ('$safeFailure' !== '' && html.indexOf('$safeFailure') !== -1) return 'failure';
                if (bodyText.indexOf('already authorizing') !== -1 || html.indexOf('already authorizing') !== -1) {
                    return 'authorizing';
                }
                if (bodyText.indexOf('خطأ') !== -1 || bodyText.indexOf('فشل') !== -1 || bodyText.indexOf('لا يمكن') !== -1 || bodyText.indexOf('غير صحيح') !== -1 || bodyText.indexOf('invalid') !== -1 || bodyText.indexOf('incorrect') !== -1) {
                    // Make sure it's not a generic word in the page footer, but a common Mikrotik error is enough.
                    return 'failure';
                }
                
                // 2. Explicit Success Indicators
                if ('$safeSuccess' !== '' && '$safeSuccess' !== 'null' && html.indexOf('$safeSuccess') !== -1) {
                   return 'success';
                }
                
                // Fallback strict success checks
                var htmlLower = html.toLowerCase();
                var textLower = bodyText.toLowerCase();
                var hasLogout = (htmlLower.indexOf('logout') !== -1 || htmlLower.indexOf('تسجيل الخروج') !== -1 || htmlLower.indexOf('خروج') !== -1);
                var hasSuccessText = (textLower.indexOf('الوقت المتبقي') !== -1 || textLower.indexOf('الميغبايت') !== -1 || textLower.indexOf('الرصيد المتبقي') !== -1 || textLower.indexOf('المتبقي') !== -1);
                
                if (hasLogout && hasSuccessText) {
                    return 'success';
                }
                
                // 3. Intermediate logic check (Redirect page)
                if (html.indexOf('سيتم الآن تحويلك') !== -1 || html.indexOf('redirect') !== -1 || html.indexOf('Please wait') !== -1) {
                    return 'redirecting';
                }
                
                return 'unknown';
            } catch(e) { return 'error:' + e.message; }
        })();
        """.trimIndent()
    }
}
