package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Fills in episodes of titles with an anime id the way Harbor does: AniZip for titles, images and
 * numbering, and animefillerlist.com for filler episodes. Episode ids are left alone, because
 * stream addons look anime up by their own numbering (kitsu:210:1000).
 */
internal object AnimeEpisodeEnrichment {
    private const val ANI_ZIP_URL = "https://api.ani.zip/mappings"
    private const val FILLER_LIST_URL = "https://www.animefillerlist.com/shows"
    private const val ANI_ZIP_TIMEOUT_MS = 3_000L
    private const val FILLER_TIMEOUT_MS = 5_000L
    private const val FILLER_SECTION_START =
        "<div class=\"filler\"><span class=\"Label\">Filler Episodes:</span><span class=\"Episodes\">"

    // AniZip gives Hunter x Hunter (2011) episodes 59-78 the identity of other episodes.
    private const val HUNTER_X_HUNTER_KITSU_ID = 6448

    private val log = Logger.withTag("AnimeEpisodeEnrichment")
    private val json = Json { ignoreUnknownKeys = true }
    private val animeIdPattern = Regex("^(kitsu|mal|anilist|anidb):(\\d+)")
    private val htmlTagPattern = Regex("<[^>]+>")
    private val slugIgnoredPattern = Regex("['’.:,!?]")
    private val slugSeparatorPattern = Regex("[^a-z0-9]+")
    private val yearPattern = Regex("\\d{4}")
    private val mappingsByQuery = mutableMapOf<String, JsonObject>()
    private val fillersByMetaId = mutableMapOf<String, Set<Int>>()

    private val episodeWords = listOf(
        "episode", "ep", "الحلقة", "الحلقه", "حلقة", "حلقه", "قسمت", "اپیزود", "اپيسود", "قسط",
        "серия", "эпизод", "серія", "епізод", "епизод", "епизода", "серыя", "эпізод", "επεισόδιο",
        "एपिसोड", "भाग", "ตอน", "פרק", "에피소드", "episodio", "capítulo", "capitulo", "cap",
        "épisode", "folge", "puntata", "bölüm", "bolum", "odcinek", "aflevering", "avsnitt", "tập",
    )
    private val genericNamePunctuation = Regex("[\\s\\-–—:.,()\\[\\]'\"،]")
    private val digitsOnly = Regex("^\\d*$")
    private val numberedName = Regex("^\\d+[\\s.,-]*$")
    private val cjkNumberedName = Regex("^第\\s*\\d+\\s*[話话集]$|^\\d+\\s*화$")

    suspend fun enrich(meta: MetaDetails, language: String): MetaDetails {
        val match = animeIdPattern.find(meta.id) ?: return meta
        if (meta.videos.isEmpty() || meta.videos.any { it.streams.isNotEmpty() }) return meta
        val (scheme, id) = match.destructured

        val mapping = fetchMapping("${scheme}_id=$id")
        val videos = mapping?.let { mergeEpisodes(meta.videos, it, language) } ?: meta.videos
        val fillers = fetchFillers(meta, mapping)
        return meta.copy(
            videos = videos.map { video ->
                val number = video.absoluteEpisode ?: video.episode
                if (number != null && number in fillers) video.copy(isFiller = true) else video
            },
        )
    }

    private suspend fun fetchMapping(query: String): JsonObject? {
        mappingsByQuery[query]?.let { return it }
        return try {
            withTimeoutOrNull(ANI_ZIP_TIMEOUT_MS) {
                json.parseToJsonElement(httpGetText("$ANI_ZIP_URL?$query")) as? JsonObject
            }?.also { mappingsByQuery[query] = it }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.w(e) { "No AniZip mapping for $query" }
            null
        }
    }

    private fun mergeEpisodes(
        videos: List<MetaVideo>,
        mapping: JsonObject,
        language: String,
    ): List<MetaVideo> {
        val episodes = mapping["episodes"] as? JsonObject ?: return videos
        val isHunterXHunter = (mapping["mappings"] as? JsonObject)?.number("kitsu_id") == HUNTER_X_HUNTER_KITSU_ID
        val languageBase = language.trim().substringBefore('-').lowercase()
        val localized = languageBase.isNotEmpty() && languageBase != "en"
        val absoluteOwners = mutableMapOf<Int, Int>()
        videos.forEach { video ->
            video.absoluteEpisode?.let { absoluteOwners[it] = (absoluteOwners[it] ?: 0) + 1 }
        }

        return videos.map { video ->
            val number = video.episode ?: return@map video
            if (video.season == 0) return@map video
            val az = episodes[number.toString()] as? JsonObject ?: return@map video

            val titles = az["titles"] as? JsonObject ?: az["title"] as? JsonObject
            val englishTitle = titles?.text("en")?.takeUnless(::isGenericEpisodeName)
            val title = if (localized) {
                val localTitle = titles?.text(language.trim()) ?: titles?.text(languageBase)
                localTitle?.takeUnless(::isGenericEpisodeName) ?: englishTitle ?: video.title
            } else if (video.title.isBlank() || video.title == "Episode $number") {
                englishTitle ?: video.title
            } else {
                video.title
            }
            val overview = video.overview?.takeIf(String::isNotBlank)
                ?: az.text("overview").takeUnless { localized }
            val merged = video.copy(
                title = title,
                overview = overview,
                runtime = video.runtime ?: az.number("runtime")?.takeIf { it > 0 },
                isFiller = video.isFiller || az.flag("filler"),
            )

            val absolute = az.number("absoluteEpisodeNumber")
            if (isHunterXHunter && absolute != null && absolute != number) return@map merged

            val azSeason = az.number("seasonNumber")
            val azEpisode = az.number("episodeNumber")
            val episodeSeason = video.imdbSeason ?: video.season ?: 1
            val episodeNumber = video.imdbEpisode ?: number
            val shifted = (azSeason == 0 && episodeSeason >= 1) ||
                (azSeason != null && azSeason >= 1 && azSeason == episodeSeason &&
                    azEpisode != null && azEpisode != episodeNumber)
            val ownedElsewhere = absolute != null &&
                (absoluteOwners[absolute] ?: 0) > (if (video.absoluteEpisode == absolute) 1 else 0)
            val applyAbsolute = absolute != null && absolute > 0 && !shifted && !ownedElsewhere
            if (applyAbsolute) absoluteOwners[absolute!!] = (absoluteOwners[absolute] ?: 0) + 1
            val hasProviderNumbering = !shifted && azSeason != null && azSeason >= 0 && azEpisode != null

            merged.copy(
                thumbnail = az.text("image") ?: merged.thumbnail,
                released = merged.released ?: az.text("airDateUtc") ?: az.text("airDate") ?: az.text("airdate"),
                rating = merged.rating ?: az.text("rating")?.toDoubleOrNull()?.takeIf { it > 0.0 },
                absoluteEpisode = if (applyAbsolute) absolute else merged.absoluteEpisode,
                imdbSeason = merged.imdbSeason ?: azSeason.takeIf { hasProviderNumbering },
                imdbEpisode = merged.imdbEpisode ?: azEpisode.takeIf { hasProviderNumbering },
            )
        }
    }

    private suspend fun fetchFillers(meta: MetaDetails, mapping: JsonObject?): Set<Int> {
        fillersByMetaId[meta.id]?.let { return it }
        val titles = mapping?.get("titles") as? JsonObject
        val year = meta.releaseInfo?.let { yearPattern.find(it)?.value }
        val slugs = listOfNotNull(meta.name, titles?.text("en"), titles?.text("x-jat"))
            .map(::slugify)
            .filter(String::isNotEmpty)
            .distinct()
            .flatMap { slug -> listOfNotNull(year?.let { "$slug-$it" }, "$slug-tv", slug) }
        return withTimeoutOrNull(FILLER_TIMEOUT_MS) {
            val page = slugs.firstNotNullOfOrNull { slug -> fetchFillerPage(slug) }
            (page?.let(::parseFillers) ?: emptySet()).also { fillersByMetaId[meta.id] = it }
        } ?: emptySet()
    }

    private suspend fun fetchFillerPage(slug: String): String? =
        try {
            httpGetText("$FILLER_LIST_URL/$slug")
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    private fun parseFillers(html: String): Set<Int> {
        if (FILLER_SECTION_START !in html) return emptySet()
        return html.substringAfter(FILLER_SECTION_START)
            .substringBefore("</span>")
            .replace(htmlTagPattern, "")
            .split(',')
            .flatMap { token ->
                val parts = token.trim().split('-').map { it.trim().toIntOrNull() }
                val first = parts.firstOrNull()
                val last = parts.lastOrNull()
                if (parts.size > 2 || first == null || last == null) emptyList<Int>() else (first..last).filter { it > 0 }
            }
            .toSet()
    }

    private fun slugify(title: String): String =
        title.lowercase()
            .replace(slugIgnoredPattern, "")
            .replace(slugSeparatorPattern, "-")
            .trim('-')

    private fun isGenericEpisodeName(text: String): Boolean {
        val name = text.trim()
        if (name.isEmpty()) return false
        if (numberedName.matches(name) || cjkNumberedName.matches(name)) return true
        val lower = name.lowercase()
        return episodeWords.any { word ->
            word in lower && digitsOnly.matches(lower.replaceFirst(word, "").replace(genericNamePunctuation, ""))
        }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.number(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.flag(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.booleanOrNull == true
}
