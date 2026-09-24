package de.dk8de.rotorapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import de.dk8de.rotorapp.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * APK selbst laden und installieren, statt den Browser zu beauftragen.
 *
 * Über den Browser sammelt sich mit jeder Version eine weitere Datei im
 * Download-Ordner an und alte Downloads werden wieder aufgenommen. Hier liegt
 * immer genau eine APK im App-Cache, die vor jedem Download ersetzt wird.
 */
object UpdateInstaller {
    private const val DIR = "updates"
    private const val FILE = "RotorApp-update.apk"
    private const val TIMEOUT_MS = 15_000

    /** Beim Start aufräumen: nach erfolgreicher Installation bleibt die APK sonst liegen. */
    fun clearCached(context: Context) {
        runCatching { File(File(context.cacheDir, DIR), FILE).delete() }
    }

    /** Darf die App eine Installation anstoßen? (einmalige Systemfreigabe) */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Systemseite „Unbekannte Apps installieren“ für genau diese App öffnen. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * APK in den App-Cache laden.
     * @param onProgress 0..1, oder -1 wenn der Server keine Größe meldet.
     * @return Datei, oder null bei Abbruch/Fehler.
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val target = File(File(context.cacheDir, DIR).apply { mkdirs() }, FILE)
        target.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RotorApp/${AppVersion.NAME}")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
            if (target.length() <= 0L) null else target
        } catch (e: Exception) {
            target.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Installationsdialog des Systems öffnen. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
