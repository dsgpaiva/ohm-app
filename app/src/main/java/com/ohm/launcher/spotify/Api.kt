package com.ohm.launcher.spotify

import com.ohm.launcher.core.OhmLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ===================================================================== models

/** One browsable thing: track, episode, album, artist, playlist, show, or the "Liked Songs" collection. */
data class SpItem(
    val type: String,
    val uri: String,
    val id: String,
    val name: String,
    val subtitle: String,
    val image: String?,
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    /** Album/show the track or episode belongs to. */
    val parentUri: String? = null,
    val artistUris: List<String> = emptyList(),
    val ownerId: String? = null,
    val collaborative: Boolean = false,
    val count: Int? = null,
    val description: String? = null,
)

data class Page(val items: List<SpItem>, val next: Boolean, val nextOffset: Int = 0, val cursor: String? = null)

data class SpDevice(val id: String, val name: String, val type: String, val active: Boolean, val volume: Int?, val restricted: Boolean, val supportsVolume: Boolean)

data class SpPlayback(
    val item: SpItem?,
    val playing: Boolean,
    val progressMs: Long,
    val fetchedAt: Long,
    val shuffle: Boolean,
    /** off | context | track */
    val repeat: String,
    val device: SpDevice?,
    val contextUri: String?,
    val liked: Boolean = false,
    val disallows: Set<String> = emptySet(),
) {
    fun livePosition(now: Long = System.currentTimeMillis()): Long =
        if (!playing) progressMs else (progressMs + (now - fetchedAt)).coerceAtMost(item?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE)
}

fun pickImage(a: JSONArray?): String? {
    if (a == null || a.length() == 0) return null
    // Spotify lists images largest first; pick ~300 px for lists/covers.
    var best: JSONObject? = null
    for (i in 0 until a.length()) {
        val o = a.getJSONObject(i)
        val w = o.optInt("width", 0)
        if (best == null || (w in 250..700) || (best.optInt("width", 0) > 700 && w > 0)) best = o
    }
    return (best ?: a.getJSONObject(0)).optString("url").ifEmpty { null }
}

private fun artists(o: JSONObject) = o.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty().joinToString(", ")
private fun artistUris(o: JSONObject) = o.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("uri") } }.orEmpty()

fun parseItem(o: JSONObject?, albumImage: String? = null, albumUri: String? = null): SpItem? {
    o ?: return null
    val type = o.optString("type")
    val uri = o.optString("uri").ifEmpty { return null }
    val id = o.optString("id")
    return when (type) {
        "track" -> {
            val album = o.optJSONObject("album")
            SpItem(type, uri, id, o.optString("name"), artists(o), album?.optJSONArray("images")?.let { pickImage(it) } ?: albumImage,
                o.optLong("duration_ms"), o.optBoolean("explicit"), album?.optString("uri") ?: albumUri, artistUris(o))
        }
        "episode" -> SpItem(type, uri, id, o.optString("name"), o.optJSONObject("show")?.optString("name") ?: o.optString("release_date"),
            pickImage(o.optJSONArray("images")) ?: albumImage, o.optLong("duration_ms"), o.optBoolean("explicit"), o.optJSONObject("show")?.optString("uri") ?: albumUri,
            description = o.optString("description").ifEmpty { null })
        "album" -> SpItem(type, uri, id, o.optString("name"), artists(o) + o.optString("release_date").take(4).let { if (it.isNotEmpty()) " · $it" else "" },
            pickImage(o.optJSONArray("images")), artistUris = artistUris(o), count = o.optInt("total_tracks").takeIf { it > 0 })
        "artist" -> SpItem(type, uri, id, o.optString("name"), "Artist", pickImage(o.optJSONArray("images")))
        "playlist" -> SpItem(type, uri, id, o.optString("name"), "Playlist · " + (o.optJSONObject("owner")?.optString("display_name") ?: ""),
            pickImage(o.optJSONArray("images")), ownerId = o.optJSONObject("owner")?.optString("id"), collaborative = o.optBoolean("collaborative"),
            count = (o.optJSONObject("items") ?: o.optJSONObject("tracks"))?.optInt("total"), description = o.optString("description").ifEmpty { null })
        "show" -> SpItem(type, uri, id, o.optString("name"), "Podcast", pickImage(o.optJSONArray("images")), description = o.optString("description").ifEmpty { null })
        "audiobook" -> SpItem(type, uri, id, o.optString("name"), "Audiobook", pickImage(o.optJSONArray("images")))
        else -> null
    }
}

class SpotifyException(val status: Int, message: String, val reason: String? = null) : Exception(message) {
    val premiumRequired get() = status == 403 && (reason == "PREMIUM_REQUIRED" || (message ?: "").contains("premium", true))
    val noDevice get() = status == 404 && (reason == "NO_ACTIVE_DEVICE" || (message ?: "").contains("device", true))
}

// ===================================================================== client

/**
 * Spotify Web API, limited to what Development-Mode apps may use since February 2026
 * (search ≤ 10 per type, playlist items only for own/collaborative playlists, /me/library for saving).
 */
class SpotifyApi(private val auth: SpotifyAuth) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private val base = "https://api.spotify.com/v1"

    private class Res(val code: Int, val text: String, val retryAfter: Long)

    /** One API call with token refresh on 401 and back-off on 429. Returns the body (may be empty). */
    private suspend fun call(method: String, path: String, query: Map<String, Any?> = emptyMap(), body: JSONObject? = null): String {
        for (attempt in 0..2) {
            val token = auth.token()
            val url = (if (path.startsWith("http")) path else base + path).toHttpUrl().newBuilder().apply {
                for ((k, v) in query) if (v != null) addQueryParameter(k, v.toString())
            }.build()
            val rb = when {
                method == "GET" -> null
                body != null -> body.toString().toRequestBody(jsonType)
                method == "DELETE" -> null
                else -> "".toRequestBody(jsonType)
            }
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, rb).build()
            val res = withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { r -> Res(r.code, r.body.string(), r.header("Retry-After")?.toLongOrNull() ?: 2) }
            }
            when {
                res.code in 200..299 -> return res.text
                res.code == 401 && attempt == 0 -> auth.invalidate()
                res.code == 429 && attempt < 2 -> { OhmLog.w("Spotify", "Rate limited, waiting ${res.retryAfter}s"); delay(res.retryAfter.coerceAtMost(10) * 1000) }
                else -> {
                    val err = runCatching { JSONObject(res.text).optJSONObject("error") }.getOrNull()
                    val msg = err?.optString("message").orEmpty().ifEmpty { "Spotify error ${res.code}" }
                    val reason = err?.optString("reason").orEmpty().ifEmpty { null }
                    throw SpotifyException(res.code, msg, reason)
                }
            }
        }
        throw SpotifyException(429, "Spotify is busy, try again")
    }

    private suspend fun getJson(path: String, query: Map<String, Any?> = emptyMap()): JSONObject =
        call("GET", path, query).takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()

    // ---------------------------------------------------------------- user & home

    suspend fun me() = getJson("/me")

    suspend fun recentlyPlayed(): List<Pair<SpItem, String?>> {
        val a = getJson("/me/player/recently-played", mapOf("limit" to 50)).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            parseItem(o.optJSONObject("track"))?.let { it to o.optJSONObject("context")?.optString("uri") }
        }
    }

    suspend fun top(type: String, range: String = "short_term"): List<SpItem> =
        getJson("/me/top/$type", mapOf("limit" to 20, "time_range" to range)).optJSONArray("items").items()

    // ---------------------------------------------------------------- library

    suspend fun myPlaylists(offset: Int = 0) = page(getJson("/me/playlists", mapOf("limit" to 50, "offset" to offset)), offset)
    suspend fun likedSongs(offset: Int = 0) = page(getJson("/me/tracks", mapOf("limit" to 50, "offset" to offset)), offset, "track")
    suspend fun savedAlbums(offset: Int = 0) = page(getJson("/me/albums", mapOf("limit" to 50, "offset" to offset)), offset, "album")
    suspend fun savedShows(offset: Int = 0) = page(getJson("/me/shows", mapOf("limit" to 50, "offset" to offset)), offset, "show")
    suspend fun savedEpisodes(offset: Int = 0) = page(getJson("/me/episodes", mapOf("limit" to 50, "offset" to offset)), offset, "episode")

    suspend fun followedArtists(after: String? = null): Page {
        val o = getJson("/me/following", mapOf("type" to "artist", "limit" to 50, "after" to after)).optJSONObject("artists") ?: JSONObject()
        val items = o.optJSONArray("items").items()
        val cursor = o.optJSONObject("cursors")?.optString("after")?.takeIf { it.isNotEmpty() && it != "null" }
        return Page(items, cursor != null, cursor = cursor)
    }

    private fun page(o: JSONObject, offset: Int, wrapped: String? = null): Page {
        val a = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until a.length()).mapNotNull { i ->
            val e = a.getJSONObject(i)
            parseItem(if (wrapped != null) e.optJSONObject(wrapped) else e)
        }
        return Page(items, !o.isNull("next") && o.has("next"), offset + a.length())
    }

    private fun JSONArray?.items(): List<SpItem> = if (this == null) emptyList() else (0 until length()).mapNotNull { parseItem(optJSONObject(it)) }

    // ---------------------------------------------------------------- details

    suspend fun playlist(id: String) = getJson("/playlists/$id")

    /** Only works for playlists the user owns or collaborates on (Dev-Mode rule). */
    suspend fun playlistItems(id: String, offset: Int = 0): Page {
        val o = getJson("/playlists/$id/items", mapOf("limit" to 50, "offset" to offset))
        val a = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { parseItem(it.optJSONObject("item") ?: it.optJSONObject("track")) } }
        return Page(items, !o.isNull("next") && o.has("next"), offset + a.length())
    }

    suspend fun album(id: String) = getJson("/albums/$id")

    suspend fun albumTracks(id: String, image: String?, uri: String, offset: Int = 0): Page {
        val o = getJson("/albums/$id/tracks", mapOf("limit" to 50, "offset" to offset))
        val a = o.optJSONArray("items") ?: JSONArray()
        return Page((0 until a.length()).mapNotNull { parseItem(a.getJSONObject(it), image, uri) }, !o.isNull("next") && o.has("next"), offset + a.length())
    }

    suspend fun artist(id: String) = getJson("/artists/$id")
    suspend fun artistAlbums(id: String, groups: String = "album,single,compilation") =
        getJson("/artists/$id/albums", mapOf("include_groups" to groups, "limit" to 50)).optJSONArray("items").items()

    suspend fun show(id: String) = getJson("/shows/$id")
    suspend fun showEpisodes(id: String, image: String?, uri: String, offset: Int = 0): Page {
        val o = getJson("/shows/$id/episodes", mapOf("limit" to 50, "offset" to offset))
        val a = o.optJSONArray("items") ?: JSONArray()
        return Page((0 until a.length()).mapNotNull { parseItem(a.optJSONObject(it), image, uri) }, !o.isNull("next") && o.has("next"), offset + a.length())
    }

    // ---------------------------------------------------------------- search

    /** Dev-Mode apps get at most 10 results per type per request. */
    suspend fun search(q: String, types: List<String>, offset: Int = 0): Map<String, List<SpItem>> {
        val o = getJson("/search", mapOf("q" to q, "type" to types.joinToString(","), "limit" to 10, "offset" to offset))
        return types.associateWith { t -> o.optJSONObject(t + "s")?.optJSONArray("items").items() }
    }

    // ---------------------------------------------------------------- library save / contains (Feb-2026 endpoints)

    suspend fun contains(uris: List<String>): List<Boolean> {
        if (uris.isEmpty()) return emptyList()
        val text = call("GET", "/me/library/contains", mapOf("uris" to uris.take(40).joinToString(",")))
        val a = runCatching { JSONArray(text) }.getOrNull() ?: return uris.map { false }
        return (0 until a.length()).map { a.optBoolean(it) }
    }

    suspend fun save(uris: List<String>) { call("PUT", "/me/library", mapOf("uris" to uris.take(40).joinToString(","))) }
    suspend fun remove(uris: List<String>) { call("DELETE", "/me/library", mapOf("uris" to uris.take(40).joinToString(","))) }

    // ---------------------------------------------------------------- player

    suspend fun playback(): SpPlayback? {
        val text = call("GET", "/me/player", mapOf("additional_types" to "track,episode"))
        if (text.isBlank()) return null
        val o = JSONObject(text)
        val dis = o.optJSONObject("actions")?.optJSONObject("disallows")?.let { d -> d.keys().asSequence().filter { d.optBoolean(it) }.toSet() }.orEmpty()
        return SpPlayback(
            item = parseItem(o.optJSONObject("item")),
            playing = o.optBoolean("is_playing"),
            progressMs = o.optLong("progress_ms"),
            fetchedAt = System.currentTimeMillis(),
            shuffle = o.optBoolean("shuffle_state"),
            repeat = o.optString("repeat_state", "off"),
            device = o.optJSONObject("device")?.let { device(it) },
            contextUri = o.optJSONObject("context")?.optString("uri"),
            disallows = dis,
        )
    }

    private fun device(d: JSONObject) = SpDevice(d.optString("id"), d.optString("name"), d.optString("type"), d.optBoolean("is_active"),
        if (d.isNull("volume_percent")) null else d.optInt("volume_percent"), d.optBoolean("is_restricted"), d.optBoolean("supports_volume", true))

    suspend fun devices(): List<SpDevice> {
        val a = getJson("/me/player/devices").optJSONArray("devices") ?: return emptyList()
        return (0 until a.length()).map { device(a.getJSONObject(it)) }
    }

    suspend fun transfer(deviceId: String, play: Boolean) {
        call("PUT", "/me/player", body = JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", play))
    }

    /** Play a context (album/playlist/artist/show), optionally starting at [offsetUri]; or a list of [uris]. */
    suspend fun play(deviceId: String?, contextUri: String? = null, offsetUri: String? = null, uris: List<String>? = null, positionMs: Long? = null) {
        val b = JSONObject()
        if (contextUri != null) b.put("context_uri", contextUri)
        if (uris != null) b.put("uris", JSONArray(uris.take(100)))
        if (offsetUri != null) b.put("offset", JSONObject().put("uri", offsetUri))
        if (positionMs != null) b.put("position_ms", positionMs)
        call("PUT", "/me/player/play", mapOf("device_id" to deviceId), if (b.length() == 0) null else b)
    }

    suspend fun resume(deviceId: String?) = call("PUT", "/me/player/play", mapOf("device_id" to deviceId))
    suspend fun pause() = call("PUT", "/me/player/pause")
    suspend fun next() = call("POST", "/me/player/next")
    suspend fun previous() = call("POST", "/me/player/previous")
    suspend fun seek(ms: Long) = call("PUT", "/me/player/seek", mapOf("position_ms" to ms.coerceAtLeast(0)))
    suspend fun shuffle(on: Boolean) = call("PUT", "/me/player/shuffle", mapOf("state" to on))
    suspend fun repeat(mode: String) = call("PUT", "/me/player/repeat", mapOf("state" to mode))
    suspend fun volume(pct: Int) = call("PUT", "/me/player/volume", mapOf("volume_percent" to pct.coerceIn(0, 100)))
    suspend fun addToQueue(uri: String) = call("POST", "/me/player/queue", mapOf("uri" to uri))

    suspend fun queue(): Pair<SpItem?, List<SpItem>> {
        val o = getJson("/me/player/queue")
        return parseItem(o.optJSONObject("currently_playing")) to o.optJSONArray("queue").items()
    }
}
