package com.example.rectgpt

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlInput: EditText
    private lateinit var answerOverlay: TextView

    private val ui = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("rectgpt_prefs", MODE_PRIVATE) }

    private val SYSTEM_PROMPT =
        "Do NOT translate. Answer in the original language used by the user/question or the selected content. " +
            "First, reason silently and verify against the SELECTED TEXT and/or PROVIDED IMAGE(S). " +
            "If the question is multiple-choice and options are present, output EXACTLY the correct option text verbatim. " +
            "Output: ONLY the final answer text, EXACTLY ONE LINE. " +
            "Do NOT repeat or quote the question, and do NOT add labels like Q:, A:, Answer:. " +
            "No explanations, no preambles, no lists, no markdown, no quotes. " +
            "If the answer cannot be determined from the selected text/images, output exactly: I DON'T KNOW"

    private val MODEL_ID = "gpt-5.2"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        urlInput = findViewById(R.id.urlInput)
        answerOverlay = findViewById(R.id.answerOverlay)

        val goBtn: Button = findViewById(R.id.goBtn)
        val keyBtn: Button = findViewById(R.id.keyBtn)

        // WebView hardening + Google login compatibility
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.cacheMode = WebSettings.LOAD_DEFAULT

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Always keep navigation inside the same WebView
                view.loadUrl(request.url.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectRectScript()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // When Google tries to open a new window (login), force it in the same WebView
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                try {
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = view
                    resultMsg.sendToTarget()
                    return true
                } catch (_: Throwable) {
                    return false
                }
            }
        }

        goBtn.setOnClickListener {
            val u = normalizeUrl(urlInput.text?.toString() ?: "")
            web.loadUrl(u)
        }

        keyBtn.setOnClickListener {
            showKeyDialog()
        }

        // Default: Google Forms home (you can paste your form)
        if (savedInstanceState == null) {
            urlInput.setText("https://docs.google.com/forms/")
            web.loadUrl("https://docs.google.com/forms/")
        }
    }

    private fun showKeyDialog() {
        val current = prefs.getString("openai_key", "") ?: ""
        val input = EditText(this)
        input.setText(current)
        input.hint = "OpenAI API key (sk-...)"

        AlertDialog.Builder(this)
            .setTitle("OpenAI API Key")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                prefs.edit().putString("openai_key", input.text?.toString()?.trim() ?: "").apply()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "about:blank"
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.contains(".")) return "https://$s"
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
    }

    private fun injectRectScript() {
        // Load JS from assets and run it in the current page context.
        // This is what makes the rectangle work even inside Google Forms, after login.
        try {
            val js = assets.open("rect_select.js").bufferedReader().use { it.readText() }
            web.evaluateJavascript(js, null)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun showAnswer(text: String) {
        ui.post {
            answerOverlay.text = text
            answerOverlay.visibility = android.view.View.VISIBLE
            // auto-hide after a short time
            ui.removeCallbacksAndMessages(null)
            ui.postDelayed({ answerOverlay.visibility = android.view.View.GONE }, 2200)
        }
    }

    private fun sanitizeAnswer(s: String): String {
        var t = s.trim()
        if (t.isEmpty()) return "I DON'T KNOW"
        val lines = t.replace("\r\n", "\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        fun isLabel(x: String) = Regex("^(q(uestion)?|a(nswer)?|risposta)\\s*[:\\-]", RegexOption.IGNORE_CASE).containsMatchIn(x)
        var candidates = lines.filter { !isLabel(it) }
        if (candidates.isEmpty()) candidates = lines
        val nonQuestion = candidates.filter { !it.contains("?") }
        t = if (nonQuestion.isNotEmpty()) nonQuestion.last() else candidates.lastOrNull() ?: ""
        if (t.contains("?")) t = t.substringAfterLast("?").trim()
        t = t.replace(Regex("^[\\-\\*\\u2022]\\s+"), "").trim()
        t = t.replace(Regex("^(\\d+[\\)\\.])\\s+"), "").trim()
        t = t.trim('"', '\'', '“', '”', '‘', '’')
        t = t.replace(Regex("^(answer|risposta)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "").trim()
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.isEmpty()) "I DON'T KNOW" else t
    }

    private fun callOpenAIResponses(apiKey: String, selectedText: String, pageUrl: String): String {
        val prompt = buildUserPrompt(selectedText, pageUrl)

        val payload = JSONObject().apply {
            put("model", MODEL_ID)
            put("max_output_tokens", 160)
            put(
                "input",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            )
        }

        val url = URL("https://api.openai.com/v1/responses")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = 20000
            readTimeout = 25000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (code !in 200..299) {
            // Return a compact error
            return "HTTP $code"
        }

        return extractOutputText(body)
    }

    private fun extractOutputText(json: String): String {
        val obj = JSONObject(json)
        val direct = obj.optString("output_text", "").trim()
        if (direct.isNotEmpty()) return direct

        val out = StringBuilder()
        val arr = obj.optJSONArray("output") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") {
                    out.append(c.optString("text", ""))
                }
            }
        }
        return out.toString().trim()
    }

    private fun buildUserPrompt(selectedText: String, urlFromPage: String): String {
        val text = selectedText.take(12000)
        return listOf(
            "URL: $urlFromPage",
            "",
            "SELECTED TEXT (may be empty):",
            "----",
            text,
            "----",
            "",
            "INSTRUCTION: one line only. Do NOT translate."
        ).joinToString("\n")
    }

    inner class Bridge {
        @JavascriptInterface
        fun onSel(json: String) {
            // Called from injected JS when rectangle selection ends.
            try {
                val obj = JSONObject(json)
                val text = obj.optString("text", "").trim()
                val url = obj.optString("url", "")

                if (text.isEmpty()) {
                    showAnswer("I DON'T KNOW")
                    return
                }

                showAnswer("…")

                val key = (prefs.getString("openai_key", "") ?: "").trim()
                if (key.isEmpty()) {
                    showAnswer("Missing OpenAI key")
                    return
                }

                Thread {
                    val raw = try {
                        callOpenAIResponses(key, text, url)
                    } catch (_: Throwable) {
                        "HTTP 0"
                    }
                    val clean = sanitizeAnswer(raw)
                    showAnswer(clean)
                }.start()

            } catch (_: Throwable) {
                showAnswer("I DON'T KNOW")
            }
        }
    }
}
