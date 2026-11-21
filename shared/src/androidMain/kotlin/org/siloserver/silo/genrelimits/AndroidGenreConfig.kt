package org.siloserver.silo.genrelimits

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.TokenManager

private const val GENRE_CONFIG_PREFS = "silo_genre_config"
private const val KEY_TOKEN = "genre_config_read_token"
private const val KEY_JSON = "document_json"
private const val KEY_ETAG = "etag"
private const val KEY_FETCHED_AT = "fetched_at_ms"

/**
 * The read token lives in the app's encrypted preferences; the last good
 * document, which holds no secret, lives in its own file so nothing that
 * clears sign-in state can drop it and lift a limit.
 */
class SharedPreferencesGenreConfigStore(
    context: Context,
    private val securePrefs: SharedPreferences,
) : GenreConfigStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(GENRE_CONFIG_PREFS, Context.MODE_PRIVATE)

    override fun readToken(): String? = securePrefs.getString(KEY_TOKEN, null)

    override fun writeToken(token: String?) {
        securePrefs.edit().apply { if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token) }.apply()
    }

    override fun readCached(): CachedGenreConfig? {
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return CachedGenreConfig(json, prefs.getString(KEY_ETAG, null), prefs.getLong(KEY_FETCHED_AT, 0L))
    }

    override fun writeCached(cached: CachedGenreConfig) {
        // commit, not apply: the copy must be on disk before it is relied on offline.
        prefs.edit()
            .putString(KEY_JSON, cached.json)
            .apply { if (cached.etag == null) remove(KEY_ETAG) else putString(KEY_ETAG, cached.etag) }
            .putLong(KEY_FETCHED_AT, cached.fetchedAtEpochMs)
            .commit()
    }
}

/** The signed-in server and profile, as the token manager holds them. */
class TokenManagerGenreLimitIdentity(
    private val tokenManager: TokenManager,
    identityTransitions: IdentityTransitionBarrier,
) : GenreLimitIdentity {
    override suspend fun serverUrl(): String? =
        runCatching { tokenManager.getServerUrl() }.getOrNull()?.takeIf { it.isNotBlank() }

    override suspend fun profileId(): String? = tokenManager.getProfileId()

    override val changes: Flow<Unit> = identityTransitions.transitions
        .filter { it.phase == IdentityTransitionPhase.DID_CHANGE }
        .map { }
        .onStart { emit(Unit) }
}

/** The app's genre config repository: a plain HTTP client, never one carrying Silo credentials. */
fun createGenreConfigRepository(
    context: Context,
    securePrefs: SharedPreferences,
    tokenManager: TokenManager,
    identityTransitions: IdentityTransitionBarrier,
): GenreConfigRepository = GenreConfigRepository(
    client = org.siloserver.silo.network.createPlatformHttpClient(),
    store = SharedPreferencesGenreConfigStore(context, securePrefs),
    identity = TokenManagerGenreLimitIdentity(tokenManager, identityTransitions),
)
