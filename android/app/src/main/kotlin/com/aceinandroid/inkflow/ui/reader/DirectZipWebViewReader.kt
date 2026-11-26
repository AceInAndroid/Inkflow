package com.aceinandroid.inkflow.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.aceinandroid.inkflow.Chapter
import com.aceinandroid.inkflow.ReaderAppearance
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

private const val VIRTUAL_ORIGIN = "https://appassets.inkflow.local"
private const val VIRTUAL_PREFIX = "/epub/"

/**
 * Inspects an EPUB's OPF metadata to detect fixed-layout (`rendition:layout pre-paginated`) books
 * that benefit from the on-demand native direct-ZIP WebView fallback.
 */
fun isFixedLayoutEpub(file: File): Boolean {
    if (!file.extension.equals("epub", ignoreCase = true) || !file.isFile) return false
    return runCatching {
        ZipFile(file).use { zip ->
            val containerEntry = zip.getEntry("META-INF/container.xml") ?: return false
            val containerXml = zip.getInputStream(containerEntry).bufferedReader().use { it.readText() }
            val opfPath = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerXml)?.groupValues?.get(1)
                ?: return false
            val opfEntry = zip.getEntry(opfPath) ?: return false
            val opfXml = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }
            opfXml.contains("pre-paginated", ignoreCase = true)
        }
    }.getOrDefault(false)
}

/**
 * On-demand lightweight native Android WebView fallback for fixed-layout or CSS-heavy EPUBs.
 * - ZERO Flutter PlatformView overhead.
 * - ZERO local 127.0.0.1 HTTP server and zero full-book Blob fetching:
 *   streams XHTML, CSS, SVG, fonts, and images directly from the EPUB ZipFile via shouldInterceptRequest.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DirectZipWebViewReader(
    epubFile: File,
    chapter: Chapter,
    appearance: ReaderAppearance,
    eInkMode: Boolean,
    onTextSelected: (String) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zipHolder = remember(epubFile.absolutePath) {
        runCatching { ZipFile(epubFile) }.getOrNull()
    }
    DisposableEffect(zipHolder) {
        onDispose { runCatching { zipHolder?.close() } }
    }

    val bgHex = remember(appearance.background) {
        String.format("#%06X", 0xFFFFFF and appearance.background.toArgb())
    }
    val fgHex = remember(appearance.foreground) {
        String.format("#%06X", 0xFFFFFF and appearance.foreground.toArgb())
    }
    val chapterUrl = "$VIRTUAL_ORIGIN$VIRTUAL_PREFIX${chapter.path.trimStart('/')}"

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    builtInZoomControls = true
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onSelection(text: String) {
                            post { onTextSelected(text.trim()) }
                        }

                        @JavascriptInterface
                        fun onCenterTap() {
                            post { onToggleChrome() }
                        }
                    },
                    "InkflowBridge",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val url = request?.url ?: return null
                        if ("${url.scheme}://${url.host}" != VIRTUAL_ORIGIN) {
                            // Block all external network fetches from inside local EPUB documents
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        val rawPath = url.path?.removePrefix(VIRTUAL_PREFIX)?.trimStart('/') ?: return null
                        val zip = zipHolder ?: return null
                        val entry = zip.getEntry(rawPath) ?: return null
                        return runCatching {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            WebResourceResponse(
                                mimeTypeFor(rawPath),
                                if (isTextMime(rawPath)) "utf-8" else null,
                                ByteArrayInputStream(bytes),
                            )
                        }.getOrNull()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(
                            buildInjectionScript(bgHex, fgHex, appearance.fontSize, appearance.lineSpacing, eInkMode),
                            null,
                        )
                    }
                }
                loadUrl(chapterUrl)
            }
        },
        update = { webView ->
            if (webView.url != chapterUrl) {
                webView.loadUrl(chapterUrl)
            } else {
                webView.evaluateJavascript(
                    buildInjectionScript(bgHex, fgHex, appearance.fontSize, appearance.lineSpacing, eInkMode),
                    null,
                )
            }
        },
    )
}

private fun buildInjectionScript(
    bgHex: String,
    fgHex: String,
    fontSizeSp: Int,
    lineSpacing: Float,
    eInkMode: Boolean,
): String {
    val eInkCss = if (eInkMode) {
        "* { animation: none !important; transition: none !important; text-shadow: none !important; }"
    } else ""
    return """
        (function() {
          var styleId = 'inkflow-reader-style';
          var el = document.getElementById(styleId);
          if (!el) {
            el = document.createElement('style');
            el.id = styleId;
            document.head && document.head.appendChild(el);
          }
          el.textContent = 'html, body { background: $bgHex !important; color: $fgHex !important; ' +
            'font-size: ${fontSizeSp}px !important; line-height: $lineSpacing !important; ' +
            'padding: 12px 16px 48px 16px !important; box-sizing: border-box; max-width: 100%; overflow-x: hidden; } ' +
            'img, svg { max-width: 100% !important; height: auto !important; } ' +
            'table { max-width: 100%; border-collapse: collapse; } ' +
            '$eInkCss';
          if (!window.__inkflowHooksInstalled) {
            window.__inkflowHooksInstalled = true;
            document.addEventListener('selectionchange', function() {
              var sel = window.getSelection ? window.getSelection().toString() : '';
              if (window.InkflowBridge && sel && sel.trim().length > 0) {
                window.InkflowBridge.onSelection(sel.trim());
              }
            });
            document.addEventListener('click', function(e) {
              var sel = window.getSelection ? window.getSelection().toString() : '';
              if (sel && sel.trim().length > 0) return;
              if (e.target && (e.target.tagName === 'A' || e.target.closest('a'))) return;
              if (window.InkflowBridge) window.InkflowBridge.onCenterTap();
            });
          }
        })();
    """.trimIndent()
}

private fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "xhtml", "html", "htm" -> "application/xhtml+xml"
    "css" -> "text/css"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "xml" -> "application/xml"
    else -> "application/octet-stream"
}

private fun isTextMime(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("xhtml", "html", "htm", "css", "svg", "xml")
