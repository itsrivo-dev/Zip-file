package com.example.data

import android.webkit.ValueCallback
import android.webkit.WebView
import com.example.model.RealSuggestionInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

object FriendAssistantBridge {

    sealed interface DetectionResult {
        data class Success(val suggestion: RealSuggestionInfo) : DetectionResult
        data object NoSuggestions : DetectionResult
        data object NotLoggedIn : DetectionResult
        data object NotOnFacebook : DetectionResult
        data class SecurityWarning(val message: String) : DetectionResult
        data class Error(val message: String) : DetectionResult
    }

    sealed interface ActionResult {
        data object Completed : ActionResult
        data class Failed(val reason: String) : ActionResult
    }

    suspend fun scanCurrentSuggestion(webView: WebView): DetectionResult = withContext(Dispatchers.Main) {
        val script = """
            (function() {
                try {
                    var href = window.location.href;
                    if (!href.includes("facebook.com")) {
                        return JSON.stringify({ status: "NOT_ON_FACEBOOK" });
                    }
                    if (href.includes("/login") || href.includes("login.php") || document.querySelector('input[type="password"]')) {
                        return JSON.stringify({ status: "NOT_LOGGED_IN" });
                    }
                    if (href.includes("checkpoint") || document.querySelector('form[action*="checkpoint"]')) {
                        return JSON.stringify({ status: "SECURITY_WARNING", message: "Checkpoint or CAPTCHA detected." });
                    }

                    var allButtons = document.querySelectorAll('button, div[role="button"], a[role="button"]');
                    var targetBtn = null;
                    var targetCard = null;

                    for (var i = 0; i < allButtons.length; i++) {
                        var btn = allButtons[i];
                        if (btn.getAttribute('data-assistant-processed') || btn.getAttribute('data-assistant-status') === 'skipped') continue;

                        var text = (btn.innerText || btn.textContent || "").trim();
                        var aria = (btn.getAttribute('aria-label') || "").trim();
                        var combined = (text + " " + aria).toLowerCase();

                        if (/requested|cancel request|remove|delete|message|đã gửi|undo/i.test(combined)) continue;

                        var isAdd = /^add(\s+friend)?$/i.test(text) || 
                                    /add friend/i.test(combined) || 
                                    /thêm bạn/i.test(combined) || 
                                    /agregar a amigos/i.test(combined) || 
                                    /ajouter/i.test(combined) || 
                                    /añadir a amigos/i.test(combined) || 
                                    btn.getAttribute('data-sigil') === 'add-friend';

                        if (isAdd) {
                            var card = null;
                            var p = btn;
                            for (var d = 0; d < 6 && p && p !== document.body; d++) {
                                p = p.parentElement;
                                if (!p) break;
                                var tag = p.tagName.toLowerCase();
                                var role = p.getAttribute('role');
                                var sigil = p.getAttribute('data-sigil') || "";
                                if (tag === 'li' || role === 'listitem' || sigil.includes('friend') || p.getAttribute('data-mcomponent')) {
                                    card = p;
                                    break;
                                }
                            }
                            if (!card) {
                                card = btn.parentElement?.parentElement?.parentElement || btn.parentElement;
                            }

                            if (card && card.getAttribute('data-assistant-processed')) {
                                continue;
                            }

                            targetBtn = btn;
                            targetCard = card;
                            break;
                        }
                    }

                    if (!targetBtn) {
                        return JSON.stringify({ status: "NO_SUGGESTIONS" });
                    }

                    if (!targetBtn.id) {
                        targetBtn.id = "fb_ast_btn_" + Math.floor(Math.random() * 1000000);
                    }

                    if (targetCard) {
                        targetCard.setAttribute('data-assistant-active', 'true');
                        targetCard.style.outline = '3px solid #1877F2';
                        targetCard.style.outlineOffset = '2px';
                        targetCard.style.borderRadius = '8px';
                        targetCard.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    }

                    var detectedName = null;
                    var detectedInfo = null;

                    if (targetCard) {
                        var headingsAndLinks = targetCard.querySelectorAll('a, h2, h3, h4, strong, span[dir]');
                        for (var j = 0; j < headingsAndLinks.length; j++) {
                            var el = headingsAndLinks[j];
                            if (el === targetBtn || targetBtn.contains(el)) continue;
                            var t = (el.innerText || el.textContent || "").trim();
                            if (!t) continue;
                            if (/^(add|add friend|remove|delete|view profile)$/i.test(t)) continue;
                            if (/mutual friend/i.test(t) || /bạn chung/i.test(t)) continue;
                            if (t.length >= 2 && t.length <= 60 && !t.includes("\n")) {
                                detectedName = t;
                                break;
                            }
                        }

                        var allTextElements = targetCard.querySelectorAll('span, div');
                        for (var k = 0; k < allTextElements.length; k++) {
                            var t2 = (allTextElements[k].innerText || allTextElements[k].textContent || "").trim();
                            if (t2 && t2 !== detectedName && (/mutual friend/i.test(t2) || /bạn chung/i.test(t2))) {
                                detectedInfo = t2;
                                break;
                            }
                        }
                    }

                    return JSON.stringify({
                        status: "SUCCESS",
                        elementId: targetBtn.id,
                        name: detectedName,
                        info: detectedInfo
                    });
                } catch(e) {
                    return JSON.stringify({ status: "ERROR", message: e.message || "DOM evaluation error" });
                }
            })();
        """.trimIndent()

        val jsonStr = evalJs(webView, script) ?: return@withContext DetectionResult.Error("No response from page")
        try {
            val json = JSONObject(jsonStr)
            when (json.optString("status")) {
                "SUCCESS" -> {
                    val elementId = json.optString("elementId")
                    val name = if (!json.isNull("name")) json.optString("name") else null
                    val info = if (!json.isNull("info")) json.optString("info") else null
                    DetectionResult.Success(
                        RealSuggestionInfo(
                            uid = elementId,
                            name = name,
                            mutualInfo = info
                        )
                    )
                }
                "NOT_LOGGED_IN" -> DetectionResult.NotLoggedIn
                "NOT_ON_FACEBOOK" -> DetectionResult.NotOnFacebook
                "SECURITY_WARNING" -> DetectionResult.SecurityWarning(
                    json.optString("message", "Security checkpoint or verification detected.")
                )
                "NO_SUGGESTIONS" -> DetectionResult.NoSuggestions
                else -> DetectionResult.Error(json.optString("message", "Unable to detect suggestions."))
            }
        } catch (e: Exception) {
            DetectionResult.Error("Failed to parse page data: ${e.message}")
        }
    }

    suspend fun executeConfirmedAddFriend(webView: WebView, elementId: String): ActionResult = withContext(Dispatchers.Main) {
        val clickScript = """
            (function() {
                try {
                    var btn = document.getElementById('$elementId');
                    if (!btn) return JSON.stringify({ status: "NOT_FOUND" });
                    btn.click();
                    return JSON.stringify({ status: "CLICKED" });
                } catch(e) {
                    return JSON.stringify({ status: "ERROR", message: e.message });
                }
            })();
        """.trimIndent()

        val clickResultStr = evalJs(webView, clickScript)
        delay(400)

        val verifyScript = """
            (function() {
                try {
                    var dialog = document.querySelector('[role="dialog"], [role="alertdialog"], div[data-sigil*="dialog"]');
                    if (dialog) {
                        var dText = (dialog.innerText || dialog.textContent || "").toLowerCase();
                        if (dText.includes("temporarily blocked") || 
                            dText.includes("can't use this feature") || 
                            dText.includes("limit") || 
                            dText.includes("restricted") || 
                            dText.includes("try again later")) {
                            return JSON.stringify({ status: "RATE_LIMITED" });
                        }
                    }

                    if (window.location.href.includes("checkpoint") || document.querySelector('form[action*="checkpoint"]')) {
                        return JSON.stringify({ status: "CHECKPOINT" });
                    }

                    var btn = document.getElementById('$elementId');
                    if (btn) {
                        btn.setAttribute('data-assistant-processed', 'sent');
                        btn.setAttribute('data-assistant-status', 'sent');
                        var card = btn.closest('li, [role="listitem"]') || btn.parentElement;
                        if (card) {
                            card.setAttribute('data-assistant-processed', 'sent');
                            card.style.outline = 'none';
                        }
                    }

                    return JSON.stringify({ status: "SUCCESS" });
                } catch(e) {
                    return JSON.stringify({ status: "SUCCESS" });
                }
            })();
        """.trimIndent()

        val verifyResultStr = evalJs(webView, verifyScript)
            ?: return@withContext ActionResult.Failed("No verification response from page.")
        try {
            val verifyJson = JSONObject(verifyResultStr)
            when (verifyJson.optString("status")) {
                "SUCCESS" -> ActionResult.Completed
                "CHECKPOINT" -> ActionResult.Failed("Facebook security checkpoint encountered.")
                "RATE_LIMITED" -> ActionResult.Failed("Facebook rate limit or temporary restriction detected.")
                "UNCONFIRMED" -> ActionResult.Failed("Action was not confirmed by Facebook. Request was not sent.")
                else -> ActionResult.Failed(verifyJson.optString("message", "Request verification failed."))
            }
        } catch (e: Exception) {
            ActionResult.Failed("Verification error: ${e.message}")
        }
    }

    suspend fun skipSuggestion(webView: WebView, elementId: String): Boolean = withContext(Dispatchers.Main) {
        val script = """
            (function() {
                try {
                    var btn = document.getElementById('$elementId');
                    if (!btn) return JSON.stringify({ skipped: false });
                    btn.setAttribute('data-assistant-status', 'skipped');
                    btn.setAttribute('data-assistant-processed', 'skipped');
                    var card = btn.closest('li, [role="listitem"]') || btn.parentElement?.parentElement?.parentElement || btn.parentElement;
                    if (card) {
                        card.setAttribute('data-assistant-processed', 'skipped');
                        card.style.outline = 'none';
                        card.style.opacity = '0.4';
                    }
                    window.scrollBy({ top: 180, behavior: 'smooth' });
                    return JSON.stringify({ skipped: true });
                } catch(e) {
                    return JSON.stringify({ skipped: false });
                }
            })();
        """.trimIndent()

        val resultStr = evalJs(webView, script) ?: return@withContext false
        return@withContext try {
            JSONObject(resultStr).optBoolean("skipped", false)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun stopWorkflow(webView: WebView): String? = withContext(Dispatchers.Main) {
        val script = """
            (function() {
                try {
                    var elements = document.querySelectorAll('[data-assistant-active], [style*="outline"]');
                    for (var i = 0; i < elements.length; i++) {
                        elements[i].removeAttribute('data-assistant-active');
                        elements[i].style.outline = 'none';
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        evalJs(webView, script)
    }

    private suspend fun evalJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { rawResult ->
                if (continuation.isActive) {
                    val unquoted = if (rawResult != null && rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                        try {
                            JSONTokener(rawResult).nextValue().toString()
                        } catch (_: Exception) {
                            rawResult.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                        }
                    } else {
                        rawResult
                    }
                    continuation.resume(unquoted)
                }
            }
        }
}
