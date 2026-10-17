package es.fran.cochelauncher

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Busca la última versión publicada en GitHub, la descarga y la instala. */
object Updater {

    private const val API = "https://api.github.com/repos/Xeracion/coche-launcher/releases/latest"
    private val main = Handler(Looper.getMainLooper())

    data class Release(val version: String, val url: String)

    /** Consulta la última versión. onDone recibe null si no hay conexión. */
    fun check(onDone: (Release?) -> Unit) {
        Thread {
            val r = try {
                val conn = URL(API).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                try {
                    if (conn.responseCode != 200) null
                    else {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val assets = json.getJSONArray("assets")
                        var url: String? = null
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.getString("name").endsWith(".apk")) {
                                url = a.getString("browser_download_url")
                                break
                            }
                        }
                        if (url == null) null else Release(json.optString("tag_name", "?"), url)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            main.post { onDone(r) }
        }.start()
    }

    /** Descarga el APK y abre el instalador. onProgress va de 0 a 100, o -1 si falla. */
    fun download(ctx: Context, release: Release, onError: (String) -> Unit = {}, onProgress: (Int) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val out = File(app.cacheDir, "update.apk")
                var conn = URL(release.url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                // GitHub redirige a otro servidor; seguimos el salto a mano si hace falta.
                var hops = 0
                while (conn.responseCode in 301..308 && hops < 5) {
                    val next = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = URL(next).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    hops++
                }
                if (conn.responseCode != 200) throw IllegalStateException("http ${conn.responseCode}")
                val total = conn.contentLength.toLong()
                var done = 0L
                conn.inputStream.use { input ->
                    out.outputStream().use { file ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            file.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                main.post { onProgress(pct) }
                            }
                        }
                    }
                }
                conn.disconnect()
                copyToDownloads(app, out)
                val err = install(app, out)
                main.post {
                    onProgress(100)
                    if (err != null) onError(err)
                }
            } catch (e: Exception) {
                main.post { onProgress(-1) }
            }
        }.start()
    }

    /** ¿Tiene permiso para instalar apps? */
    fun canInstall(ctx: Context): Boolean = try {
        ctx.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        false
    }

    /** Abre la pantalla donde se concede ese permiso. */
    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
            }
        }
    }

    /** Lanza el instalador. Devuelve null si fue bien, o el motivo del fallo. */
    fun install(ctx: Context, apk: File): String? {
        if (!apk.exists() || apk.length() < 100_000) return "el archivo descargado está incompleto"
        val uri: Uri = try {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        } catch (e: Exception) {
            return "no se pudo preparar el archivo (" + e.javaClass.simpleName + ")"
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(intent)
            return null
        } catch (e: Exception) {
            // Segundo intento con la acción antigua de instalación.
            try {
                @Suppress("DEPRECATION")
                ctx.startActivity(
                    Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setData(uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
                return null
            } catch (e2: Exception) {
                return "la radio no abrió el instalador (" + e2.javaClass.simpleName + ")"
            }
        }
    }

    /** Copia de seguridad en Descargas, para poder instalarla a mano si hace falta. */
    private fun copyToDownloads(ctx: Context, apk: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "InicioCoche.apk")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
        }
    }

    /** El APK ya descargado, si existe. */
    fun downloaded(ctx: Context): File? {
        val f = File(ctx.applicationContext.cacheDir, "update.apk")
        return if (f.exists() && f.length() > 100_000) f else null
    }
}
