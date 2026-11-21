package org.siloserver.silo.genrelimits

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.network.apiv2.CatalogRuleGroupV2
import org.siloserver.silo.network.apiv2.CatalogRuleV2

/**
 * The genre config document a household keeps outside Silo:
 * `{version, updatedAt, servers: {"<origin>": {name, profiles: {"<profileId>": {...}}}}}`.
 * Unknown keys are ignored so the document can grow.
 */
@Serializable
data class GenreConfigDocument(
    val version: Int = 0,
    val updatedAt: String? = null,
    val servers: Map<String, GenreConfigServer> = emptyMap(),
)

@Serializable
data class GenreConfigServer(
    val name: String? = null,
    val profiles: Map<String, GenreConfigProfile> = emptyMap(),
)

@Serializable
data class GenreConfigProfile(
    val label: String? = null,
    val allowedGenres: List<String> = emptyList(),
    val searchAllGenres: Boolean = false,
)

/** Where a catalog read is shown; Search can be exempt from a profile's limit. */
enum class GenreLimitSurface { BROWSE, SEARCH, HOME }

/**
 * The genres one profile may see. A title shows when it has at least one of
 * [allowedGenres]; a title with no genres never shows.
 */
data class GenreLimit(
    val allowedGenres: List<String>,
    val searchAllGenres: Boolean = false,
) {
    private val allowedKeys = allowedGenres.map(::genreKey).toSet()

    /** The limit for [surface], or null when that surface shows every genre. */
    fun forSurface(surface: GenreLimitSurface): GenreLimit? =
        if (surface == GenreLimitSurface.SEARCH && searchAllGenres) null else this

    fun allows(genres: List<String>): Boolean = genres.any { genreKey(it) in allowedKeys }
}

private fun genreKey(genre: String): String = genre.trim().lowercase()

/**
 * `scheme://host[:port]`, lowercased, with no path, query or trailing slash,
 * and with a scheme's default port dropped. Null when [url] has no host.
 */
fun normalizeServerOrigin(url: String): String? {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    val rest = trimmed.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = (if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest)
        .substringAfterLast('@')
        .lowercase()
    if (authority.isEmpty()) return null
    val defaultPort = when (scheme) {
        "https" -> ":443"
        "http" -> ":80"
        else -> null
    }
    val hostPort = if (defaultPort != null && authority.endsWith(defaultPort) && !authority.endsWith("]")) {
        authority.removeSuffix(defaultPort)
    } else {
        authority
    }
    return "$scheme://$hostPort"
}

/**
 * The limit for [profileId] on the server at [serverUrl], or null when the
 * document has no entry for them or the entry lists no genres.
 */
fun GenreConfigDocument.limitFor(serverUrl: String?, profileId: String?): GenreLimit? {
    if (serverUrl == null || profileId.isNullOrBlank()) return null
    val origin = normalizeServerOrigin(serverUrl) ?: return null
    val server = servers.entries.firstOrNull { (key, _) -> normalizeServerOrigin(key) == origin }?.value
        ?: return null
    val profile = server.profiles[profileId] ?: return null
    val genres = profile.allowedGenres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (genres.isEmpty()) return null
    return GenreLimit(genres, profile.searchAllGenres)
}

/** The largest rule set a distributed "any" query may grow to before it stops distributing. */
private const val MAX_DISTRIBUTED_GROUPS = 400

/**
 * Narrows a catalog query to titles that have at least one of [allowedGenres].
 *
 * The server combines [groups] with [match] and has no single "any of these
 * genres" rule, so the limit is a group of `genre contains` rules joined by
 * "any". Under a top-level "all" that group is simply added. Under a top-level
 * "any", adding a group would widen the query instead, so the limit is
 * distributed into every existing group: `(G1 or G2) and L` becomes
 * `(G1 and g1) or (G1 and g2) or (G2 and g1) ...`. If that would grow past
 * [MAX_DISTRIBUTED_GROUPS], the query falls back to `match = all` with the
 * limit added, which is narrower than asked but never wider.
 */
fun applyGenreLimit(
    groups: List<CatalogRuleGroupV2>,
    match: String?,
    allowedGenres: List<String>,
): Pair<List<CatalogRuleGroupV2>, String?> {
    if (allowedGenres.isEmpty()) return groups to match
    val genreRules = allowedGenres.map { CatalogRuleV2("genre", "contains", JsonPrimitive(it)) }
    val limitGroup = CatalogRuleGroupV2("any", genreRules)
    val nonEmpty = groups.filter { it.rules.isNotEmpty() }
    if (match != "any" || nonEmpty.isEmpty()) return (nonEmpty + limitGroup) to match

    val distributed = buildList {
        for (group in nonEmpty) {
            // Each branch of the "any" is a conjunction of plain rules.
            val branches: List<List<CatalogRuleV2>> =
                if (group.match == "any") group.rules.map { listOf(it) } else listOf(group.rules)
            for (branch in branches) for (genre in genreRules) add(CatalogRuleGroupV2("all", branch + genre))
        }
    }
    if (distributed.size > MAX_DISTRIBUTED_GROUPS) return (nonEmpty + limitGroup) to "all"
    return distributed to "any"
}

/**
 * Rows of titles narrowed to [limit]: cards whose genres miss it (and titles
 * with none) are dropped, rows left empty are dropped, and rows of
 * collections are dropped, since a collection opens onto titles the limit
 * does not check. A row the server sent without items is kept for the
 * caller to resolve. No limit leaves the rows alone.
 */
fun List<org.siloserver.silo.model.section.ResolvedSection>.limitedTo(
    limit: GenreLimit?,
): List<org.siloserver.silo.model.section.ResolvedSection> {
    if (limit == null) return this
    return mapNotNull { section ->
        if (section.isCollectionRow) return@mapNotNull null
        if (section.items.isEmpty()) return@mapNotNull section
        val items = section.items.filter { limit.allows(it.genres) }
        if (items.isEmpty()) null else section.copy(items = items)
    }
}
