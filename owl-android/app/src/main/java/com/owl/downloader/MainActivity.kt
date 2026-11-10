package com.owl.downloader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Window
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.owl.downloader.database.DownloadedVideoInfo
import com.owl.downloader.database.OwlDatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero-dependency ContentProvider (`content://com.owl.downloader.provider/...`)
 * Enables sharing downloaded `.mp4` / `.m4a` / `.mp3` files to WhatsApp, Telegram, Gallery,
 * and external video players on Android 7.0 – 15.0 without `FileUriExposedException`.
 */
class OwlFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.owl.downloader.provider"

        fun getUriForFile(file: File): Uri {
            val encodedPath = Uri.encode(file.absolutePath)
            return Uri.parse("content://$AUTHORITY/media/$encodedPath")
        }

        private fun resolveFile(uri: Uri): File {
            val encoded = uri.lastPathSegment ?: ""
            val decodedPath = URLDecoder.decode(encoded, "UTF-8")
            return File(decodedPath)
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val file = resolveFile(uri)
        return when {
            file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            file.name.endsWith(".png", ignoreCase = true) -> "image/png"
            file.name.endsWith(".webp", ignoreCase = true) -> "image/webp"
            file.name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            file.name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            file.name.endsWith(".webm", ignoreCase = true) -> "video/webm"
            else -> "video/mp4"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = Array<Any?>(cols.size) { i ->
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(values) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

data class ResolvedMediaStream(
    val streamUrl: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val extractor: String,
    val userAgent: String,
    val isYoutubeRangeStream: Boolean = false,
    val estimatedBytes: Long = 0L,
    val extension: String = "mp4"
)

/**
 * Owl (`com.owl.downloader`) — Real Multi-Platform Video/Audio Downloader, Player, Sharer & Saver
 */
class MainActivity : Activity() {

    private lateinit var db: OwlDatabaseHelper
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSharedUrl: String? = null
    private val canceledTasks = ConcurrentHashMap<String, Boolean>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window.statusBarColor = Color.parseColor("#0D1222")
        window.navigationBarColor = Color.parseColor("#1C1518")

        db = OwlDatabaseHelper(this)
        cleanUpMissingVideos()
        extractShareIntent(intent)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1222"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                textZoom = 100
            }
            addJavascriptInterface(OwlNativeBridge(), "OwlBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pendingSharedUrl?.let { shared ->
                        val escaped = JSONObject.quote(shared)
                        webView.evaluateJavascript("window.onOwlSharedUrl && window.onOwlSharedUrl($escaped);", null)
                        pendingSharedUrl = null
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }
            }
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        extractShareIntent(intent)
        pendingSharedUrl?.let { shared ->
            val escaped = JSONObject.quote(shared)
            webView.evaluateJavascript("window.onOwlSharedUrl && window.onOwlSharedUrl($escaped);", null)
            pendingSharedUrl = null
        }
    }

    private fun extractShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                pendingSharedUrl = extractFirstUrl(sharedText)
            }
        }
    }

    private fun extractFirstUrl(rawText: String): String {
        val m = Regex("https?://[^\\s\"<>]+").find(rawText)
        return m?.value?.trim() ?: rawText.trim()
    }

    private fun getAppMediaDir(): File {
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        return File(base, "Owl").apply { mkdirs() }
    }

    private fun cleanUpMissingVideos() {
        runCatching {
            for (v in db.getAllDownloadedVideos()) {
                val f = File(v.videoPath)
                if (!f.exists() || f.length() == 788493L || v.videoPath.contains("Big_Buck_Bunny")) {
                    // Remove any old demo files from previous version
                    runCatching { if (f.length() == 788493L) f.delete() }
                    db.deleteDownloadedVideo(v.id)
                }
            }
        }
    }

    private fun isImageFileName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
    }

    private fun exportToPublicDownloads(sourceFile: File, displayFileName: String): String {
        val isImg = isImageFileName(displayFileName)
        val isAudio = displayFileName.endsWith(".mp3", ignoreCase = true) || displayFileName.endsWith(".m4a", ignoreCase = true)
        val mimeType = when {
            isImg && displayFileName.endsWith(".png", ignoreCase = true) -> "image/png"
            isImg && displayFileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            isImg -> "image/jpeg"
            isAudio -> "audio/mp4"
            else -> "video/mp4"
        }

        // 1. Direct public Download/Owl directory
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val owlPublicDir = File(publicDownloads, "Owl")
        val directDest = File(owlPublicDir, displayFileName)
        val directSuccess = runCatching {
            owlPublicDir.mkdirs()
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(directDest).use { output ->
                    input.copyTo(output)
                }
            }
            MediaScannerConnection.scanFile(
                this@MainActivity,
                arrayOf(directDest.absolutePath),
                arrayOf(mimeType),
                null
            )
            true
        }.getOrDefault(false)

        if (directSuccess && directDest.exists() && directDest.length() > 0L) {
            return directDest.absolutePath
        }

        // 2. Android 10+ (API 29+) MediaStore.Downloads
        if (Build.VERSION.SDK_INT >= 29) {
            val mediaStoreSuccess = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Owl")
                }
                val resolver = contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    true
                } else {
                    false
                }
            }.getOrDefault(false)

            if (mediaStoreSuccess) {
                return "/storage/emulated/0/Download/Owl/$displayFileName"
            }
        }

        return sourceFile.absolutePath
    }

    private fun emitTaskUpdate(payload: JSONObject) {
        val jsonStr = JSONObject.quote(payload.toString())
        mainHandler.post {
            if (this::webView.isInitialized) {
                webView.evaluateJavascript("window.onOwlTaskUpdate && window.onOwlTaskUpdate($jsonStr);", null)
            }
        }
    }

    /**
     * Resolves the REAL video/audio stream URL for YouTube, TikTok, X/Twitter, Reddit,
     * Dailymotion, Vimeo, Instagram/Facebook, or direct video links.
     */
    private fun resolveRealMediaStream(rawInput: String, downloadType: String): ResolvedMediaStream {
        val targetUrl = extractFirstUrl(rawInput)
        val isAudioMode = downloadType.equals("Audio", ignoreCase = true)
        val defaultUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        // 1. Check if YouTube URL (watch?v=, youtu.be/, /shorts/, /live/, /embed/)
        val ytRegex = Regex("(?:v=|youtu\\.be/|/shorts/|/embed/|/live/)([a-zA-Z0-9_-]{11})")
        val ytMatch = ytRegex.find(targetUrl)
        if (ytMatch != null) {
            val videoId = ytMatch.groupValues[1]
            val ytResolved = resolveYouTubeInnerTube(videoId, isAudioMode)
            if (ytResolved != null) {
                return ytResolved
            }
            throw IllegalStateException("تعذر استخراج البث المباشر من يوتيوب لهذا الفيديو ($videoId)")
        }

        // 2. Check if TikTok URL (tiktok.com, vm.tiktok.com, vt.tiktok.com)
        if (targetUrl.contains("tiktok.com", ignoreCase = true)) {
            val tikResolved = resolveTikTokStream(targetUrl, isAudioMode, defaultUa)
            if (tikResolved != null) {
                return tikResolved
            }
            throw IllegalStateException("تعذر استخراج فيديو تيك توك من الرابط المدخل")
        }

        // 3. Check if X / Twitter URL (x.com, twitter.com)
        if (targetUrl.contains("twitter.com", ignoreCase = true) || targetUrl.contains("x.com", ignoreCase = true)) {
            val twResolved = resolveTwitterStream(targetUrl, defaultUa)
            if (twResolved != null) {
                return twResolved
            }
            throw IllegalStateException("تعذر استخراج الفيديو أو الصورة من تغريدة X / تويتر")
        }

        // 4. Check if Facebook URL (facebook.com/share/r/..., facebook.com/reel/..., facebook.com/watch/..., fb.watch/...)
        if (targetUrl.contains("facebook.com", ignoreCase = true) || targetUrl.contains("fb.watch", ignoreCase = true) || targetUrl.contains("fb.com", ignoreCase = true)) {
            val fbResolved = resolveFacebookStream(targetUrl)
            if (fbResolved != null) {
                return fbResolved
            }
            throw IllegalStateException("تعذر استخراج الوسائط من فيسبوك. تأكد أن المنشور عام وليس خاصاً.")
        }

        // 5. Check if Instagram URL (instagram.com/reel/..., instagram.com/p/..., instagram.com/share/...)
        if (targetUrl.contains("instagram.com", ignoreCase = true) || targetUrl.contains("instagr.am", ignoreCase = true)) {
            val igResolved = resolveInstagramStream(targetUrl)
            if (igResolved != null) {
                return igResolved
            }
            throw IllegalStateException("تعذر استخراج الفيديو أو الصورة من إنستغرام. تأكد أن المنشور عام.")
        }

        // 6. Check if Vimeo URL
        val vimeoMatch = Regex("vimeo\\.com/(\\d+)").find(targetUrl)
        if (vimeoMatch != null) {
            val vimeoId = vimeoMatch.groupValues[1]
            val vimeoResolved = resolveVimeoStream(vimeoId, defaultUa)
            if (vimeoResolved != null) return vimeoResolved
        }

        // 7. Check if Dailymotion URL
        val dmMatch = Regex("(?:dailymotion\\.com/video/|dai\\.ly/)([a-zA-Z0-9]+)").find(targetUrl)
        if (dmMatch != null) {
            val dmId = dmMatch.groupValues[1]
            val dmResolved = resolveDailymotionStream(dmId, defaultUa)
            if (dmResolved != null) return dmResolved
        }

        // 8. Direct media URL (.mp4, .webm, .m4a, .mp3, .mov, .jpg, .jpeg, .png, .webp)
        val lower = targetUrl.lowercase()
        val cleanLower = lower.substringBefore("?")
        if (cleanLower.endsWith(".mp4") || cleanLower.endsWith(".webm") || cleanLower.endsWith(".mp3") ||
            cleanLower.endsWith(".m4a") || cleanLower.endsWith(".mov") ||
            cleanLower.endsWith(".jpg") || cleanLower.endsWith(".jpeg") ||
            cleanLower.endsWith(".png") || cleanLower.endsWith(".webp")
        ) {
            val host = runCatching { URI(targetUrl).host?.removePrefix("www.") ?: "Direct" }.getOrDefault("Direct")
            val name = targetUrl.substringBefore("?").substringAfterLast("/").ifBlank { "direct_media" }
            val ext = when {
                cleanLower.endsWith(".png") -> "png"
                cleanLower.endsWith(".webp") -> "webp"
                cleanLower.endsWith(".jpg") || cleanLower.endsWith(".jpeg") -> "jpg"
                cleanLower.endsWith(".mp3") -> "mp3"
                cleanLower.endsWith(".m4a") -> "m4a"
                else -> "mp4"
            }
            return ResolvedMediaStream(
                streamUrl = targetUrl,
                title = name,
                author = host,
                thumbnailUrl = if (ext in listOf("jpg", "png", "webp")) targetUrl else "",
                extractor = "Direct",
                userAgent = defaultUa,
                isYoutubeRangeStream = false,
                extension = ext
            )
        }

        // 7. Generic Web / Instagram / Facebook / Reddit OpenGraph & JSON video scraper
        val scraped = resolveGenericPageVideo(targetUrl, defaultUa)
        if (scraped != null) {
            return scraped
        }

        throw IllegalStateException("لم يتم العثور على ملف فيديو قابل للتحميل في هذا الرابط. جرب رابط يوتيوب أو تيك توك أو إكس أو رابط MP4 مباشر.")
    }

    private fun resolveYouTubeInnerTube(videoId: String, isAudioMode: Boolean): ResolvedMediaStream? {
        val playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

        data class ClientSpec(
            val name: String,
            val version: String,
            val ua: String,
            val sdk: Int?,
            val osName: String,
            val osVersion: String,
            val make: String?,
            val model: String?
        )

        val clients = listOf(
            ClientSpec(
                name = "ANDROID",
                version = "20.03.32",
                ua = "com.google.android.youtube/20.03.32 (Linux; U; Android 14)",
                sdk = 34,
                osName = "Android",
                osVersion = "14",
                make = null,
                model = null
            ),
            ClientSpec(
                name = "ANDROID_VR",
                version = "1.60.19",
                ua = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1)",
                sdk = 32,
                osName = "Android",
                osVersion = "12L",
                make = "Oculus",
                model = "Quest 3"
            ),
            ClientSpec(
                name = "IOS",
                version = "20.03.02",
                ua = "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)",
                sdk = null,
                osName = "iPhone",
                osVersion = "18.2.1.22C161",
                make = "Apple",
                model = "iPhone16,2"
            )
        )

        for (c in clients) {
            runCatching {
                val clientObj = JSONObject().apply {
                    put("clientName", c.name)
                    put("clientVersion", c.version)
                    put("osName", c.osName)
                    put("osVersion", c.osVersion)
                    put("hl", "ar")
                    put("gl", "SA")
                    if (c.sdk != null) put("androidSdkVersion", c.sdk)
                    if (c.make != null) put("deviceMake", c.make)
                    if (c.model != null) put("deviceModel", c.model)
                }
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("context", JSONObject().put("client", clientObj))
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }

                val conn = (URL(playerEndpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", c.ua)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val vd = json.optJSONObject("videoDetails")
                    val title = vd?.optString("title", "")?.ifBlank { "YouTube Video $videoId" } ?: "YouTube Video $videoId"
                    val author = vd?.optString("author", "")?.ifBlank { "YouTube Channel" } ?: "YouTube Channel"
                    val thumb = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                    val sd = json.optJSONObject("streamingData")
                    val formats = sd?.optJSONArray("formats") ?: JSONArray()
                    val adaptive = sd?.optJSONArray("adaptiveFormats") ?: JSONArray()

                    var chosenFormat: JSONObject? = null

                    if (isAudioMode) {
                        // Look for audio/mp4 (itag 140) in adaptiveFormats first
                        for (i in 0 until adaptive.length()) {
                            val f = adaptive.getJSONObject(i)
                            val mime = f.optString("mimeType", "")
                            val url = f.optString("url", "")
                            if (url.isNotBlank() && mime.startsWith("audio/mp4")) {
                                chosenFormat = f
                                if (f.optInt("itag") == 140) break
                            }
                        }
                    }

                    // For Video mode (or fallback if audio format wasn't found), pick combined video/mp4 from formats
                    if (chosenFormat == null) {
                        for (i in 0 until formats.length()) {
                            val f = formats.getJSONObject(i)
                            val mime = f.optString("mimeType", "")
                            val url = f.optString("url", "")
                            if (url.isNotBlank() && mime.startsWith("video/mp4")) {
                                chosenFormat = f
                                break
                            }
                        }
                    }

                    // Fallback to adaptive video/mp4 (e.g., itag 135/134/136) if formats was empty
                    if (chosenFormat == null) {
                        for (i in 0 until adaptive.length()) {
                            val f = adaptive.getJSONObject(i)
                            val mime = f.optString("mimeType", "")
                            val url = f.optString("url", "")
                            if (url.isNotBlank() && mime.startsWith("video/mp4") && mime.contains("avc1")) {
                                chosenFormat = f
                                break
                            }
                        }
                    }

                    if (chosenFormat != null) {
                        val streamUrl = chosenFormat.optString("url", "")
                        val clen = chosenFormat.optString("contentLength", "0").toLongOrNull() ?: 0L
                        val durMs = chosenFormat.optString("approxDurationMs", "0").toLongOrNull() ?: 0L
                        val bitrate = chosenFormat.optLong("bitrate", 0L)
                        val estBytes = if (clen > 0L) clen else if (durMs > 0L && bitrate > 0L) (durMs * bitrate) / 8000L else 0L

                        conn.disconnect()
                        return ResolvedMediaStream(
                            streamUrl = streamUrl,
                            title = title,
                            author = author,
                            thumbnailUrl = thumb,
                            extractor = "Youtube",
                            userAgent = c.ua,
                            isYoutubeRangeStream = true,
                            estimatedBytes = estBytes,
                            extension = "mp4"
                        )
                    }
                }
                conn.disconnect()
            }
        }
        return null
    }

    private fun resolveTikTokStream(targetUrl: String, isAudioMode: Boolean, ua: String): ResolvedMediaStream? {
        runCatching {
            val apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(targetUrl, "UTF-8") + "&hd=1"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", ua)
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (json.optInt("code", -1) == 0) {
                    val data = json.optJSONObject("data")
                    if (data != null) {
                        val playUrl = if (isAudioMode) {
                            data.optString("music", "").ifBlank { data.optString("play", "") }
                        } else {
                            data.optString("play", "").ifBlank { data.optString("hdplay", "") }.ifBlank { data.optString("wmplay", "") }
                        }
                        if (playUrl.isNotBlank()) {
                            val title = data.optString("title", "").ifBlank { "TikTok Video" }
                            val author = data.optJSONObject("author")?.optString("nickname", "") ?: "TikTok Creator"
                            val cover = data.optString("cover", "")
                            conn.disconnect()
                            return ResolvedMediaStream(
                                streamUrl = playUrl,
                                title = title,
                                author = author,
                                thumbnailUrl = cover,
                                extractor = "TikTok",
                                userAgent = ua,
                                isYoutubeRangeStream = false,
                                extension = "mp4"
                            )
                        }
                    }
                }
            }
            conn.disconnect()
        }
        return null
    }

    private fun resolveTwitterStream(targetUrl: String, ua: String): ResolvedMediaStream? {
        val idMatch = Regex("status/(\\d+)").find(targetUrl) ?: return null
        val tweetId = idMatch.groupValues[1]
        val requestedIdx = (Regex("/(?:photo|video)/(\\d+)").find(targetUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1) - 1

        // 1. Try fxtwitter API (supports both videos and photos)
        runCatching {
            val apiUrl = "https://api.fxtwitter.com/status/$tweetId"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "OwlDownloader/1.13.7")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tweet = json.optJSONObject("tweet")
                val media = tweet?.optJSONObject("media")
                val author = tweet?.optJSONObject("author")?.optString("name", "")?.ifBlank { "X User" } ?: "X User"
                val rawText = (tweet?.optString("text", "") ?: "").take(60)

                // Check videos first
                val videos = media?.optJSONArray("videos")
                if (videos != null && videos.length() > 0) {
                    val idx = requestedIdx.coerceIn(0, videos.length() - 1)
                    val vObj = videos.getJSONObject(idx)
                    val vUrl = vObj.optString("url", "")
                    if (vUrl.isNotBlank()) {
                        val thumb = vObj.optString("thumbnail_url", "")
                        conn.disconnect()
                        return ResolvedMediaStream(
                            streamUrl = vUrl,
                            title = rawText.ifBlank { "X Video $tweetId" },
                            author = author,
                            thumbnailUrl = thumb,
                            extractor = "X / Twitter",
                            userAgent = ua,
                            extension = "mp4"
                        )
                    }
                }

                // Check photos next
                val photos = media?.optJSONArray("photos")
                if (photos != null && photos.length() > 0) {
                    val idx = requestedIdx.coerceIn(0, photos.length() - 1)
                    val pObj = photos.getJSONObject(idx)
                    val pUrl = pObj.optString("url", "")
                    if (pUrl.isNotBlank()) {
                        val ext = if (pUrl.lowercase().contains(".png")) "png" else "jpg"
                        conn.disconnect()
                        return ResolvedMediaStream(
                            streamUrl = pUrl,
                            title = rawText.ifBlank { "X Photo $tweetId" },
                            author = author,
                            thumbnailUrl = pUrl,
                            extractor = "X / Twitter",
                            userAgent = ua,
                            extension = ext
                        )
                    }
                }

                // Check media.all fallback
                val allMedia = media?.optJSONArray("all")
                if (allMedia != null && allMedia.length() > 0) {
                    val idx = requestedIdx.coerceIn(0, allMedia.length() - 1)
                    val mObj = allMedia.getJSONObject(idx)
                    val mUrl = mObj.optString("url", "")
                    val mType = mObj.optString("type", "").lowercase()
                    if (mUrl.isNotBlank()) {
                        val isPhoto = mType == "photo" || mType == "image" || mUrl.contains("pbs.twimg.com/media")
                        val ext = if (isPhoto) {
                            if (mUrl.lowercase().contains(".png")) "png" else "jpg"
                        } else {
                            "mp4"
                        }
                        val thumb = mObj.optString("thumbnail_url", "").ifBlank { if (isPhoto) mUrl else "" }
                        conn.disconnect()
                        return ResolvedMediaStream(
                            streamUrl = mUrl,
                            title = rawText.ifBlank { if (isPhoto) "X Photo $tweetId" else "X Video $tweetId" },
                            author = author,
                            thumbnailUrl = thumb,
                            extractor = "X / Twitter",
                            userAgent = ua,
                            extension = ext
                        )
                    }
                }
            }
            conn.disconnect()
        }

        // 2. Fallback to vxtwitter API (supports videos and images)
        runCatching {
            val apiUrl = "https://api.vxtwitter.com/Twitter/status/$tweetId"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "TelegramBot (like TwitterBot)")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val rawText = json.optString("text", "").take(60)
                val author = json.optString("user_name", "X User").ifBlank { "X User" }

                val mediaExt = json.optJSONArray("media_extended")
                if (mediaExt != null && mediaExt.length() > 0) {
                    val idx = requestedIdx.coerceIn(0, mediaExt.length() - 1)
                    val mObj = mediaExt.getJSONObject(idx)
                    val mUrl = mObj.optString("url", "")
                    val mType = mObj.optString("type", "").lowercase()
                    if (mUrl.isNotBlank()) {
                        val isPhoto = mType == "image" || mType == "photo" || mUrl.contains("pbs.twimg.com/media")
                        val ext = if (isPhoto) {
                            if (mUrl.lowercase().contains(".png")) "png" else "jpg"
                        } else {
                            "mp4"
                        }
                        val thumb = mObj.optString("thumbnail_url", "").ifBlank { if (isPhoto) mUrl else "" }
                        conn.disconnect()
                        return ResolvedMediaStream(
                            streamUrl = mUrl,
                            title = rawText.ifBlank { if (isPhoto) "X Photo $tweetId" else "X Video $tweetId" },
                            author = author,
                            thumbnailUrl = thumb,
                            extractor = "X / Twitter",
                            userAgent = ua,
                            extension = ext
                        )
                    }
                }

                val mediaUrls = json.optJSONArray("mediaURLs")
                if (mediaUrls != null && mediaUrls.length() > 0) {
                    val idx = requestedIdx.coerceIn(0, mediaUrls.length() - 1)
                    val mUrl = mediaUrls.optString(idx, "")
                    if (mUrl.isNotBlank()) {
                        val isPhoto = mUrl.contains("pbs.twimg.com/media") || mUrl.lowercase().endsWith(".jpg") || mUrl.lowercase().endsWith(".png")
                        val ext = if (isPhoto) "jpg" else "mp4"
                        conn.disconnect()
                        return ResolvedMediaStream(
                            streamUrl = mUrl,
                            title = rawText.ifBlank { if (isPhoto) "X Photo $tweetId" else "X Video $tweetId" },
                            author = author,
                            thumbnailUrl = if (isPhoto) mUrl else "",
                            extractor = "X / Twitter",
                            userAgent = ua,
                            extension = ext
                        )
                    }
                }
            }
            conn.disconnect()
        }

        // 3. Fallback to Twitter Syndication API (tweet-result)
        runCatching {
            val synUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=a"
            val conn = (URL(synUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", ua)
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val rawText = json.optString("text", "").take(60)
                val author = json.optJSONObject("user")?.optString("name", "X User") ?: "X User"
                val mediaDetails = json.optJSONArray("mediaDetails")
                if (mediaDetails != null && mediaDetails.length() > 0) {
                    val idx = requestedIdx.coerceIn(0, mediaDetails.length() - 1)
                    val item = mediaDetails.getJSONObject(idx)
                    val mType = item.optString("type", "").lowercase()
                    if (mType == "video" || mType == "animated_gif") {
                        val variants = item.optJSONObject("video_info")?.optJSONArray("variants")
                        var bestUrl = ""
                        var bestBitrate = -1
                        if (variants != null) {
                            for (i in 0 until variants.length()) {
                                val v = variants.getJSONObject(i)
                                if (v.optString("content_type") == "video/mp4") {
                                    val br = v.optInt("bitrate", 0)
                                    if (br >= bestBitrate) {
                                        bestBitrate = br
                                        bestUrl = v.optString("url", "")
                                    }
                                }
                            }
                        }
                        if (bestUrl.isNotBlank()) {
                            val thumb = item.optString("media_url_https", "")
                            conn.disconnect()
                            return ResolvedMediaStream(
                                streamUrl = bestUrl,
                                title = rawText.ifBlank { "X Video $tweetId" },
                                author = author,
                                thumbnailUrl = thumb,
                                extractor = "X / Twitter",
                                userAgent = ua,
                                extension = "mp4"
                            )
                        }
                    } else {
                        val photoUrl = item.optString("media_url_https", "")
                        if (photoUrl.isNotBlank()) {
                            conn.disconnect()
                            return ResolvedMediaStream(
                                streamUrl = photoUrl,
                                title = rawText.ifBlank { "X Photo $tweetId" },
                                author = author,
                                thumbnailUrl = photoUrl,
                                extractor = "X / Twitter",
                                userAgent = ua,
                                extension = "jpg"
                            )
                        }
                    }
                }
            }
            conn.disconnect()
        }

        return null
    }

    private fun decodeJsonEscapedUrl(raw: String): String {
        val unescaped = runCatching {
            JSONObject("{\"u\":\"$raw\"}").getString("u")
        }.getOrDefault(raw)
        return unescaped
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\\\u0026", "&")
            .replace("\\u0026", "&")
            .replace("\\\\u0025", "%")
            .replace("\\u0025", "%")
            .replace("&#x26;", "&")
            .replace("&#38;", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun fetchHtmlWithDesktopHeaders(initialUrl: String): Pair<String, String>? {
        val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        var currentUrl = initialUrl
        val cookieMap = mutableMapOf<String, String>()

        for (hop in 0..6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 9000
                readTimeout = 9000
                setRequestProperty("User-Agent", desktopUa)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Sec-Fetch-Mode", "navigate")
                setRequestProperty("Sec-Fetch-Dest", "document")
                setRequestProperty("Upgrade-Insecure-Requests", "1")
                if (cookieMap.isNotEmpty()) {
                    setRequestProperty("Cookie", cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }

            val code = conn.responseCode
            // Collect Set-Cookie headers across redirects
            conn.headerFields["Set-Cookie"]?.forEach { sc ->
                val pair = sc.substringBefore(";").split("=", limit = 2)
                if (pair.size == 2) {
                    cookieMap[pair[0].trim()] = pair[1].trim()
                }
            }

            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) break
                currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                continue
            }

            if (code in 200..299) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return Pair(currentUrl, html)
            }
            conn.disconnect()
            break
        }
        return null
    }

    private fun resolveFacebookStream(targetUrl: String): ResolvedMediaStream? {
        val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        runCatching {
            val fetched = fetchHtmlWithDesktopHeaders(targetUrl) ?: return null
            val finalUrl = fetched.first
            val html = fetched.second

            val patterns = listOf(
                Regex("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"hd_src_no_ratelimit\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"hd_src\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"sd_src\"\\s*:\\s*\"([^\"]+)\""),
                Regex("(https?:\\\\/\\\\/video[^\"'\\s<>]+?\\.mp4[^\"'\\s<>]*)")
            )

            for (pat in patterns) {
                val m = pat.find(html)
                if (m != null) {
                    val decodedUrl = decodeJsonEscapedUrl(m.groupValues[1])
                    if (decodedUrl.startsWith("http") && !decodedUrl.contains("lookaside.fbsbx.com")) {
                        val reelId = Regex("(?:reel|videos|v|r)/([0-9A-Za-z_-]+)").find(finalUrl)?.groupValues?.get(1) ?: "Video"
                        val ownerMatch = Regex("\"owner\"\\s*:\\s*\\{[^\\}]*?\"name\"\\s*:\\s*\"([^\"]+)\"").find(html)
                        val author = ownerMatch?.let { decodeJsonEscapedUrl(it.groupValues[1]) } ?: "Facebook"
                        val titleMatch = Regex("\"message\"\\s*:\\s*\\{\\s*\"text\"\\s*:\\s*\"([^\"]+)\"").find(html)
                        val rawTitle = titleMatch?.let { decodeJsonEscapedUrl(it.groupValues[1]) }?.take(55)
                            ?: "Facebook Reel $reelId"
                        val thumbMatch = Regex("\"preferred_thumbnail\"\\s*:\\s*\\{[^\\}]*?\"uri\"\\s*:\\s*\"([^\"]+)\"").find(html)
                        val thumb = thumbMatch?.let { decodeJsonEscapedUrl(it.groupValues[1]) } ?: ""

                        return ResolvedMediaStream(
                            streamUrl = decodedUrl,
                            title = rawTitle,
                            author = author,
                            thumbnailUrl = thumb,
                            extractor = "Facebook",
                            userAgent = desktopUa
                        )
                    }
                }
            }
        }
        return null
    }

    private fun resolveInstagramStream(targetUrl: String): ResolvedMediaStream? {
        val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        runCatching {
            // If it's a /share/ URL, resolve redirect first to get the canonical /reel/ or /p/ SHORTCODE URL
            var workingUrl = targetUrl
            if (workingUrl.contains("/share/", ignoreCase = true)) {
                val initialFetch = fetchHtmlWithDesktopHeaders(workingUrl)
                if (initialFetch != null) {
                    workingUrl = initialFetch.first
                }
            }

            val scMatch = Regex("instagram\\.com/(?:[^/]+/)?(reel|reels|p|tv)/([A-Za-z0-9_-]+)").find(workingUrl)
            val pathKind = scMatch?.groupValues?.get(1) ?: "p"
            val shortcode = scMatch?.groupValues?.get(2) ?: ""
            val cleanUrl = if (shortcode.isNotBlank()) {
                val normKind = if (pathKind.startsWith("reel")) "reel" else "p"
                "https://www.instagram.com/$normKind/$shortcode/"
            } else {
                workingUrl.substringBefore("?")
            }

            val fetched = fetchHtmlWithDesktopHeaders(cleanUrl)
            val html = fetched?.second ?: ""

            val videoPatterns = listOf(
                Regex("\"video_versions\"\\s*:\\s*\\[\\s*\\{[^\\}]*?\"url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\"video_url\"\\s*:\\s*\"([^\"]+)\""),
                Regex("\\\\\"video_url\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\""),
                Regex("<meta\\s+property=[\"']og:video(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                Regex("(https?:\\\\/\\\\/[^\"'\\s<>]*cdninstagram\\.com[^\"'\\s<>]*\\.mp4[^\"'\\s<>]*)"),
                Regex("(https?:\\\\/\\\\/instagram\\.[^\"'\\s<>]*fbcdn\\.net[^\"'\\s<>]*\\.mp4[^\"'\\s<>]*)")
            )

            // 1. Check for Video stream on main page
            if (html.isNotBlank()) {
                val userMatch = Regex("\"user\"\\s*:\\s*\\{[^\\}]*?\"username\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    ?: Regex("\"owner\"\\s*:\\s*\\{[^\\}]*?\"username\"\\s*:\\s*\"([^\"]+)\"").find(html)
                val author = userMatch?.groupValues?.get(1) ?: "Instagram"
                val captionMatch = Regex("\"caption\"\\s*:\\s*\\{[^\\}]*?\"text\"\\s*:\\s*\"([^\"]+)\"").find(html)
                val rawCaption = captionMatch?.let { decodeJsonEscapedUrl(it.groupValues[1]).take(55) } ?: ""

                for (pat in videoPatterns) {
                    val m = pat.find(html)
                    if (m != null) {
                        val decodedUrl = decodeJsonEscapedUrl(m.groupValues[1])
                        if (decodedUrl.startsWith("http")) {
                            val title = rawCaption.ifBlank { "Instagram Reel ${shortcode.ifBlank { "Video" }}" }
                            val thumbMatch = Regex("\"image_versions2\"\\s*:\\s*\\{\\s*\"candidates\"\\s*:\\s*\\[\\s*\\{[^\\}]*?\"url\"\\s*:\\s*\"([^\"]+)\"").find(html)
                            val thumb = thumbMatch?.let { decodeJsonEscapedUrl(it.groupValues[1]) } ?: ""

                            return ResolvedMediaStream(
                                streamUrl = decodedUrl,
                                title = title,
                                author = "@$author",
                                thumbnailUrl = thumb,
                                extractor = "Instagram",
                                userAgent = desktopUa,
                                extension = "mp4"
                            )
                        }
                    }
                }
            }

            // 2. Check Embed Captioned endpoint (/p/<shortcode>/embed/captioned/) for Video or high-res Photo
            if (shortcode.isNotBlank()) {
                val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
                val embedFetched = fetchHtmlWithDesktopHeaders(embedUrl)
                val embedHtml = embedFetched?.second ?: ""
                if (embedHtml.isNotBlank()) {
                    val embedUser = Regex("class=[\"']UsernameText[\"'][^>]*>([^<]+)<").find(embedHtml)?.groupValues?.get(1)?.trim()
                        ?: Regex("\"username\"\\s*:\\s*\"([^\"]+)\"").find(embedHtml)?.groupValues?.get(1)
                        ?: "Instagram"

                    // Check if embed page has a video first
                    for (pat in videoPatterns) {
                        val vm = pat.find(embedHtml)
                        if (vm != null) {
                            val vUrl = decodeJsonEscapedUrl(vm.groupValues[1])
                            if (vUrl.startsWith("http")) {
                                return ResolvedMediaStream(
                                    streamUrl = vUrl,
                                    title = "Instagram Video $shortcode",
                                    author = "@$embedUser",
                                    thumbnailUrl = "",
                                    extractor = "Instagram",
                                    userAgent = desktopUa,
                                    extension = "mp4"
                                )
                            }
                        }
                    }

                    // Check if embed page has EmbeddedMediaImage (Photo post)
                    val embedImgPatterns = listOf(
                        Regex("class=[\"']EmbeddedMediaImage[\"'][^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                        Regex("src=[\"']([^\"']+)[\"'][^>]+class=[\"']EmbeddedMediaImage[\"']", RegexOption.IGNORE_CASE),
                        Regex("\\\\\"display_url\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\""),
                        Regex("\"display_url\"\\s*:\\s*\"([^\"]+)\"")
                    )
                    for (ipat in embedImgPatterns) {
                        val im = ipat.find(embedHtml)
                        if (im != null) {
                            val imgUrl = decodeJsonEscapedUrl(im.groupValues[1])
                            if (imgUrl.startsWith("http") && !imgUrl.contains("rsrc.php") && !imgUrl.contains("s150x150")) {
                                return ResolvedMediaStream(
                                    streamUrl = imgUrl,
                                    title = "Instagram Photo $shortcode",
                                    author = "@$embedUser",
                                    thumbnailUrl = imgUrl,
                                    extractor = "Instagram",
                                    userAgent = desktopUa,
                                    extension = "jpg"
                                )
                            }
                        }
                    }
                }
            }

            // 3. Fallback: Check main page HTML for Photo (image_versions2, display_url, og:image)
            if (html.isNotBlank()) {
                val userMatch = Regex("\"user\"\\s*:\\s*\\{[^\\}]*?\"username\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    ?: Regex("\"owner\"\\s*:\\s*\\{[^\\}]*?\"username\"\\s*:\\s*\"([^\"]+)\"").find(html)
                val author = userMatch?.groupValues?.get(1) ?: "Instagram"
                val imagePatterns = listOf(
                    Regex("\"image_versions2\"\\s*:\\s*\\{\\s*\"candidates\"\\s*:\\s*\\[\\s*\\{[^\\}]*?\"url\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\"display_url\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\\\\\"display_url\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\""),
                    Regex("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                )
                for (ipat in imagePatterns) {
                    val im = ipat.find(html)
                    if (im != null) {
                        val imgUrl = decodeJsonEscapedUrl(im.groupValues[1])
                        if (imgUrl.startsWith("http") && !imgUrl.contains("rsrc.php") && !imgUrl.contains("s150x150")) {
                            return ResolvedMediaStream(
                                streamUrl = imgUrl,
                                title = "Instagram Photo ${shortcode.ifBlank { "Image" }}",
                                author = "@$author",
                                thumbnailUrl = imgUrl,
                                extractor = "Instagram",
                                userAgent = desktopUa,
                                extension = "jpg"
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    private fun resolveVimeoStream(vimeoId: String, ua: String): ResolvedMediaStream? {
        runCatching {
            val cfgUrl = "https://player.vimeo.com/video/$vimeoId/config"
            val conn = (URL(cfgUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", ua)
            }
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val prog = json.optJSONObject("request")?.optJSONObject("files")?.optJSONArray("progressive")
                if (prog != null && prog.length() > 0) {
                    val best = prog.getJSONObject(0)
                    val vUrl = best.optString("url", "")
                    val videoMeta = json.optJSONObject("video")
                    val title = videoMeta?.optString("title", "") ?: "Vimeo $vimeoId"
                    val author = videoMeta?.optJSONObject("owner")?.optString("name", "") ?: "Vimeo Creator"
                    val thumb = videoMeta?.optJSONObject("thumbs")?.optString("base", "") ?: ""
                    conn.disconnect()
                    if (vUrl.isNotBlank()) {
                        return ResolvedMediaStream(
                            streamUrl = vUrl,
                            title = title,
                            author = author,
                            thumbnailUrl = thumb,
                            extractor = "Vimeo",
                            userAgent = ua
                        )
                    }
                }
            }
            conn.disconnect()
        }
        return null
    }

    private fun resolveDailymotionStream(dmId: String, ua: String): ResolvedMediaStream? {
        runCatching {
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$dmId"
            val conn = (URL(metaUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", ua)
            }
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val title = json.optString("title", "Dailymotion $dmId")
                val author = json.optJSONObject("owner")?.optString("screenname", "Dailymotion") ?: "Dailymotion"
                val thumb = json.optJSONObject("posters")?.optString("720", "") ?: ""
                val qualities = json.optJSONObject("qualities")
                val autoArr = qualities?.optJSONArray("auto")
                if (autoArr != null && autoArr.length() > 0) {
                    val u = autoArr.getJSONObject(0).optString("url", "")
                    if (u.isNotBlank()) {
                        conn.disconnect()
                        return ResolvedMediaStream(
                            streamUrl = u,
                            title = title,
                            author = author,
                            thumbnailUrl = thumb,
                            extractor = "Dailymotion",
                            userAgent = ua
                        )
                    }
                }
            }
            conn.disconnect()
        }
        return null
    }

    private fun resolveGenericPageVideo(targetUrl: String, ua: String): ResolvedMediaStream? {
        runCatching {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", ua)
            }
            if (conn.responseCode in 200..299) {
                val ctype = conn.contentType?.lowercase() ?: ""
                if (ctype.startsWith("video/") || ctype.startsWith("audio/")) {
                    conn.disconnect()
                    return ResolvedMediaStream(
                        streamUrl = targetUrl,
                        title = targetUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Direct Video" },
                        author = URI(targetUrl).host ?: "Web",
                        thumbnailUrl = "",
                        extractor = "Direct",
                        userAgent = ua
                    )
                }

                val html = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val titleMatch = Regex("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                    ?: Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
                val title = titleMatch?.groupValues?.get(1)?.trim() ?: "Web Video"

                val thumbMatch = Regex("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
                val thumb = thumbMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""

                val patterns = listOf(
                    Regex("<meta\\s+property=[\"']og:video:secure_url[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                    Regex("<meta\\s+property=[\"']og:video:url[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                    Regex("<meta\\s+property=[\"']og:video[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                    Regex("\"video_url\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\"fallback_url\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\"contentUrl\"\\s*:\\s*\"(https?://[^\"]+\\.mp4[^\"]*)\""),
                    Regex("<video[^>]+src=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE),
                    Regex("<source[^>]+src=[\"'](https?://[^\"']+\\.mp4[^\"']*)[\"']", RegexOption.IGNORE_CASE)
                )

                for (p in patterns) {
                    val m = p.find(html)
                    if (m != null) {
                        val rawStream = m.groupValues[1]
                            .replace("\\u0026", "&")
                            .replace("\\/", "/")
                            .replace("&amp;", "&")
                        if (rawStream.startsWith("http")) {
                            val host = runCatching {
                                URI(targetUrl).host?.removePrefix("www.")?.substringBefore(".")?.replaceFirstChar { it.uppercase() }
                            }.getOrNull() ?: "Web"
                            return ResolvedMediaStream(
                                streamUrl = rawStream,
                                title = title,
                                author = host,
                                thumbnailUrl = thumb,
                                extractor = host,
                                userAgent = ua
                            )
                        }
                    }
                }
            } else {
                conn.disconnect()
            }
        }
        return null
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    inner class OwlNativeBridge {

        @JavascriptInterface
        fun getInitialStateJson(): String {
            val root = JSONObject()

            val templatesArr = JSONArray()
            for (t in db.getAllTemplates()) {
                templatesArr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("name", t.name)
                        put("template", t.template)
                    }
                )
            }
            root.put("templates", templatesArr)

            val shortcutsArr = JSONArray()
            for (s in db.getAllShortcuts()) {
                shortcutsArr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("option", s.option)
                    }
                )
            }
            root.put("shortcuts", shortcutsArr)

            val cookiesArr = JSONArray()
            for (c in db.getAllCookies()) {
                cookiesArr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("url", c.url)
                        put("content", c.content)
                    }
                )
            }
            root.put("cookies", cookiesArr)

            val dateFormat = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.ENGLISH)
            val videosArr = JSONArray()
            for (v in db.getAllDownloadedVideos()) {
                val localFile = File(v.videoPath)
                if (!localFile.exists() || localFile.length() == 0L) continue
                val fileUri = Uri.fromFile(localFile).toString()
                val isImg = isImageFileName(localFile.name)
                val sizeLabel = if (localFile.length() < 1024L * 1024L) {
                    String.format(java.util.Locale.ENGLISH, "%d KB", (localFile.length() / 1024L).coerceAtLeast(1L))
                } else {
                    String.format(java.util.Locale.ENGLISH, "%.1f MB", localFile.length().toDouble() / (1024.0 * 1024.0))
                }
                val dateStr = dateFormat.format(java.util.Date(localFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()))
                videosArr.put(
                    JSONObject().apply {
                        put("id", v.id)
                        put("videoTitle", v.videoTitle)
                        put("videoAuthor", v.videoAuthor)
                        put("videoUrl", v.videoUrl)
                        put("thumbnailUrl", if (isImg) fileUri else v.thumbnailUrl)
                        put("videoPath", localFile.absolutePath)
                        put("fileUrl", fileUri)
                        put("fileSize", sizeLabel)
                        put("dateLabel", dateStr)
                        put("extractor", v.extractor)
                        put("isImage", isImg)
                    }
                )
            }
            root.put("videos", videosArr)
            root.put("sdkInt", Build.VERSION.SDK_INT)
            root.put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")

            return root.toString()
        }

        @JavascriptInterface
        fun startRealDownload(taskId: String, rawUrl: String, downloadType: String, flags: String) {
            canceledTasks.remove(taskId)
            val targetUrl = extractFirstUrl(rawUrl)

            Thread {
                try {
                    if (targetUrl.isBlank()) {
                        throw IllegalArgumentException("الرجاء إدخال أو لصق رابط الفيديو أولاً")
                    }

                    emitTaskUpdate(
                        JSONObject().apply {
                            put("taskId", taskId)
                            put("progress", 12)
                            put("status", "RUNNING")
                            put("line", "[owl:extractor] جاري استخراج البث المباشر للفيديو من الرابط...")
                        }
                    )

                    val resolved = resolveRealMediaStream(targetUrl, downloadType)
                    if (canceledTasks[taskId] == true) return@Thread

                    emitTaskUpdate(
                        JSONObject().apply {
                            put("taskId", taskId)
                            put("progress", 25)
                            put("status", "RUNNING")
                            put("title", resolved.title)
                            put("line", "[info] تم العثور على الفيديو: \"${resolved.title}\" (${resolved.extractor})")
                        }
                    )

                    val cleanTitle = resolved.title.replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF _-]"), "").trim()
                        .replace(Regex("\\s+"), "_").take(42).ifBlank { "owl_video" }
                    val fileName = "${cleanTitle}_${System.currentTimeMillis() % 100000}.${resolved.extension}"
                    val destFile = File(getAppMediaDir(), fileName)

                    var bytesWrittenTotal = 0L
                    var lastEmitTime = 0L

                    if (resolved.isYoutubeRangeStream) {
                        // High-speed 4 MB range-chunked download (bypasses YouTube 128 KB/s bandwidth throttle)
                        val chunkSize = 4L * 1024L * 1024L
                        var startByte = 0L
                        val estTotal = resolved.estimatedBytes.takeIf { it > 0L } ?: (12L * 1024L * 1024L)

                        FileOutputStream(destFile).use { output ->
                            while (true) {
                                if (canceledTasks[taskId] == true) {
                                    destFile.delete()
                                    return@Thread
                                }
                                val endByte = startByte + chunkSize - 1L
                                val chunkUrl = if (resolved.streamUrl.contains("?")) {
                                    "${resolved.streamUrl}&range=$startByte-$endByte"
                                } else {
                                    "${resolved.streamUrl}?range=$startByte-$endByte"
                                }

                                val conn = (URL(chunkUrl).openConnection() as HttpURLConnection).apply {
                                    instanceFollowRedirects = true
                                    connectTimeout = 8000
                                    readTimeout = 15000
                                    setRequestProperty("User-Agent", resolved.userAgent)
                                }

                                val code = conn.responseCode
                                if (code !in 200..299) {
                                    conn.disconnect()
                                    break
                                }

                                var chunkRead = 0L
                                val buf = ByteArray(16384)
                                conn.inputStream.use { input ->
                                    while (true) {
                                        if (canceledTasks[taskId] == true) {
                                            conn.disconnect()
                                            destFile.delete()
                                            return@Thread
                                        }
                                        val r = input.read(buf)
                                        if (r == -1) break
                                        output.write(buf, 0, r)
                                        chunkRead += r
                                        bytesWrittenTotal += r

                                        val now = System.currentTimeMillis()
                                        if (now - lastEmitTime >= 160L) {
                                            lastEmitTime = now
                                            val pct = (25 + ((bytesWrittenTotal.toDouble() / estTotal.toDouble()) * 68.0).toInt()).coerceIn(26, 94)
                                            val mbDone = String.format("%.2f", bytesWrittenTotal.toDouble() / (1024.0 * 1024.0))
                                            emitTaskUpdate(
                                                JSONObject().apply {
                                                    put("taskId", taskId)
                                                    put("progress", pct)
                                                    put("status", "RUNNING")
                                                    put("line", "[download] تم تحميل $mbDone MB ($pct%)...")
                                                }
                                            )
                                        }
                                    }
                                }
                                conn.disconnect()

                                if (chunkRead < chunkSize || chunkRead == 0L) {
                                    break
                                }
                                startByte += chunkRead
                            }
                        }
                    } else {
                        // Direct HTTP stream download (TikTok, X/Twitter, Instagram, Facebook, Vimeo, Dailymotion, Direct)
                        val cleanStreamUrl = decodeJsonEscapedUrl(resolved.streamUrl)
                        val conn = (URL(cleanStreamUrl).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 10000
                            readTimeout = 15000
                            setRequestProperty("User-Agent", resolved.userAgent)
                            setRequestProperty("Accept", "*/*")
                        }
                        if (conn.responseCode !in 200..299) {
                            val code = conn.responseCode
                            conn.disconnect()
                            throw IllegalStateException("فشل الاتصال بخادم الوسائط (HTTP $code)")
                        }

                        val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: (8L * 1024L * 1024L)
                        val buf = ByteArray(16384)
                        conn.inputStream.use { input ->
                            FileOutputStream(destFile).use { output ->
                                while (true) {
                                    if (canceledTasks[taskId] == true) {
                                        conn.disconnect()
                                        destFile.delete()
                                        return@Thread
                                    }
                                    val r = input.read(buf)
                                    if (r == -1) break
                                    output.write(buf, 0, r)
                                    bytesWrittenTotal += r

                                    val now = System.currentTimeMillis()
                                    if (now - lastEmitTime >= 160L) {
                                        lastEmitTime = now
                                        val pct = (25 + ((bytesWrittenTotal.toDouble() / totalBytes.toDouble()) * 68.0).toInt()).coerceIn(26, 94)
                                        val mbDone = String.format("%.2f", bytesWrittenTotal.toDouble() / (1024.0 * 1024.0))
                                        emitTaskUpdate(
                                            JSONObject().apply {
                                                put("taskId", taskId)
                                                put("progress", pct)
                                                put("status", "RUNNING")
                                                put("line", "[download] تم تحميل $mbDone MB ($pct%)...")
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        conn.disconnect()
                    }

                    if (!destFile.exists() || destFile.length() < 256L) {
                        destFile.delete()
                        throw IllegalStateException("الملف المحمل فارغ أو غير مكتمل")
                    }

                    // Export copy to public Downloads/Owl
                    val publicSavedPath = exportToPublicDownloads(destFile, fileName)

                    val insertedId = db.insertDownloadedVideo(
                        DownloadedVideoInfo(
                            id = 0,
                            videoTitle = resolved.title,
                            videoAuthor = resolved.author,
                            videoUrl = targetUrl,
                            thumbnailUrl = resolved.thumbnailUrl,
                            videoPath = destFile.absolutePath,
                            extractor = resolved.extractor
                        )
                    )

                    val fileUri = Uri.fromFile(destFile).toString()
                    val isImg = isImageFileName(destFile.name)
                    val sizeLabel = if (destFile.length() < 1024L * 1024L) {
                        String.format(java.util.Locale.ENGLISH, "%d KB", (destFile.length() / 1024L).coerceAtLeast(1L))
                    } else {
                        String.format(java.util.Locale.ENGLISH, "%.2f MB", destFile.length().toDouble() / (1024.0 * 1024.0))
                    }

                    emitTaskUpdate(
                        JSONObject().apply {
                            put("taskId", taskId)
                            put("progress", 100)
                            put("status", "COMPLETED")
                            put("videoId", insertedId)
                            put("videoTitle", resolved.title)
                            put("videoAuthor", resolved.author)
                            put("thumbnailUrl", if (isImg) fileUri else resolved.thumbnailUrl)
                            put("videoPath", destFile.absolutePath)
                            put("publicPath", publicSavedPath)
                            put("fileUrl", fileUri)
                            put("fileSize", sizeLabel)
                            put("isImage", isImg)
                            put("line", "[اكتمل] تم حفظ الملف ($sizeLabel) في $publicSavedPath")
                            put("stateJson", getInitialStateJson())
                        }
                    )
                } catch (e: Exception) {
                    emitTaskUpdate(
                        JSONObject().apply {
                            put("taskId", taskId)
                            put("progress", 100)
                            put("status", "ERROR")
                            put("line", "[خطأ] ${e.message ?: "تعذر تحميل هذا الرابط"}")
                        }
                    )
                }
            }.start()
        }

        @JavascriptInterface
        fun cancelNativeDownload(taskId: String) {
            canceledTasks[taskId] = true
        }

        @JavascriptInterface
        fun shareVideoFile(id: Int) {
            mainHandler.post {
                val video = db.getVideoById(id)
                if (video == null) {
                    Toast.makeText(this@MainActivity, "لم يتم العثور على الملف", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val file = File(video.videoPath)
                if (!file.exists()) {
                    Toast.makeText(this@MainActivity, "الملف غير موجود على الذاكرة", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val contentUri = OwlFileProvider.getUriForFile(file)
                val isImg = isImageFileName(file.name)
                val shareMime = if (isImg) "image/*" else "video/mp4"
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = shareMime
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_SUBJECT, video.videoTitle)
                    putExtra(Intent.EXTRA_TEXT, "${video.videoTitle}\n${video.videoUrl}")
                    clipData = ClipData.newRawUri(video.videoTitle, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, if (isImg) "مشاركة الصورة عبر" else "مشاركة الفيديو عبر").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(chooser)
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, "تعذر فتح المشاركة: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun saveVideoToDownloads(id: Int): String {
            val video = db.getVideoById(id) ?: return ""
            val file = File(video.videoPath)
            if (!file.exists()) return ""
            val savedPath = exportToPublicDownloads(file, file.name)
            val isImg = isImageFileName(file.name)
            mainHandler.post {
                Toast.makeText(
                    this@MainActivity,
                    if (isImg) "✅ تم حفظ الصورة في الاستوديو والتنزيلات: Download/Owl/${file.name}"
                    else "✅ تم حفظ الفيديو في الاستوديو والتنزيلات: Download/Owl/${file.name}",
                    Toast.LENGTH_LONG
                ).show()
            }
            return savedPath
        }

        @JavascriptInterface
        fun openExternalPlayer(id: Int) {
            mainHandler.post {
                val video = db.getVideoById(id) ?: return@post
                val file = File(video.videoPath)
                if (!file.exists()) return@post
                val contentUri = OwlFileProvider.getUriForFile(file)
                val isImg = isImageFileName(file.name)
                val viewMime = if (isImg) "image/*" else "video/mp4"
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, viewMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(Intent.createChooser(viewIntent, if (isImg) "عرض الصورة باستخدام" else "تشغيل الفيديو باستخدام"))
                }.onFailure {
                    Toast.makeText(this@MainActivity, "لا يوجد عارض خارجي، يمكنك العرض داخل التطبيق", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun saveTemplate(id: Int, name: String, template: String): String {
            if (id <= 0) {
                db.insertTemplate(name.trim(), template.trim())
            } else {
                db.updateTemplate(id, name.trim(), template.trim())
            }
            return getInitialStateJson()
        }

        @JavascriptInterface
        fun deleteTemplate(id: Int): String {
            db.deleteTemplate(id)
            return getInitialStateJson()
        }

        @JavascriptInterface
        fun addShortcut(option: String): String {
            if (option.isNotBlank()) {
                db.insertShortcut(option.trim())
            }
            return getInitialStateJson()
        }

        @JavascriptInterface
        fun deleteShortcut(id: Long): String {
            db.deleteShortcut(id)
            return getInitialStateJson()
        }

        @JavascriptInterface
        fun addCookieProfile(url: String, content: String): String {
            db.insertCookie(url.trim(), content.trim())
            return getInitialStateJson()
        }

        @JavascriptInterface
        fun extractWebViewCookies(targetUrl: String): String {
            val rawCookies = runCatching {
                CookieManager.getInstance().getCookie(targetUrl)
            }.getOrNull()

            val domain = runCatching {
                URI(targetUrl).host?.removePrefix("www.") ?: "youtube.com"
            }.getOrDefault("youtube.com")

            val expiry = (System.currentTimeMillis() / 1000L) + 86400L * 180L
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            sb.append("# Extracted by Owl Android CookieManager for $targetUrl\n")

            if (!rawCookies.isNullOrBlank()) {
                rawCookies.split(";").forEach { pair ->
                    val kv = pair.trim().split("=", limit = 2)
                    if (kv.size == 2) {
                        sb.append(".$domain\tTRUE\t/\tTRUE\t$expiry\t${kv[0]}\t${kv[1]}\n")
                    }
                }
            } else {
                sb.append(".$domain\tTRUE\t/\tTRUE\t$expiry\tSID\towl_android_session_${System.currentTimeMillis() % 100000}\n")
                sb.append(".$domain\tTRUE\t/\tTRUE\t$expiry\tPREF\tf6=40000000&hl=ar\n")
            }

            db.insertCookie(targetUrl.trim(), sb.toString().trim())
            return getInitialStateJson()
        }

        @JavascriptInterface
        fun deleteCookieProfile(id: Int): String {
            db.deleteCookie(id)
            return getInitialStateJson()
        }

        @JavascriptInterface
        fun deleteVideoHistory(id: Int): String {
            val video = db.getVideoById(id)
            if (video != null) {
                runCatching { File(video.videoPath).delete() }
            }
            db.deleteDownloadedVideo(id)
            return getInitialStateJson()
        }

        @JavascriptInterface
        fun readClipboardText(): String {
            var text = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val item = cm?.primaryClip?.getItemAt(0)
                    text = item?.text?.toString() ?: ""
                }
                latch.countDown()
            }
            runCatching { latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            return text
        }

        @JavascriptInterface
        fun copyToClipboard(label: String, content: String) {
            mainHandler.post {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(label, content))
                Toast.makeText(this@MainActivity, "تم النسخ إلى الحافظة", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            mainHandler.post {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                }
            }
        }
    }
}
