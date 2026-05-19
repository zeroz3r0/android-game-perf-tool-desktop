package com.gameperf.desktop.core.sharing

import com.gameperf.desktop.core.AppVersion
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Uploads an HTML report to **temp.sh** via a multipart/form-data POST and
 * returns the public URL the recipient opens in a browser.
 *
 * ## Why temp.sh?
 *
 * Validated 2026-05-19 against the obvious free-and-anonymous alternatives:
 *
 * | Service       | HTML rendered? | Status (2026-05) | Retention   | Verdict |
 * |---------------|----------------|------------------|-------------|---------|
 * | **temp.sh**   | ✅ text/html   | ✅ Active        | 3 days      | Winner  |
 * | 0x0.st        | n/a            | ❌ Uploads off   | 30d-1y      | Down    |
 * | transfer.sh   | n/a            | ❌ Transport err | 14d         | Dead    |
 * | catbox.moe    | ❌ text/plain  | ✅ Active        | Forever     | Serves HTML as text/plain — useless for reports |
 * | gofile.io     | ❌ Own UI page | ✅ Active        | 10d inactive | URL goes to file manager, not direct render |
 *
 * temp.sh is the only one that (a) is currently accepting uploads, (b)
 * serves `*.html` with `Content-Type: text/html; charset=utf-8` so the
 * recipient sees the rendered report on link click, and (c) does not
 * require auth, keys, or OAuth.
 *
 * ## Constraints
 *
 * - **No external libraries.** Multipart body is hand-built with
 *   [HttpURLConnection] + [DataOutputStream] — same approach as
 *   [com.gameperf.desktop.core.Downloader]. Keeps the JAR small and removes
 *   one runtime dependency from the supply chain.
 * - **Strict size cap.** Default 50 MB upload limit ([MAX_UPLOAD_BYTES]).
 *   Reports are ~0.5 MB so this is generous; the cap exists to fail loudly
 *   if someone tries to upload a `.gameperf` ZIP by accident.
 * - **Identifiable User-Agent.** Per temp.sh / 0x0.st convention for public
 *   file hosters: use a UA that names the program + version so the
 *   operator can troubleshoot or block abusive clients without nuking
 *   legitimate ones.
 *
 * ## Privacy disclaimer (caller responsibility)
 *
 * The caller MUST surface a one-time disclaimer before invoking this — the
 * file lands on a public file hoster operated by a third party. Anyone with
 * the URL can read the report. There is no per-upload auth.
 *
 * ## Pure-ish design
 *
 * The pure part is [buildMultipartBody]: takes a file, returns the raw
 * bytes ready to POST. Tested without network. The HTTP side effects live
 * in [upload] and are exercised by a JUnit test that spins up an
 * [com.sun.net.httpserver.HttpServer] fake in-process (same pattern as
 * `IosBridgeTest` per CHANGELOG v4.0.0).
 *
 * @since v4.7.1
 */
internal object TempShUploader {

    /** Endpoint per temp.sh frontpage: `curl -F "file=@x" https://temp.sh/upload`. */
    private const val DEFAULT_ENDPOINT = "https://temp.sh/upload"

    /** Hard cap to refuse oversized files before opening a socket. */
    const val MAX_UPLOAD_BYTES: Long = 50L * 1024L * 1024L

    /** Connect / read timeouts mirror [Downloader] for consistency. */
    private const val CONNECT_TIMEOUT_MS: Int = 15_000
    private const val READ_TIMEOUT_MS: Int = 60_000

    /**
     * Multipart boundary token. Constant per process — temp.sh does not care
     * about uniqueness across requests as long as the boundary does not
     * appear inside the body, which it won't for HTML/JSON/binary content.
     */
    private const val BOUNDARY = "----GamePerfFormBoundary7d4a8b2c1f5e9d3a"

    /** Last upload error message, kept for debugging (same pattern as [Downloader.lastDownloadError]). */
    @Volatile
    var lastUploadError: String? = null
        private set

    /**
     * Upload [file] to [endpoint] (defaults to temp.sh production) and
     * return a [ReportShareResult] ready to surface to the user.
     *
     * @param file File to upload. Must exist, must be ≤ [MAX_UPLOAD_BYTES].
     * @param endpoint Override the default endpoint. Provided for tests
     *   (in-process fake server). Production callers omit it.
     * @param mimeType MIME type to declare for the upload. Defaults to
     *   `text/html` so temp.sh serves the response with the matching
     *   `Content-Type` header. Override for non-HTML uploads if ever
     *   reused outside this feature.
     */
    fun upload(
        file: File,
        endpoint: String = DEFAULT_ENDPOINT,
        mimeType: String = "text/html",
    ): ReportShareResult {
        lastUploadError = null

        if (!file.exists() || !file.isFile) {
            lastUploadError = "file not found: ${file.absolutePath}"
            return ReportShareResult.Failure(
                ReportShareResult.FailureReason.REPORT_NOT_FOUND,
                "No se encontró el informe HTML. Probablemente fue movido o eliminado.",
            )
        }

        if (file.length() > MAX_UPLOAD_BYTES) {
            lastUploadError = "file too large: ${file.length()} bytes (max $MAX_UPLOAD_BYTES)"
            val sizeMb = file.length() / 1024.0 / 1024.0
            return ReportShareResult.Failure(
                ReportShareResult.FailureReason.UPLOAD_FILE_TOO_LARGE,
                "El archivo pesa %.1f MB, demasiado para el servicio de enlace temporal. ".format(sizeMb) +
                    "Usa el botón de compartir local para enviarlo a mano.",
            )
        }

        val body = try {
            buildMultipartBody(file, mimeType)
        } catch (e: IOException) {
            lastUploadError = "build body: ${e.message}"
            return ReportShareResult.Failure(
                ReportShareResult.FailureReason.UPLOAD_NETWORK_ERROR,
                "No se pudo leer el archivo del informe para subirlo: ${e.message ?: "sin detalle"}.",
            )
        }

        val conn = try {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                doOutput = true
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "GamePerfDesktop/${AppVersion.NAME} (+https://github.com/vivagames/android-game-perf-tool-desktop)",
                )
                setRequestProperty("Accept", "text/plain, */*")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                setFixedLengthStreamingMode(body.size)
            }
        } catch (e: IOException) {
            lastUploadError = "open conn: ${e.message}"
            return ReportShareResult.Failure(
                ReportShareResult.FailureReason.UPLOAD_NETWORK_ERROR,
                "No se pudo conectar con el servicio de enlace temporal (temp.sh). " +
                    "Revisa tu conexión o usa el botón de compartir local.",
            )
        }

        return try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = readError(conn)
                lastUploadError = "HTTP $code: $err"
                return ReportShareResult.Failure(
                    ReportShareResult.FailureReason.UPLOAD_HTTP_ERROR,
                    "El servicio respondió con error HTTP $code. Es posible que temp.sh esté caído. " +
                        "Usa el botón de compartir local mientras tanto.",
                )
            }
            val raw = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val url = extractUrl(raw)
                ?: run {
                    lastUploadError = "no url in response: ${raw.take(200)}"
                    return ReportShareResult.Failure(
                        ReportShareResult.FailureReason.UPLOAD_BAD_RESPONSE,
                        "El servicio temp.sh respondió OK pero no devolvió un enlace utilizable. " +
                            "Inténtalo de nuevo o usa el botón de compartir local.",
                    )
                }
            ReportShareResult.TempLinkShareResult(
                url = url,
                retentionDescription = "Válido ~3 días",
            )
        } catch (e: IOException) {
            lastUploadError = "transfer: ${e.message}"
            ReportShareResult.Failure(
                ReportShareResult.FailureReason.UPLOAD_NETWORK_ERROR,
                "Se cortó la conexión durante la subida: ${e.message ?: "sin detalle"}. " +
                    "Reintenta o usa el botón de compartir local.",
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Build a multipart/form-data body for a single file field named `file`,
     * matching the temp.sh contract (`curl -F "file=@x.html"`).
     *
     * Pure function — no I/O beyond reading the file bytes; no network.
     * Returned [ByteArray] is the entire body, ready to be written to the
     * connection's output stream after [HttpURLConnection.setFixedLengthStreamingMode].
     *
     * @throws IOException if the file cannot be read.
     */
    fun buildMultipartBody(file: File, mimeType: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)

        // --boundary
        out.writeBytes("--$BOUNDARY\r\n")
        // Content-Disposition with file name. We sanitize the name to be safe
        // for the header — no CR/LF, no quotes. temp.sh uses the original
        // name in the returned URL so keeping it close to the source helps
        // the recipient identify the file.
        val safeName = file.name.replace(Regex("[\r\n\"]"), "_")
        out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n")
        out.writeBytes("Content-Type: $mimeType\r\n\r\n")
        out.write(file.readBytes())
        out.writeBytes("\r\n--$BOUNDARY--\r\n")
        out.flush()
        return baos.toByteArray()
    }

    /**
     * Pull the first `https?://...` URL out of the response body.
     *
     * temp.sh historically returns the URL on a line of its own, optionally
     * preceded by curl progress output if the client streams stdout (curl
     * does, our JVM client does not — but the parser stays defensive). We
     * scan line-by-line and trim, accepting any line that starts with
     * `http://` or `https://` and contains a `/`. Returns the FIRST match
     * so a future server that prints a banner above the URL still works.
     */
    fun extractUrl(responseBody: String): String? {
        for (rawLine in responseBody.lineSequence()) {
            val line = rawLine.trim()
            val schemeEnd = when {
                line.startsWith("https://") -> "https://".length
                line.startsWith("http://") -> "http://".length
                else -> continue
            }
            // Require at least one slash AFTER the host portion so a bare
            // domain (e.g. "https://temp.sh") is rejected as un-shareable —
            // we want a URL that points to a specific file, not a homepage.
            if (line.indexOf('/', startIndex = schemeEnd) > 0) {
                return line
            }
        }
        return null
    }

    /** Read the error stream defensively for the HTTP failure path. */
    private fun readError(conn: HttpURLConnection): String =
        runCatching {
            conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
        }.getOrNull()?.take(200) ?: "(sin detalle)"
}
