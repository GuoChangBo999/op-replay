package ai.openpilot.replay

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.WebView
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * OP Replay - openpilot dashcam + vehicle signal viewer.
 *
 * Three ways to get data:
 *   1) 本地文件  : pick fcamera.mp4/hevc + qlog.zst manually
 *   2) 设备直连  : input IP:port (e.g. 10.90.179.99:5088), browse the device's
 *                  "行车记录查看下载" page, download the 5 route files
 *                  (ecamera / fcamera / qcamera / qlog / rlog) into a per-route
 *                  folder, then auto-parse and play.
 *   3) (fallback) if the page exposes plain links we still parse them.
 */
class MainActivity : AppCompatActivity() {

    // ---- views
    private lateinit var status: TextView
    private lateinit var btnVideo: Button
    private lateinit var btnLog: Button
    private lateinit var btnPlay: Button
    private lateinit var btnExt: Button
    private lateinit var web: WebView
    private lateinit var video: android.widget.VideoView
    private lateinit var camRow: android.widget.LinearLayout
    private lateinit var btnCamF: Button
    private lateinit var btnCamE: Button
    private lateinit var etHost: EditText
    private lateinit var etKey: EditText
    private lateinit var btnConnect: Button
    private lateinit var remoteList: LinearLayout
    private lateinit var pageNav: LinearLayout
    private lateinit var tvPage: TextView

    // ---- state
    private var videoUri: Uri? = null
    private var logUri: Uri? = null
    private var wideUri: Uri? = null        // optional 广角 (ecamera) for the cam switch

    private var baseHost: String? = null          // e.g. http://10.90.179.99:5088
    private var currentDir = "/data/media/0/realdata"  // for SSH fallback (unused now)
    private var lastTranscodeError: String? = null

    // ---- 方案A：原生 VideoView 播放 + WebView 图表
    private var frontPath: String? = null   // 已复制到 cache 的前摄文件绝对路径
    private var widePath: String? = null    // 已复制到 cache 的广角文件绝对路径
    private var curCam = "front"            // front | wide
    private var syncRunning = false
    private val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---- local-file pickers
    // NOTE: we pick with "*/*" on purpose. HEVC raw streams (fcamera.hevc / ecamera.hevc)
    // have no MIME type registered on many devices, so a "video/*" filter would grey them out.
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            videoUri = uri
            val nm = displayName(uri)
            status.text = "视频已选:$nm"
            // auto-pair: look for the matching ecamera (wide) next to this file
            autoPairWide(uri)
            refreshPlayBtn()
        }
    }
    private val pickLog = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            logUri = uri
            val nm = displayName(uri)
            status.text = "日志已选:$nm"
            // auto-pair BOTH cameras from the same route id (name prefix)
            autoPairFrom(uri)
            refreshPlayBtn()
        }
    }

    /**
     * Given a picked file (qlog.zst / fcamera.hevc / any route file), work out its
     * route id (the leading "yyyy-MM-dd-HH-mm-ss") and try to locate the sibling
     * fcamera.hevc (front) and ecamera.hevc (wide) in the same folder.
     *
     * openpilot route dirs look like:
     *   /realdata/2026-10-02-20-24-08--0/
     *       fcamera.hevc  ecamera.hevc  qcamera.ts  qlog.zst  rlog.zst
     * but the downloaded (flattened) names are often:
     *   2026-10-02-20-24-08-fcamera.hevc
     *   2026-10-02-20-24-08-ecamera.hevc
     *   2026-10-02-20-24-08-qlog.zst
     * so we handle both shapes.
     */
    private fun autoPairFrom(uri: Uri) {
        val nm = displayName(uri)
        val route = routeIdOf(nm) ?: return
        // 1) same SAF folder (works for content:// URIs picked from DocumentsUI)
        findSibling(uri, route, "fcamera")?.let { if (videoUri == null) videoUri = it }
        findSibling(uri, route, "ecamera")?.let { wideUri = it }
        // 2) plain filesystem scan (works for file:// URIs)
        val p = uri.path
        if ((videoUri == null || wideUri == null) && p != null && p.startsWith("/")) {
            val parent = File(p).parentFile
            if (parent != null && parent.isDirectory) {
                val files = parent.listFiles() ?: emptyArray()
                if (videoUri == null)
                    files.firstOrNull { it.name.contains(route) && it.name.contains("fcamera") }
                        ?.let { videoUri = Uri.fromFile(it) }
                if (wideUri == null)
                    files.firstOrNull { it.name.contains(route) && it.name.contains("ecamera") }
                        ?.let { wideUri = Uri.fromFile(it) }
                if (videoUri == null)
                    files.firstOrNull { it.name.contains("fcamera") }?.let { videoUri = Uri.fromFile(it) }
                if (wideUri == null)
                    files.firstOrNull { it.name.contains("ecamera") }?.let { wideUri = Uri.fromFile(it) }
            }
        }
        val v = videoUri?.let { displayName(it) } ?: "—"
        val w = wideUri?.let { displayName(it) } ?: "—"
        status.text = "已配对  前摄:$v   广角:$w"
    }

    private fun autoPairWide(videoOrAny: Uri) {
        val nm = displayName(videoOrAny)
        val route = routeIdOf(nm) ?: return
        findSibling(videoOrAny, route, "ecamera")?.let { wideUri = it }
        val p = videoOrAny.path
        if (wideUri == null && p != null && p.startsWith("/")) {
            File(p).parentFile?.takeIf { it.isDirectory }?.listFiles()
                ?.firstOrNull { it.name.contains(route) && it.name.contains("ecamera") }
                ?.let { wideUri = Uri.fromFile(it) }
        }
    }

    /** extract "2026-10-02-20-24-08" from any filename/uri containing it. */
    private fun routeIdOf(name: String): String? {
        val m = Regex("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})").find(name)
        return m?.groupValues?.get(1)
    }

    /** List the picked document's parent folder (via DocumentsContract) and return the sibling
     *  whose name contains both [route] and [cam]. Returns null when not resolvable. */
    private fun findSibling(from: Uri, route: String, cam: String): Uri? {
        return try {
            val treeId = android.provider.DocumentsContract.getTreeDocumentId(from)
            val parentUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(from, treeId)
            contentResolver.query(parentUri, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue
                    if (name.contains(route) && name.contains(cam))
                        return android.provider.DocumentsContract.buildDocumentUriUsingTree(from, id)
                }
            }
            null
        } catch (_: Exception) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        btnVideo = findViewById(R.id.btnVideo)
        btnLog = findViewById(R.id.btnLog)
        btnPlay = findViewById(R.id.btnPlay)
        btnExt = findViewById(R.id.btnExt)
        web = findViewById(R.id.web)
        video = findViewById(R.id.video)
        camRow = findViewById(R.id.camRow)
        btnCamF = findViewById(R.id.btnCamF)
        btnCamE = findViewById(R.id.btnCamE)
        etHost = findViewById(R.id.etHost)
        etKey = findViewById(R.id.etKey)
        btnConnect = findViewById(R.id.btnConnect)
        remoteList = findViewById(R.id.remoteList)
        pageNav = findViewById(R.id.pageNav)
        tvPage = findViewById(R.id.tvPage)

        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.domStorageEnabled = true
        // 模板点图表时回调原生 -> VideoView seek
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onSeek(sec: String) {
                val t = sec.toDoubleOrNull() ?: return
                runOnUiThread {
                    try {
                        video.seekTo((t * 1000).toInt())
                        if (!video.isPlaying) video.start()
                    } catch (_: Exception) {}
                }
            }
        }, "OPJS")
        // VideoView 播放时，定时把当前时间推给图表
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            video.seekTo(0)
            video.start()
            startSync()
        }
        video.setOnCompletionListener { stopSync() }
        btnCamF.setOnClickListener { switchCam("front") }
        btnCamE.setOnClickListener { switchCam("wide") }

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))

        // 密钥框在新方案里用不到（设备页面走 HTTP），隐藏掉避免困惑
        etKey.visibility = View.GONE

        btnVideo.setOnClickListener { pickVideo.launch(arrayOf("*/*")) }
        btnLog.setOnClickListener { pickLog.launch(arrayOf("*/*")) }
        btnPlay.setOnClickListener { runReplay() }
        btnExt.setOnClickListener { openExternally() }
        btnConnect.setOnClickListener { connect() }

        status.text = "输入设备 IP:端口（如 10.90.179.99:5088）连接，或选本地文件。"
    }

    // =====================================================================
    //  connect
    // =====================================================================

    private fun connect() {
        val host = etHost.text.toString().trim()
        if (host.isEmpty()) { toast("请输入 IP:端口"); return }
        var h = host
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://$h"
        baseHost = h
        loadDevicePage(h)
    }

    // =====================================================================
    //  device page (:5088 "行车记录查看下载")
    // =====================================================================

    private fun loadDevicePage(base: String) {
        status.text = "连接 $base …"
        Thread {
            try {
                val html = httpGet("$base/")
                runOnUiThread {
                    status.text = "已连接设备"
                    remoteList.removeAllViews()
                    val pvRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val btnOpen = Button(this).apply { text = "在浏览器中打开原页面" }
                    btnOpen.setOnClickListener {
                        web.loadDataWithBaseURL("$base/", html, "text/html", "utf-8", null)
                    }
                    pvRow.addView(btnOpen)
                    remoteList.addView(pvRow)
                    renderRoutes(base, html)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "连接失败:${e.message}（确认设备开机且与手机同网）" }
            }
        }.start()
    }

    /**
     * Parse the device page and build our own list of "route cards".
     * Each card = one recording段, with 5 download buttons:
     *   广角HEVC(ecamera) / 前摄HEVC(fcamera) / 标清(qcamera) / qlog / rlog
     *
     * The page structure is unknown to us exactly, so we do a *robust* job:
     *   - find all <a href="...">  and all onclick="...download..." patterns
     *   - group links by route id (the yyyy-mm-dd-hh-mm-ss prefix in the url/name)
     *   - for each route, map each link to a camera by keyword
     */
    private fun renderRoutes(base: String, html: String) {
        val routes = DevicePageParser.parse(base, html)
        if (routes.isEmpty()) {
            val tv = TextView(this).apply {
                text = "页面已获取，但未识别到 route 下载链接。\n点上面的按钮可查看原始页面。"
            }
            remoteList.addView(tv)
            return
        }
        status.text = "共 ${routes.size} 段"
        for (r in routes) remoteList.addView(buildRouteCard(r))
    }

    private fun buildRouteCard(r: RouteEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val title = TextView(this).apply {
            text = "${r.route}   ${r.sizeText}".trim()
            textSize = 15f
        }
        card.addView(title)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun mk(label: String, url: String?, col: String) {
            val b = Button(this).apply {
                text = label
                isEnabled = url != null
                setOnClickListener { url?.let { startRouteDownload(r, col, it) } }
            }
            card.addView(b)
        }
        // two rows for the 5 buttons
        val btnAll = Button(this).apply {
            text = "⬇ 下载全部 5 个文件"
            setOnClickListener { downloadAll(r) }
        }
        card.addView(btnAll)
        mk("广角HEVC", r.ecamera, "ecamera")
        mk("前摄HEVC", r.fcamera, "fcamera")
        mk("标清", r.qcamera, "qcamera")
        mk("qlog", r.qlog, "qlog")
        mk("rlog", r.rlog, "rlog")
        card.addView(TextView(this).apply { text = " " })
        return card
    }

    // =====================================================================
    //  downloads
    // =====================================================================

    private fun downloadAll(r: RouteEntry) {
        val items = listOfNotNull(
            r.ecamera?.let { "ecamera" to it },
            r.fcamera?.let { "fcamera" to it },
            r.qcamera?.let { "qcamera" to it },
            r.qlog?.let { "qlog" to it },
            r.rlog?.let { "rlog" to it },
        )
        Thread {
            val dir = File(getExternalFilesDir(null), "routes/${r.route}")
            dir.mkdirs()
            var done = 0
            for ((col, url) in items) {
                try {
                    runOnUiThread { status.text = "下载 ${r.route} / $col ($done/${items.size})" }
                    val ext = if (col == "qlog" || col == "rlog") ".zst"
                              else if (col == "qcamera") ".ts" else ".hevc"
                    val out = File(dir, "$col$ext")
                    download(url, out)
                    done++
                    runOnUiThread { status.text = "已下载 $col (${out.length()/1024/1024} MB)" }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "下载 $col 失败:${e.message}" }
                }
            }
            runOnUiThread {
                status.text = "全部下载完成，自动解析…"
                val fc = File(dir, "fcamera.hevc")
                val ql = File(dir, "qlog.zst")
                val ec = File(dir, "ecamera.hevc")
                if (fc.exists()) videoUri = Uri.fromFile(fc)
                if (ec.exists()) wideUri = Uri.fromFile(ec)
                if (ql.exists()) logUri = Uri.fromFile(ql)
                refreshPlayBtn()
                if (videoUri != null && logUri != null) runReplay()
            }
        }.start()
    }

    private fun startRouteDownload(r: RouteEntry, col: String, url: String) {
        Thread {
            try {
                val dir = File(getExternalFilesDir(null), "routes/${r.route}")
                dir.mkdirs()
                val ext = if (col == "qlog" || col == "rlog") ".zst"
                          else if (col == "qcamera") ".ts" else ".hevc"
                val out = File(dir, "$col$ext")
                runOnUiThread { status.text = "下载 $col …" }
                download(url, out)
                runOnUiThread {
                    status.text = "已下载:${out.name} (${out.length()/1024/1024} MB) → ${dir.path}"
                    if (col == "fcamera") { videoUri = Uri.fromFile(out); refreshPlayBtn() }
                    if (col == "ecamera") { wideUri = Uri.fromFile(out); refreshPlayBtn() }
                    if (col == "qlog") { logUri = Uri.fromFile(out); refreshPlayBtn() }
                    if (videoUri != null && logUri != null) runReplay()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "下载失败:${e.message}" }
            }
        }.start()
    }

    private fun download(urlStr: String, out: File) {
        val u = if (urlStr.startsWith("http")) urlStr else (baseHost ?: "") + urlStr
        val c = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 120000
        }
        c.inputStream.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
    }

    private fun httpGet(urlStr: String): String {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 20000
        }
        return c.inputStream.bufferedReader().readText()
    }

    // =====================================================================
    //  playback + parsing
    // =====================================================================

    private fun refreshPlayBtn() {
        val ok = videoUri != null && logUri != null
        btnPlay.isEnabled = ok
        btnExt.isEnabled = videoUri != null
    }

    /** Open the raw video with an external player (MX Player etc.) — these all handle HEVC raw. */
    private fun openExternally() {
        val v = videoUri ?: return
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(v, "video/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "选择播放器"))
        } catch (e: Exception) {
            toast("没有可用的播放器: ${e.message}")
        }
    }

    private fun runReplay() {
        val vUri = videoUri ?: return
        val lUri = logUri ?: return
        btnPlay.isEnabled = false
        status.text = "处理中:复制视频…"
        Thread {
            try {
                // 复制原始 HEVC 裸流到 cache
                val vfRaw = copyToCache(vUri, "front.hevc")
                val efRaw = wideUri?.let { copyToCache(it, "wide.hevc") }

                // HEVC 无法在 WebView 里播，转成 H.264 mp4（纯 Java，ByteBuffer 通路，无 EGL）
                runOnUiThread { status.text = "处理中:转码前摄(HEVC→H.264)…" }
                val vf = ensurePlayable(vfRaw, "front.mp4") { p ->
                    runOnUiThread { status.text = "转码前摄… $p%" }
                }
                val ef = efRaw?.let {
                    ensurePlayable(it, "wide.mp4") { p -> runOnUiThread { status.text = "转码广角… $p%" } }
                }

                runOnUiThread { status.text = "处理中:解析日志…" }
                val lf = copyToCache(lUri, "qlog.zst")
                val mod = Python.getInstance().getModule("op_parser")
                val json = mod.callAttr("parse_route", lf.absolutePath, "").toString()
                runOnUiThread { status.text = "处理中:生成播放页…" }
                val html = buildHtml(json, vf, ef)
                runOnUiThread {
                    // WebView 内嵌播放转好的 mp4 + 图表同屏联动
                    web.loadDataWithBaseURL("file://${vf.parentFile!!.absolutePath}/", html, "text/html", "utf-8", null)
                    val m = Regex("\"samples\":\\s*(\\d+)").find(json)
                    status.text = "就绪(${m?.groupValues?.get(1) ?: "?"} 采样点)"
                    // 隐藏原生 VideoView（改用 WebView 内嵌播放）
                    video.visibility = View.GONE
                    camRow.visibility = View.GONE
                    btnPlay.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "失败:${e.message}"; btnPlay.isEnabled = true }
            }
        }.start()
    }

    /** 原生 VideoView 播放指定摄像头文件 */
    private fun playCam(cam: String) {
        val path = if (cam == "wide") widePath else frontPath
        if (path == null) return
        curCam = cam
        try {
            video.setVideoPath(path)
            video.requestFocus()
        } catch (e: Exception) {
            status.text = "播放失败:${e.message}"
        }
        web.evaluateJavascript("window.opReplay && window.opReplay.setCam('${if (cam=="wide") "广角" else "前摄长焦"}')", null)
        btnCamF?.let { it.alpha = if (cam == "front") 1f else 0.5f }
        btnCamE?.let { it.alpha = if (cam == "wide") 1f else 0.5f }
    }

    /** 切换前摄/广角，保持当前时间 */
    private fun switchCam(cam: String) {
        if (cam == curCam) return
        val t = try { video.currentPosition } catch (_: Exception) { 0 }
        playCam(cam)
        // 切换后恢复到同一时间点
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            try { video.seekTo(t) } catch (_: Exception) {}
            video.start()
            startSync()
        }
    }

    /** 每 ~100ms 把 VideoView 当前时间推给 WebView 图表 */
    private fun startSync() {
        if (syncRunning) return
        syncRunning = true
        val tick = object : Runnable {
            override fun run() {
                if (!syncRunning) return
                try {
                    val t = video.currentPosition / 1000.0
                    web.evaluateJavascript("window.opReplay && window.opReplay.setTime($t)", null)
                } catch (_: Exception) {}
                syncHandler.postDelayed(this, 100)
            }
        }
        syncHandler.postDelayed(tick, 100)
    }

    private fun stopSync() {
        syncRunning = false
        syncHandler.removeCallbacksAndMessages(null)
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val f = File(cacheDir, name)
        contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(f).use { out -> input!!.copyTo(out) }
        }
        return f
    }

    /**
     * Make [src] playable by a WebView <video>.
     *
     *  - If the file already has an ISO-BMFF (mp4) header we keep it as-is.
     *  - Otherwise (openpilot HEVC raw Annex-B: fcamera.hevc / ecamera.hevc) we do a REAL
     *    hardware transcode: HEVC decoder -> H.264 encoder -> mp4. This is what makes the
     *    video play inside the WebView while the signal charts / blinkers / wheel stay visible
     *    underneath (that's the whole point of this app).
     *
     *  Falls back to returning [src] if anything goes wrong.
     */
    private fun ensurePlayable(src: File, outName: String? = null, onProgress: ((Int) -> Unit)? = null): File {
        // already a container?
        try {
            FileInputStream(src).use { ins ->
                val head = ByteArray(16); val n = ins.read(head)
                if (n >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") return src
            }
        } catch (_: Exception) { }

        val name = outName ?: if (src.name.contains("wide")) "video_wide.mp4" else "video.mp4"
        val out = File(cacheDir, name)
        if (out.exists()) out.delete()
        try {
            // Pure-Java ByteBuffer transcode (no EGL): HEVC decode -> YUV copy -> H.264 encode.
            val ok = HevcTranscoder.transcode(src, out) { p -> onProgress?.invoke(p) }
            return if (ok && out.length() > 0) out else src
        } catch (e: Exception) {
            lastTranscodeError = "${e.javaClass.simpleName}: ${e.message}"
            return src
        }
    }

    private fun headHex(f: File): String = try {
        FileInputStream(f).use { ins ->
            val b = ByteArray(16); val n = ins.read(b)
            b.copyOf(n).joinToString(" ") { "%02x".format(it) }
        }
    } catch (e: Exception) { "ERR:${e.message}" }

    private fun buildHtml(signalsJson: String, videoFile: File, wideFile: File? = null): String {
        val chartJs = assets.open("chart.umd.min.js").bufferedReader().readText()
        val tpl = assets.open("replay_template.html").bufferedReader().readText()
        val wideName = wideFile?.name ?: "__CAM_E__"
        return tpl.replace("__CHARTJS__", chartJs)
            .replace("__SERIES_JSON__", signalsJson)
            .replace("__CAM_E__", wideName)
            .replace("__VIDEO_FILE__", videoFile.name)
    }

    private fun displayName(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

// =========================================================================
//  Device page parser
// =========================================================================

data class RouteEntry(
    val route: String,
    val sizeText: String,
    val ecamera: String?,
    val fcamera: String?,
    val qcamera: String?,
    val qlog: String?,
    val rlog: String?,
)

object DevicePageParser {

    // route id: 2026-10-02-10-05-06  (yyyy-mm-dd-HH-MM-SS)
    private val ROUTE_RE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})")
    // href="..."  or  onclick="...'...'" / onclick="...(‘...’)"
    private val HREF_RE = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val ONCLICK_RE = Pattern.compile("onclick\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    // capture any url-ish token inside a string
    private val URLLIKE_RE = Pattern.compile("(https?://[^\\s\"')]+|/[\\w./%?=&-]+)")

    fun parse(base: String, html: String): List<RouteEntry> {
        // 1) collect all candidate links (href + urls found inside onclick)
        val links = LinkedHashSet<String>()
        HREF_RE.matcher(html).let { m -> while (m.find()) links.add(m.group(1)!!) }
        ONCLICK_RE.matcher(html).let { m ->
            while (m.find()) {
                val js = m.group(1)!!
                val um = URLLIKE_RE.matcher(js)
                while (um.find()) links.add(um.group(1)!!)
            }
        }
        // also scan raw html for hevc/zst/ts links that may appear anywhere
        URLLIKE_RE.matcher(html).let { um ->
            while (um.find()) {
                val u = um.group(1)!!
                if (u.endsWith(".hevc") || u.endsWith(".zst") || u.endsWith(".ts") ||
                    u.contains("download") || u.contains("ecamera") || u.contains("fcamera") ||
                    u.contains("qlog") || u.contains("rlog") || u.contains("camera"))
                    links.add(u)
            }
        }

        // 2) group by route id
        val byRoute = LinkedHashMap<String, MutableMap<String, String>>()
        for (u in links) {
            val rm = ROUTE_RE.matcher(u)
            val route = if (rm.find()) rm.group(1)!! else continue
            val lower = u.lowercase()
            val slot = when {
                lower.contains("ecamera") || lower.contains("广角") || lower.contains("wide") -> "ecamera"
                lower.contains("fcamera") || lower.contains("前摄") || lower.contains("front") -> "fcamera"
                lower.contains("qcamera") || lower.contains("标清") || lower.contains("qcamera") -> "qcamera"
                lower.contains("rlog") -> "rlog"
                lower.contains("qlog") -> "qlog"
                else -> null
            }
            if (slot != null) byRoute.getOrPut(route) { mutableMapOf() }[slot] = u
        }

        return byRoute.entries.sortedByDescending { it.key }.map { (route, m) ->
            RouteEntry(
                route = route,
                sizeText = "",
                ecamera = m["ecamera"],
                fcamera = m["fcamera"],
                qcamera = m["qcamera"],
                qlog = m["qlog"],
                rlog = m["rlog"],
            )
        }
    }
}

