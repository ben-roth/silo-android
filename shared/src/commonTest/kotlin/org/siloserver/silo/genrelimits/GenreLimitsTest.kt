package org.siloserver.silo.genrelimits

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.network.apiv2.CatalogRuleGroupV2
import org.siloserver.silo.network.apiv2.CatalogRuleV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenreLimitsTest {
    private val document = GenreConfigDocument(
        version = 1,
        servers = mapOf(
            "https://Silo.Example.com" to GenreConfigServer(
                name = "Home",
                profiles = mapOf(
                    "kid" to GenreConfigProfile(allowedGenres = listOf("Animation", "Family")),
                    "teen" to GenreConfigProfile(allowedGenres = listOf("Comedy"), searchAllGenres = true),
                    "adult" to GenreConfigProfile(allowedGenres = emptyList()),
                ),
            ),
        ),
    )

    @Test
    fun originIsLowercasedWithoutPathOrDefaultPort() {
        assertEquals("https://silo.example.com", normalizeServerOrigin("HTTPS://Silo.Example.com:443/web/"))
        assertEquals("http://10.0.0.5:8096", normalizeServerOrigin("http://10.0.0.5:8096/api?x=1"))
        assertNull(normalizeServerOrigin("silo.example.com"))
    }

    @Test
    fun limitMatchesServerOriginThenProfile() {
        val kid = assertNotNull(document.limitFor("https://silo.example.com/", "kid"))
        assertEquals(listOf("Animation", "Family"), kid.allowedGenres)
        assertNull(document.limitFor("https://other.example.com", "kid"), "another server has no limit")
        assertNull(document.limitFor("https://silo.example.com", "nobody"), "a profile with no entry has no limit")
        assertNull(document.limitFor("https://silo.example.com", "adult"), "an empty list means no limit")
    }

    @Test
    fun searchAllGenresLiftsOnlySearch() {
        val teen = assertNotNull(document.limitFor("https://silo.example.com", "teen"))
        assertNull(teen.forSurface(GenreLimitSurface.SEARCH))
        assertNotNull(teen.forSurface(GenreLimitSurface.BROWSE))
        assertNotNull(teen.forSurface(GenreLimitSurface.HOME))
    }

    @Test
    fun titlesWithNoGenresAreHidden() {
        val kid = assertNotNull(document.limitFor("https://silo.example.com", "kid"))
        assertTrue(kid.allows(listOf("Drama", "family")))
        assertFalse(kid.allows(listOf("Horror")))
        assertFalse(kid.allows(emptyList()))
    }

    private fun rule(field: String, value: String) = CatalogRuleV2(field, "is", JsonPrimitive(value))

    @Test
    fun limitIsAddedAsAnAnyGroupUnderMatchAll() {
        val existing = listOf(CatalogRuleGroupV2("all", listOf(rule("year", "1999"))))
        val (groups, match) = applyGenreLimit(existing, "all", listOf("Animation", "Family"))
        assertEquals("all", match)
        assertEquals(2, groups.size)
        assertEquals("any", groups.last().match)
        assertEquals(listOf("Animation", "Family"), groups.last().rules.map { (it.value as JsonPrimitive).content })
    }

    @Test
    fun limitIsDistributedUnderMatchAnySoItNarrows() {
        // (studio=A) or (rating=G or rating=PG), limited to Animation/Family.
        val existing = listOf(
            CatalogRuleGroupV2("all", listOf(rule("studio", "A"))),
            CatalogRuleGroupV2("any", listOf(rule("content_rating", "G"), rule("content_rating", "PG"))),
        )
        val (groups, match) = applyGenreLimit(existing, "any", listOf("Animation", "Family"))
        assertEquals("any", match)
        // 3 branches x 2 genres, each an "all" that requires a genre.
        assertEquals(6, groups.size)
        assertTrue(groups.all { group -> group.match == "all" && group.rules.any { it.field == "genre" } })
    }

    @Test
    fun noLimitLeavesTheQueryAlone() {
        val existing = listOf(CatalogRuleGroupV2("any", listOf(rule("studio", "A"))))
        assertEquals(existing to "any", applyGenreLimit(existing, "any", emptyList()))
    }

    private class Identity(val url: String?, val profile: String?) : GenreLimitIdentity {
        override suspend fun serverUrl() = url
        override suspend fun profileId() = profile
        override val changes: Flow<Unit> = flowOf(Unit)
    }

    private class MemoryStore(var token: String? = "secret", var cached: CachedGenreConfig? = null) : GenreConfigStore {
        override fun readToken() = token
        override fun writeToken(token: String?) { this.token = token }
        override fun readCached() = cached
        override fun writeCached(cached: CachedGenreConfig) { this.cached = cached }
    }

    private val kidJson = """{"version":1,"servers":{"https://silo.example.com":{"profiles":{"kid":{"allowedGenres":["Animation"]}}}}}"""

    @Test
    fun aFailedRefreshKeepsTheCachedLimit() = runTest {
        val store = MemoryStore(cached = CachedGenreConfig(kidJson, "\"v1\"", 1L))
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
        val repository = GenreConfigRepository(client, store, Identity("https://silo.example.com", "kid"), now = { 99L })
        assertEquals(GenreConfigFetchError.HTTP, repository.refresh())
        assertEquals(listOf("Animation"), repository.limitFor(GenreLimitSurface.BROWSE)?.allowedGenres)
        assertEquals(1L, repository.state.value.fetchedAtEpochMs)
    }

    @Test
    fun refreshSendsTheTokenAndEtagAndKeepsTheCopyOn304() = runTest {
        val store = MemoryStore(cached = CachedGenreConfig(kidJson, "\"v1\"", 1L))
        var auth: String? = null
        var ifNoneMatch: String? = null
        val client = HttpClient(MockEngine { request ->
            auth = request.headers[HttpHeaders.Authorization]
            ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
            respond("", HttpStatusCode.NotModified)
        })
        val repository = GenreConfigRepository(client, store, Identity("https://silo.example.com", "kid"), now = { 99L })
        assertNull(repository.refresh())
        assertEquals("Bearer secret", auth)
        assertEquals("\"v1\"", ifNoneMatch)
        assertEquals(99L, repository.state.value.fetchedAtEpochMs)
        assertEquals(listOf("Animation"), repository.limitFor(GenreLimitSurface.HOME)?.allowedGenres)
    }

    @Test
    fun aNewDocumentReplacesTheCachedOne() = runTest {
        val store = MemoryStore(cached = CachedGenreConfig(kidJson, "\"v1\"", 1L))
        val updated = """{"version":2,"servers":{"https://silo.example.com":{"profiles":{"kid":{"allowedGenres":["Family"]}}}}}"""
        val client = HttpClient(MockEngine {
            respond(updated, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"v2\""))
        })
        val repository = GenreConfigRepository(client, store, Identity("https://silo.example.com", "kid"), now = { 5L })
        assertNull(repository.refresh())
        assertEquals(listOf("Family"), repository.limitFor(GenreLimitSurface.BROWSE)?.allowedGenres)
        assertEquals("\"v2\"", store.cached?.etag)
    }

    @Test
    fun anUnreadableDocumentKeepsTheCachedOne() = runTest {
        val store = MemoryStore(cached = CachedGenreConfig(kidJson, "\"v1\"", 1L))
        val client = HttpClient(MockEngine { respond("<html>oops</html>", HttpStatusCode.OK) })
        val repository = GenreConfigRepository(client, store, Identity("https://silo.example.com", "kid"))
        assertEquals(GenreConfigFetchError.INVALID_DOCUMENT, repository.refresh())
        assertEquals(listOf("Animation"), repository.limitFor(GenreLimitSurface.BROWSE)?.allowedGenres)
        assertEquals(kidJson, store.cached?.json)
    }
}
