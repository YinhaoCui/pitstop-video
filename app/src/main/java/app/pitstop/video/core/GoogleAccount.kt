package app.pitstop.video.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in for one OAuth scope (YouTube read-only, Drive read-only, ...) via the Identity
 * AuthorizationClient. Needs an Android OAuth client for this package + signing SHA-1 (see README).
 */
open class GoogleScopedAccount(
    private val context: Context,
    prefsName: String,
    private val scope: String,
    private val finishMessage: () -> String,
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val _signedIn = MutableStateFlow(prefs.getBoolean(KEY_SIGNED_IN, false))
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val client get() = Identity.getAuthorizationClient(context)
    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(scope))).build()

    /** Returns a PendingIntent to show the consent UI, or null when access was granted silently. */
    suspend fun beginSignIn(): PendingIntent? {
        val result = client.authorize(request()).await()
        if (result.hasResolution()) return result.pendingIntent
        setSignedIn(result.accessToken != null)
        return null
    }

    fun completeSignIn(data: Intent?) {
        val result = client.getAuthorizationResultFromIntent(data)
        setSignedIn(result.accessToken != null)
    }

    fun signOut() { cached = null; setSignedIn(false) }

    /** Last token and when it was issued; Google tokens live ~1 h, reused for [TOKEN_TTL_MS]. */
    @Volatile private var cached: Pair<String, Long>? = null

    private fun setSignedIn(value: Boolean) {
        prefs.edit().putBoolean(KEY_SIGNED_IN, value).apply()
        _signedIn.value = value
    }

    /** A fresh access token (Google caches and refreshes it); throws if consent is still needed. */
    suspend fun token(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { (t, at) -> if (now - at < TOKEN_TTL_MS) return t }
        val result = client.authorize(request()).await()
        if (result.hasResolution()) throw UserFacingException(finishMessage())
        val t = result.accessToken ?: throw UserFacingException("Google sign-in failed")
        cached = t to now
        return t
    }

    private companion object {
        const val KEY_SIGNED_IN = "signed_in"
        const val TOKEN_TTL_MS = 40 * 60_000L
    }
}
