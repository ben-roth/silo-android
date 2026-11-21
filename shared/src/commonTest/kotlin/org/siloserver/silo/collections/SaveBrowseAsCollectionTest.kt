package org.siloserver.silo.collections

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogQueryRule
import org.siloserver.silo.network.api.catalogRuleGroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveBrowseAsCollectionTest {
    @Test
    fun nameTakesUpToThreeGenresThenTheYears() {
        assertEquals("Comedy · Drama · Family · 1990–1999", suggestedCollectionName(listOf("Comedy", "Drama", "Family", "War"), 1990, 1999))
        assertEquals("Sci-Fi · 1985", suggestedCollectionName(listOf("Sci-Fi"), 1985, 1985))
        assertEquals("2001", suggestedCollectionName(emptyList(), null, 2001))
        assertEquals("", suggestedCollectionName(emptyList(), null, null))
    }

    @Test
    fun theSavedQueryIsTheBrowseQueryScopedToTheLibrary() {
        val groups = catalogRuleGroups(
            queryGroups = listOf(CatalogQueryGroup("any", listOf(CatalogQueryRule("genre", "contains", "Comedy")))),
            yearMin = 1990,
            yearMax = 1999,
        )
        val query = browseSmartQueryDefinition(7, "movie", groups, "all", "title", "asc")
        assertTrue(query.hasCompleteRule())
        val body = Json { encodeDefaults = true }.encodeToString(
            CreateSmartCollectionRequest.serializer(),
            CreateSmartCollectionRequest(name = "Comedy · 1990–1999", queryDefinition = query),
        )
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("smart", json["collection_type"]!!.jsonPrimitive.content)
        assertEquals("false", json["is_shared"]!!.jsonPrimitive.content)
        val definition = json["query_definition"]!!.jsonObject
        assertEquals(7, definition["library_ids"]!!.jsonArray.single().jsonPrimitive.content.toInt())
        assertEquals("movie", definition["media_scope"]!!.jsonPrimitive.content)
        assertEquals("title", definition["sort"]!!.jsonObject["field"]!!.jsonPrimitive.content)
        val year = definition["groups"]!!.jsonArray[1].jsonObject["rules"]!!.jsonArray.single().jsonObject
        assertEquals("between", year["op"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSortWithNoRuleCannotBeSaved() {
        val query = browseSmartQueryDefinition(7, null, catalogRuleGroups(emptyList()), null, "title", "asc")
        assertFalse(query.hasCompleteRule())
        val blank = browseSmartQueryDefinition(
            7, null,
            listOf(org.siloserver.silo.network.apiv2.CatalogRuleGroupV2("all", listOf(
                org.siloserver.silo.network.apiv2.CatalogRuleV2("genre", "contains", JsonPrimitive(" ")),
            ))),
            null, null, null,
        )
        assertFalse(blank.hasCompleteRule())
    }
}
