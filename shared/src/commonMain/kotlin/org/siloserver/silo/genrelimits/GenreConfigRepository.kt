package org.siloserver.silo.genrelimits

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.siloserver.silo.util.wallClockMillis

const val GENRE_CONFIG_URL = "https://silo-genre-config.bjrdesign.workers.dev/v1/config"
const val GENRE_CONFIG_REFRESH_INTERVAL_MS = 15L * 60L * 1000L

/** The last good config document as fetched, kept on the device. */
data class CachedGenreConfig(
    val json: String,
    val etag: String?,
    /** When the server last confirmed this copy (a 200 or a 304). */
    val fetchedAtEpochMs: Long,
)

/** Device storage for the read token and the last good document. */
interface GenreConfigStore {
    fun readToken(): String?
    fun writeToken(token: String?)
    fun readCached(): CachedGenreConfig?
    fun writeCached(cached: CachedGenreConfig)

    object InMemory : GenreConfigStore {
        private var token: String? = null
        private var cached: CachedGenreConfig? = null
        override fun readToken() = token
        override fun writeToken(token: String?) { this.token = token }
        override fun readCached() = cached
        override fun writeCached(cached: CachedGenreConfig) { this.cached = cached }
    }
}

/** What catalog reads ask before they go out. */
fun interface GenreLimitProvider {
    suspend fun limitFor(surface: GenreLimitSurface): GenreLimit?

    companion object {
        val None = GenreLimitProvider { null }
    }
}

/** The active server URL and profile ID, read when a limit is resolved. */
interface GenreLimitIdentity {
    suspend fun serverUrl(): String?
    suspend fun profileId(): String?
    /** Emits at once, then after each server, account or profile change. */
    val changes: Flow<Unit>
}

enum class GenreConfigFetchError { NO_TOKEN, UNAUTHORIZED, HTTP, NETWORK, INVALID_DOCUMENT }

data class GenreConfigState(
    val document: GenreConfigDocument? = null,
    val fetchedAtEpochMs: Long? = null,
    val hasToken: Boolean = false,
    val refreshing: Boolean = false,
    /** Why the last refresh kept the cached copy; null after a good refresh. */
    val lastError: GenreConfigFetchError? = null,
)

/**
 * Keeps the household's genre config: reads it from the Worker with the
 * device's read token, sends `If-None-Match` so an unchanged document costs a
 * 304, and keeps the last good copy on the device. A failed refresh never
 * replaces that copy, so losing the network never lifts a limit.
 */
class GenreConfigRepository(
    private val client: HttpClient,
    private val store: GenreConfigStore,
    private val identity: GenreLimitIdentity,
    private val url: String = GENRE_CONFIG_URL,
    private val now: () -> Long = ::wallClockMillis,
) : GenreLimitProvider {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val refreshMutex = Mutex()

    // Read the cached copy synchronously so the first catalog read after a
    // cold start is already limited.
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<GenreConfigState> = _state.asStateFlow()

    private fun initialState(): GenreConfigState {
        val cached = store.readCached()
        return GenreConfigState(
            document = cached?.let { decode(it.json) },
            fetchedAtEpochMs = cached?.fetchedAtEpochMs,
            hasToken = !store.readToken().isNullOrBlank(),
        )
    }

    private fun decode(text: String): GenreConfigDocument? =
        try {
            json.decodeFromString(GenreConfigDocument.serializer(), text)
        } catch (e: Exception) {
            null
        }

    /** Saves a new read token (blank clears it). The cached document stays. */
    fun setToken(token: String?) {
        val value = token?.trim()?.takeIf { it.isNotEmpty() }
        store.writeToken(value)
        _state.update { it.copy(hasToken = value != null) }
    }

    override suspend fun limitFor(surface: GenreLimitSurface): GenreLimit? =
        _state.value.document
            ?.limitFor(identity.serverUrl(), identity.profileId())
            ?.forSurface(surface)

    /** The active profile's limit for [surface], updated as the document or profile changes. */
    fun limitFlow(surface: GenreLimitSurface): Flow<GenreLimit?> =
        combine(_state, identity.changes) { state, _ ->
            state.document?.limitFor(identity.serverUrl(), identity.profileId())?.forSurface(surface)
        }.distinctUntilChanged()

    /** Fetches the document once. Returns null on success, else why the cached copy was kept. */
    suspend fun refresh(): GenreConfigFetchError? = refreshMutex.withLock {
        val token = store.readToken()
        if (token.isNullOrBlank()) {
            _state.update { it.copy(hasToken = false, lastError = GenreConfigFetchError.NO_TOKEN) }
            return@withLock GenreConfigFetchError.NO_TOKEN
        }
        _state.update { it.copy(refreshing = true) }
        val cached = store.readCached()
        val error: GenreConfigFetchError? = try {
            val response = client.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/json")
                cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
            when (val status = response.status.value) {
                304 -> {
                    if (cached != null) {
                        val confirmed = cached.copy(fetchedAtEpochMs = now())
                        store.writeCached(confirmed)
                        _state.update { it.copy(fetchedAtEpochMs = confirmed.fetchedAtEpochMs) }
                    }
                    null
                }
                in 200..299 -> {
                    val body = response.bodyAsText()
                    val document = decode(body)
                    if (document == null) {
                        GenreConfigFetchError.INVALID_DOCUMENT
                    } else {
                        val fresh = CachedGenreConfig(body, response.headers[HttpHeaders.ETag], now())
                        store.writeCached(fresh)
                        _state.update { it.copy(document = document, fetchedAtEpochMs = fresh.fetchedAtEpochMs) }
                        null
                    }
                }
                401, 403 -> GenreConfigFetchError.UNAUTHORIZED
                else -> GenreConfigFetchError.HTTP
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(refreshing = false) }
            throw e
        } catch (e: Exception) {
            GenreConfigFetchError.NETWORK
        }
        _state.update { it.copy(refreshing = false, hasToken = true, lastError = error) }
        error
    }

    private val _homeLimit = MutableStateFlow<GenreLimit?>(null)

    /**
     * The active profile's Home limit as last resolved, for filters that
     * can't suspend (a Home row's See all page). Kept current while
     * [startPeriodicRefresh]'s scope runs.
     */
    val currentHomeLimit: GenreLimit? get() = _homeLimit.value

    /** Refreshes now and then every [intervalMs] until [scope] ends. */
    fun startPeriodicRefresh(scope: CoroutineScope, intervalMs: Long = GENRE_CONFIG_REFRESH_INTERVAL_MS): Job {
        scope.launch { limitFlow(GenreLimitSurface.HOME).collect { _homeLimit.value = it } }
        return scope.launch {
            while (isActive) {
                refresh()
                delay(intervalMs)
            }
        }
    }
}
