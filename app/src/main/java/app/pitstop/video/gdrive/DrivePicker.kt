package app.pitstop.video.gdrive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.pitstop.video.core.I18n
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.getString
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest.ResourceParameter
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** One file or folder the user shared with the app through Google's Drive picker. */
data class PickedDriveFile(val id: String, val name: String, val mimeType: String) {
    val isFolder get() = mimeType == FOLDER_MIME
}

const val FOLDER_MIME = "application/vnd.google-apps.folder"

/**
 * Experimental (issue #2): Google's own Drive picker with the non-sensitive `drive.file` scope.
 * The app only ever sees what the user picks, so it would not need restricted-scope verification
 * or a CASA assessment. The picker is shown by the Identity AuthorizationClient itself
 * ([ResourceParameter.PICKER_OAUTH_TRIGGER]); picked ids come back in the token response params.
 */
class DrivePicker(private val context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("gdrive_picker", Context.MODE_PRIVATE)
    private val _files = MutableStateFlow(read())
    val files: StateFlow<List<PickedDriveFile>> = _files

    private val client get() = Identity.getAuthorizationClient(context)
    private val scope = Scope(SCOPE)

    /** Request that opens the picker: drive.file only, consent + account choice, any file (audio is kept) or folders. */
    private fun pickRequest() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(scope))
        .setOptOutIncludingGrantedScopes(true)
        .setPrompt(AuthorizationRequest.Prompt.CONSENT or AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        .addResourceParameter(ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
        .addResourceParameter(ResourceParameter.PICKER_ALLOW_MULTIPLE, "true")
        .addResourceParameter(ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, "true")
        // No PICKER_MIMETYPES filter: "audio/*" hid every file in the picker. Non-audio picks are dropped below.
        .build()

    /** Plain token request for files picked earlier; no UI unless Google needs consent again. */
    private fun tokenRequest() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(scope))
        .setOptOutIncludingGrantedScopes(true)
        .build()

    /** The picker always needs UI; returns its PendingIntent. */
    suspend fun beginPick(): PendingIntent {
        val result = client.authorize(pickRequest()).await()
        Log.i(TAG, "authorize: resolution=${result.hasResolution()} params=${result.tokenResponseParams?.keySet()}")
        return result.pendingIntent ?: throw UserFacingException(I18n.s.drivePickerFailed("no picker UI"))
    }

    /** Reads the picked ids from the picker's result, then fetches their names. Returns how many were added. */
    suspend fun completePick(data: Intent?): Int {
        val result = client.getAuthorizationResultFromIntent(data)
        val params = result.tokenResponseParams
        Log.i(TAG, "result: token=${result.accessToken != null} scopes=${result.grantedScopes} params=${params?.keySet()?.associateWith { params.get(it) }}")
        val ids = params?.getString(PICKED_IDS).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val token = result.accessToken ?: throw UserFacingException(I18n.s.drivePickerFailed("no access token"))
        cached = token to android.os.SystemClock.elapsedRealtime()
        val picked = ids.map { id -> metadata(id, token) }.filter { it.isFolder || it.mimeType.startsWith("audio/") }
        save(picked + _files.value.filter { old -> picked.none { it.id == old.id } })
        return picked.size
    }

    /**
     * Result of the picker opened to choose where downloads go: the first picked folder (remembered,
     * so its songs also show in Music → Google Drive), or null when only files were picked.
     */
    suspend fun completeFolderPick(data: Intent?): PickedDriveFile? {
        val result = client.getAuthorizationResultFromIntent(data)
        val ids = result.tokenResponseParams?.getString(PICKED_IDS).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val token = result.accessToken ?: throw UserFacingException(I18n.s.drivePickerFailed("no access token"))
        cached = token to android.os.SystemClock.elapsedRealtime()
        val folder = ids.map { metadata(it, token) }.firstOrNull { it.isFolder } ?: return null
        remember(folder)
        return folder
    }

    fun clear() = save(emptyList())

    /** Adds a file or folder the app made itself (drive.file covers it), e.g. the downloads folder. */
    fun remember(file: PickedDriveFile) {
        if (_files.value.none { it.id == file.id }) save(_files.value + file)
    }

    /** Last token and when it was issued; reused for 40 minutes (Google tokens live ~1 h). */
    @Volatile private var cached: Pair<String, Long>? = null

    suspend fun token(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { (t, at) -> if (now - at < 40 * 60_000L) return t }
        val result = client.authorize(tokenRequest()).await()
        if (result.hasResolution()) throw UserFacingException(I18n.s.drivePickAgain)
        val t = result.accessToken ?: throw UserFacingException(I18n.s.drivePickerFailed("no access token"))
        cached = t to now
        return t
    }

    /** Subfolders and audio files directly inside a picked folder (drive.file covers a picked folder's contents). */
    suspend fun children(folderId: String): List<PickedDriveFile> {
        val q = "'$folderId' in parents and (mimeType contains 'audio/' or mimeType = '$FOLDER_MIME') and trashed = false"
        val out = mutableListOf<PickedDriveFile>()
        var pageToken: String? = null
        val token = token()
        do {
            val url = "$API/files?orderBy=name&pageSize=${DriveSource.MAX_ITEMS - out.size}&fields=nextPageToken,files(id,name,mimeType)" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true&q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val json = JSONObject(http.getString(authed(url, token)))
            val arr = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) arr.getJSONObject(i).let {
                out += PickedDriveFile(it.getString("id"), it.optString("name"), it.optString("mimeType"))
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null && out.size < DriveSource.MAX_ITEMS)
        return out.sortedBy { !it.isFolder }
    }

    fun mediaUrl(id: String) = "$API/files/$id?alt=media&acknowledgeAbuse=true"

    private suspend fun metadata(id: String, token: String): PickedDriveFile {
        val o = JSONObject(http.getString(authed("$API/files/$id?fields=id,name,mimeType&supportsAllDrives=true", token)))
        return PickedDriveFile(o.getString("id"), o.optString("name"), o.optString("mimeType"))
    }

    private fun authed(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").build()

    private fun save(list: List<PickedDriveFile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mimeType)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
        _files.value = list
    }

    private fun read(): List<PickedDriveFile> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { PickedDriveFile(it.getString("id"), it.optString("name"), it.optString("mime")) }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val PICKED_IDS = "picked_file_ids"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val KEY = "files"
        private const val TAG = "DrivePicker"
    }
}
