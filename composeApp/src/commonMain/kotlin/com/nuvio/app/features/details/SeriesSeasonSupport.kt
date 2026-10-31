package com.nuvio.app.features.details

internal const val SPECIALS_SEASON_NUMBER = 0

/**
 * The video that S1 E[episode] became on a title once listed as one long season, or null when the
 * title still has that episode.
 */
internal fun List<MetaVideo>.videoForSingleSeasonEpisode(episode: Int): MetaVideo? {
    if (any { it.season == 1 && it.episode == episode }) return null
    return filter { normalizeSeasonNumber(it.season) > SPECIALS_SEASON_NUMBER && it.episode != null }
        .sortedWith(metaVideoSeasonEpisodeComparator)
        .getOrNull(episode - 1)
}

internal val metaVideoSeasonEpisodeComparator: Comparator<MetaVideo> =
    compareBy<MetaVideo>(
        { seasonSortKey(it.season) },
        { it.episode ?: Int.MAX_VALUE },
        { it.released ?: "" },
        { it.title },
    )

internal fun normalizeSeasonNumber(seasonNumber: Int?): Int =
    if (seasonNumber == null || seasonNumber <= SPECIALS_SEASON_NUMBER) {
        SPECIALS_SEASON_NUMBER
    } else {
        seasonNumber
    }

internal fun seasonSortKey(seasonNumber: Int?): Int =
    if (seasonNumber == null || seasonNumber <= SPECIALS_SEASON_NUMBER) {
        Int.MAX_VALUE
    } else {
        seasonNumber
    }

internal fun preferredEpisodeNumberForSeason(
    displayedSeasonNumber: Int,
    preferredSeasonNumber: Int?,
    preferredEpisodeNumber: Int?,
): Int? = preferredEpisodeNumber.takeIf { displayedSeasonNumber == preferredSeasonNumber }

internal fun MetaDetails.groupedEpisodesForDisplay(): Map<Int, List<MetaVideo>> {
    val numberedEpisodes = videos.filter { it.season != null || it.episode != null }
    return when {
        numberedEpisodes.isNotEmpty() -> numberedEpisodes
            .sortedWith(metaVideoSeasonEpisodeComparator)
            .groupBy { normalizeSeasonNumber(it.season) }
        type != "series" && videos.isNotEmpty() -> mapOf(normalizeSeasonNumber(null) to videos)
        else -> emptyMap()
    }
}

// Long-running shows (Detective Conan, One Piece) list 1000+ episodes in a single season.
// Seasons above this size are split into ranges so a far episode is one tap away.
internal const val EPISODE_RANGE_SIZE = 50

internal data class EpisodeRange(
    val fromIndex: Int,
    val toIndex: Int,
    val label: String,
)

internal fun episodeRangesFor(episodes: List<MetaVideo>): List<EpisodeRange> {
    if (episodes.size <= EPISODE_RANGE_SIZE) return emptyList()
    return (episodes.indices step EPISODE_RANGE_SIZE).map { start ->
        val end = minOf(start + EPISODE_RANGE_SIZE, episodes.size)
        val first = episodes[start].episode ?: (start + 1)
        val last = episodes[end - 1].episode ?: end
        EpisodeRange(fromIndex = start, toIndex = end, label = "$first-$last")
    }
}

internal fun episodeRangeIndexFor(
    ranges: List<EpisodeRange>,
    episodes: List<MetaVideo>,
    episodeNumber: Int?,
): Int {
    if (ranges.isEmpty() || episodeNumber == null) return 0
    val episodeIndex = episodes.indexOfFirst { it.episode == episodeNumber }
    if (episodeIndex < 0) return 0
    return ranges.indexOfFirst { episodeIndex in it.fromIndex until it.toIndex }.coerceAtLeast(0)
}

internal fun List<MetaVideo>.inEpisodeRange(
    ranges: List<EpisodeRange>,
    rangeIndex: Int,
): List<MetaVideo> {
    val range = ranges.getOrNull(rangeIndex) ?: return this
    return subList(range.fromIndex, range.toIndex)
}
