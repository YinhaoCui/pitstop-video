package app.pitstop.video.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.pitstop.video.core.GoogleScopedAccount
import app.pitstop.video.core.I18n
import app.pitstop.video.gdrive.DrivePicker
import app.pitstop.video.gdrive.FOLDER_MIME
import app.pitstop.video.gdrive.PickedDriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Saves downloads to a "Pitstop" folder in the user's Google Drive. Uses the non-sensitive
 * `drive.file` scope: the app only sees files and folders it created (or the user picked).
 * The folder is added to the Drive picker list, so uploaded songs also show in Music → Google Drive.
 */
class DriveUploader(context: Context, private val http: OkHttpClient, private val picker: DrivePicker) {
    val account = GoogleScopedAccount(context, "gdrive_upload", DrivePicker.SCOPE) { I18n.s.driveUploadSignIn }
    private val prefs = context.getSharedPreferences("gdrive_upload", Context.MODE_PRIVATE)

    /** The folder the user picked for downloads; null = the app's own "Pitstop" folder. */
    private val _chosen = MutableStateFlow(
        prefs.getString(KEY_CHOSEN_ID, null)?.let { DownloadFolder(it, prefs.getString(KEY_CHOSEN_NAME, null) ?: it) }
    )
    val chosenFolder: StateFlow<DownloadFolder?> = _chosen.asStateFlow()

    /** Opens Google's Drive picker; the user picks the folder downloads should go to. */
    suspend fun beginPickFolder(): PendingIntent = picker.beginPick(folders = true)

    /** Saves the folder picked in [beginPickFolder]'s picker; false when no folder was picked. */
    suspend fun completePickFolder(data: Intent?): Boolean {
        val folder = picker.completeFolderPick(data) ?: return false
        setFolder(DownloadFolder(folder.id, folder.name))
        return true
    }

    fun setFolder(folder: DownloadFolder?) {
        prefs.edit().putString(KEY_CHOSEN_ID, folder?.id).putString(KEY_CHOSEN_NAME, folder?.name).apply()
        _chosen.value = folder
    }

    /** Uploads [file] as [name]; returns the new Drive file id. */
    suspend fun upload(file: File, name: String, mime: String, onProgress: (Float) -> Unit): String {
        val token = account.token()
        val chosen = _chosen.value
        val folder = chosen?.id ?: folderId(token)
        return try {
            uploadTo(folder, file, name, mime, token, onProgress)
        } catch (e: ParentMissing) {
            // The folder was deleted (or access to it lost): use the app's own folder from now on.
            if (chosen != null) setFolder(null) else prefs.edit().remove(KEY_FOLDER).apply()
            uploadTo(folderId(token), file, name, mime, token, onProgress)
        }
    }

    private class ParentMissing : IOException()

    private suspend fun uploadTo(folder: String, file: File, name: String, mime: String, token: String, onProgress: (Float) -> Unit): String =
        withContext(Dispatchers.IO) {
            val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder))
            val start = Request.Builder()
                .url("$UPLOAD?uploadType=resumable&fields=id")
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", file.length().toString())
                .post(meta.toString().toRequestBody(JSON))
                .build()
            val location = http.newCall(start).execute().use { r ->
                if (r.code == 404) throw ParentMissing()
                if (!r.isSuccessful) throw IOException("Drive HTTP ${r.code}: ${r.body?.string()?.take(300)}")
                r.header("Location") ?: throw IOException("Drive gave no upload address")
            }
            val ctx = coroutineContext
            val body = object : RequestBody() {
                override fun contentType(): MediaType = mime.toMediaType()
                override fun contentLength() = file.length()
                override fun writeTo(sink: BufferedSink) {
                    val total = file.length().coerceAtLeast(1)
                    var sent = 0L
                    file.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            ctx.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            sent += n
                            onProgress(sent.toFloat() / total)
                        }
                    }
                }
            }
            val put = Request.Builder().url(location).header("Authorization", "Bearer $token").put(body).build()
            http.newCall(put).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("Drive HTTP ${r.code}: ${text.take(300)}")
                JSONObject(text).getString("id")
            }
        }

    /** The app's "Pitstop" folder: remembered, else found (drive.file sees only app-made ones), else created. */
    private suspend fun folderId(token: String): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_FOLDER, null)?.let { return@withContext it }
        val q = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = http.newCall(authed("$API/files?fields=files(id)&q=" + java.net.URLEncoder.encode(q, "UTF-8"), token).build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("Drive HTTP ${r.code}")
                JSONObject(r.body!!.string()).optJSONArray("files")?.optJSONObject(0)?.optString("id")
            }
        val id = found ?: http.newCall(
            authed("$API/files?fields=id", token)
                .post(JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME).toString().toRequestBody(JSON)).build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Drive HTTP ${r.code}: ${r.body?.string()?.take(300)}")
            JSONObject(r.body!!.string()).getString("id")
        }
        prefs.edit().putString(KEY_FOLDER, id).apply()
        picker.remember(PickedDriveFile(id, FOLDER_NAME, FOLDER_MIME))
        id
    }

    private fun authed(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER_NAME = "Pitstop"
        const val KEY_FOLDER = "folder_id"
        const val KEY_CHOSEN_ID = "chosen_id"
        const val KEY_CHOSEN_NAME = "chosen_name"
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
