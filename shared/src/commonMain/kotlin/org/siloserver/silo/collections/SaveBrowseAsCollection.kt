package org.siloserver.silo.collections

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.network.apiv2.CatalogRuleGroupV2

/** A Smart collection's `query_definition`, in the server's catalog query shape. */
@Serializable
data class SmartQueryDefinition(
    @SerialName("library_ids") val libraryIds: List<Int>? = null,
    @SerialName("media_scope") val mediaScope: String? = null,
    val match: String = "all",
    val groups: List<SmartQueryGroup> = emptyList(),
    val sort: SmartQuerySort? = null,
)

@Serializable
data class SmartQueryGroup(val match: String, val rules: List<SmartQueryRule>)

@Serializable
data class SmartQueryRule(val field: String, val op: String, val value: JsonElement)

@Serializable
data class SmartQuerySort(val field: String, val order: String)

/**
 * `POST /api/v2/collections` for Save as collection: a private Smart
 * collection of the acting profile. Sharing and Home rows are set later in the
 * collection's editor, as on the web.
 */
@Serializable
data class CreateSmartCollectionRequest(
    val name: String,
    @SerialName("collection_type") val collectionType: String = "smart",
    @SerialName("is_shared") val isShared: Boolean = false,
    @SerialName("query_definition") val queryDefinition: SmartQueryDefinition,
    @SerialName("sort_config") val sortConfig: JsonObject = JsonObject(emptyMap()),
    @SerialName("include_in_server_collections") val includeInServerCollections: Boolean = false,
)

/**
 * The query a library's Browse shows, as a Smart collection stores it: the
 * same rule groups and match, scoped to [libraryId] (and the mixed-library
 * side in [mediaScope]), in the Browse order.
 */
fun browseSmartQueryDefinition(
    libraryId: Int,
    mediaScope: String?,
    groups: List<CatalogRuleGroupV2>,
    match: String?,
    sort: String?,
    order: String?,
): SmartQueryDefinition = SmartQueryDefinition(
    libraryIds = listOf(libraryId),
    mediaScope = mediaScope?.takeIf { it.isNotBlank() },
    match = match ?: "all",
    groups = groups
        .filter { it.rules.isNotEmpty() }
        .map { group -> SmartQueryGroup(group.match, group.rules.map { SmartQueryRule(it.field, it.op, it.value) }) },
    sort = sort?.takeIf { it.isNotBlank() }?.let { SmartQuerySort(it, order ?: "asc") },
)

/**
 * Whether the rules hold at least one complete rule. A Smart collection with
 * none would match every title, and the server refuses it.
 */
fun SmartQueryDefinition.hasCompleteRule(): Boolean =
    groups.any { group -> group.rules.any { it.field.isNotBlank() && it.op.isNotBlank() && it.value.isFilled() } }

private fun JsonElement.isFilled(): Boolean = when (this) {
    is JsonNull -> false
    is JsonPrimitive -> !isString || content.isNotBlank()
    is JsonArray -> isNotEmpty()
    is JsonObject -> isNotEmpty()
}

/**
 * A starting name from the rules a viewer is most likely to name a shelf by:
 * up to three genres, then the years. Empty when there are neither. The name
 * is the viewer's own text, saved as typed, so it is built from the rule
 * values and not translated (web `suggestedCollectionName`).
 */
fun suggestedCollectionName(genres: List<String>, yearFrom: Int?, yearTo: Int?): String {
    val parts = genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3).toMutableList()
    when {
        yearFrom != null && yearTo != null -> parts += if (yearFrom == yearTo) "$yearFrom" else "$yearFrom–$yearTo"
        yearFrom != null -> parts += "$yearFrom"
        yearTo != null -> parts += "$yearTo"
    }
    return parts.joinToString(" · ")
}
