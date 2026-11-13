package com.ohm.launcher.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.ohm.launcher.MainActivity
import com.ohm.launcher.Ohm
import com.ohm.launcher.core.OhmLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a non-exportable Android Keystore key — used for the Spotify refresh token. */
object Vault {
    private const val ALIAS = "ohm-vault"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String? = runCatching {
        val (iv, ct) = blob.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct))
    }.getOrNull()
}

/** auth states for the UI */
sealed class AuthState {
    data object NoClientId : AuthState()
    data object SignedOut : AuthState()
    data object SigningIn : AuthState()
    data class SignedIn(val name: String, val avatar: String?, val userId: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * Spotify Authorization Code + PKCE. The redirect goes to a tiny one-shot HTTP server on
 * 127.0.0.1 inside Ohm (Spotify requires loopback IPs, not "localhost", for native apps).
 * The user registers their own developer app and pastes its Client ID — no secret is ever stored.
 */
class SpotifyAuth(private val ctx: Context) {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private val http = OkHttpClient()
    private val mutex = Mutex()

    private var accessToken: String? = null
    private var expiresAt = 0L
    private var refreshToken: String? = null

    val clientId get() = Ohm.prefs.spotifyClientId.value.trim()

    fun init() {
        val blob = Ohm.prefs.spotifyTokens.value
        if (clientId.isEmpty()) { _state.value = AuthState.NoClientId; return }
        if (blob.isEmpty()) { _state.value = AuthState.SignedOut; return }
        val json = Vault.decrypt(blob)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null) { _state.value = AuthState.SignedOut; return }
        refreshToken = json.optString("refresh").ifEmpty { null }
        _state.value = AuthState.SignedIn(json.optString("name", "Spotify"), json.optString("avatar").ifEmpty { null }, json.optString("user"))
    }

    fun onClientIdChanged() { signOut(); init() }

    // ------------------------------------------------------------------ sign in

    suspend fun signIn(activity: Context) {
        if (clientId.isEmpty()) { _state.value = AuthState.NoClientId; return }
        _state.value = AuthState.SigningIn
        val verifier = randomString(64)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val st = randomString(16)
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", st)
            .appendQueryParameter("scope", SCOPES)
            .build()
        try {
            val server = withContext(Dispatchers.IO) { ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 300_000 } }
            runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, url) }
                .onFailure { activity.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val params = withContext(Dispatchers.IO) { server.use { awaitRedirect(it) } }
            bringOhmToFront()
            params["error"]?.let { error(if (it == "access_denied") "Sign-in was cancelled" else it) }
            if (params["state"] != st) error("Sign-in state mismatch — please try again")
            val code = params["code"] ?: error("No authorization code")
            tokenRequest(FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", REDIRECT).add("client_id", clientId).add("code_verifier", verifier).build())
            val me = Ohm.spotify.api.me()
            _state.value = AuthState.SignedIn(me.optString("display_name", "Spotify"), me.optJSONArray("images")?.let { pickImage(it) }, me.optString("id"))
            persist()
            OhmLog.i("Spotify", "Signed in as ${me.optString("display_name")}")
            Ohm.spotify.onSignedIn()
        } catch (e: SocketTimeoutException) {
            _state.value = AuthState.Error("Sign-in timed out")
        } catch (e: Exception) {
            OhmLog.w("Spotify", "Sign-in failed", e)
            _state.value = AuthState.Error(e.message ?: "Sign-in failed")
        }
    }

    /** Accepts connections until the /callback request arrives; answers with a small page. */
    private fun awaitRedirect(ss: ServerSocket): Map<String, String> {
        while (true) {
            ss.accept().use { sock ->
                val line = sock.getInputStream().bufferedReader().readLine() ?: return@use
                val path = line.split(' ').getOrNull(1) ?: return@use
                val ok = path.startsWith("/callback")
                val html = if (ok) "<html><body style='font-family:sans-serif;background:#171717;color:#fff;text-align:center;padding-top:20vh'><h2>Spotify connected</h2><p>You can return to Ohm.</p></body></html>" else "Not found"
                val bytes = html.toByteArray()
                sock.getOutputStream().apply {
                    write("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
                if (ok) {
                    val q = path.substringAfter('?', "")
                    return q.split('&').filter { '=' in it }.associate { kv ->
                        kv.substringBefore('=') to URLDecoder.decode(kv.substringAfter('='), "UTF-8")
                    }
                }
            }
        }
    }

    private fun bringOhmToFront() = runCatching {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ------------------------------------------------------------------ tokens

    private suspend fun tokenRequest(body: FormBody) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body.string()
            val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) {
                if (j.optString("error") == "invalid_grant") throw SignedOutException()
                error(j.optString("error_description").ifEmpty { "Token request failed (${r.code})" })
            }
            accessToken = j.getString("access_token")
            expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 60_000
            j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { refreshToken = it } // may rotate
        }
    }

    /** A valid access token, refreshing when needed. */
    suspend fun token(): String = mutex.withLock {
        accessToken?.takeIf { System.currentTimeMillis() < expiresAt }?.let { return it }
        val rt = refreshToken ?: throw SignedOutException()
        try {
            tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).add("client_id", clientId).build())
            persist()
        } catch (e: SignedOutException) { signOut(); throw e }
        accessToken!!
    }

    fun invalidate() { expiresAt = 0 }

    private fun persist() {
        val s = _state.value as? AuthState.SignedIn
        val j = JSONObject().put("refresh", refreshToken ?: "").put("name", s?.name ?: "").put("avatar", s?.avatar ?: "").put("user", s?.userId ?: "")
        Ohm.prefs.spotifyTokens.set(Vault.encrypt(j.toString()))
    }

    fun signOut() {
        accessToken = null; refreshToken = null; expiresAt = 0
        Ohm.prefs.spotifyTokens.set("")
        _state.value = if (clientId.isEmpty()) AuthState.NoClientId else AuthState.SignedOut
    }

    private fun randomString(n: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val r = SecureRandom()
        return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        const val PORT = 43117
        const val REDIRECT = "http://127.0.0.1:43117/callback"
        const val SCOPES = "user-read-private user-read-playback-state user-modify-playback-state user-read-currently-playing " +
            "user-read-recently-played user-read-playback-position user-top-read user-library-read user-library-modify " +
            "user-follow-read user-follow-modify playlist-read-private playlist-read-collaborative playlist-modify-public playlist-modify-private"
    }
}

class SignedOutException : Exception("Signed out of Spotify")
