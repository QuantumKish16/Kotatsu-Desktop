package com.kishore.kotatsu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder

// Holds search results for a single source that had matches
data class SourceSearchResult @OptIn(DirectParsersAccess::class) constructor(
    val source: MangaParserSource,
    val results: List<Manga>
)

class MangaService {
    private val context = DesktopMangaContext()
    @OptIn(DirectParsersAccess::class)
    private val parserCache = mutableMapOf<MangaParserSource, MangaParser>()

    val httpClient: OkHttpClient
        get() = context.httpClient

    @OptIn(DirectParsersAccess::class)
    fun getSourceBaseUrl(source: MangaParserSource): String {
        val domain = getParser(source).domain
        return "https://$domain/"
    }

    private val nsfwKeywords = listOf(
        "hentai", "18+", "nsfw", "adult", "smut", "erotica", "porn", "xxx", "doujin", "ecchi"
    )

    @OptIn(DirectParsersAccess::class)
    private fun isNsfwSource(source: MangaParserSource): Boolean {
        if (source.contentType == ContentType.HENTAI) return true
        val combined = "${source.name} ${source.title}".lowercase()
        return nsfwKeywords.any { keyword -> combined.contains(keyword) }
    }

    private fun isNsfwManga(manga: Manga): Boolean {
        if (manga.isNsfw || manga.contentRating == ContentRating.ADULT) return true
        val titleLower = manga.title.lowercase()
        if (nsfwKeywords.any { titleLower.contains(it) }) return true
        return manga.tags.any { tag ->
            val tagLower = tag.title.lowercase()
            nsfwKeywords.any { tagLower.contains(it) }
        }
    }
    @OptIn(DirectParsersAccess::class)
    fun getFilteredSources(
        language: String = "en",
        hideNsfw: Boolean = true
    ): List<MangaParserSource> {
        val recommended = listOf(
            MangaParserSource.MANGATOWN,
            MangaParserSource.MANGAFOXFULL,
            MangaParserSource.MANGADEX,
            MangaParserSource.WEEBCENTRAL
        )

        val validEntries = MangaParserSource.entries.filter { source ->
            val nsfwOk = !hideNsfw || !isNsfwSource(source)
            val langOk = language.equals("all", ignoreCase = true) ||
                    source.locale.equals(language, ignoreCase = true) ||
                    source.locale.isBlank()
            nsfwOk && langOk
        }

        val top = recommended.filter { it in validEntries }
        val rest = validEntries.filterNot { it in top }.sortedBy { it.title }
        return top + rest
    }

    @OptIn(DirectParsersAccess::class)
    private fun getParser(source: MangaParserSource): MangaParser {
        return parserCache.getOrPut(source) {
            context.createParser(source)
        }
    }

    @OptIn(DirectParsersAccess::class)
    suspend fun getSourceFilterOptions(source: MangaParserSource): MangaListFilterOptions =
        withContext(Dispatchers.IO) {
            getParser(source).getFilterOptions()
        }

    @OptIn(DirectParsersAccess::class)
    suspend fun searchManga(
        source: MangaParserSource,
        query: String,
        selectedTags: Set<MangaTag> = emptySet(),
        sortOrder: SortOrder = SortOrder.POPULARITY,
        hideNsfw: Boolean = true
    ): List<Manga> = withContext(Dispatchers.IO) {
        val parser = getParser(source)
        val filter = MangaListFilter(
            query = query.ifBlank { null },
            tags = selectedTags,
            contentRating = if (hideNsfw) setOf(ContentRating.SAFE, ContentRating.SUGGESTIVE) else emptySet()
        )
        val results = parser.getList(offset = 0, order = sortOrder, filter = filter)

        return@withContext if (hideNsfw) {
            results.filterNot { isNsfwManga(it) }
        } else {
            results
        }

    }

    // Searches multiple sources in parallel (max 5 at a time, 8s timeout per source)
    // and streams back ONLY sources that found at least 1 manga!
    @OptIn(DirectParsersAccess::class)
    fun searchAcrossSources(
        sourcesToSearch: List<MangaParserSource>,
        query: String,
        sortOrder: SortOrder = SortOrder.POPULARITY,
        hideNsfw: Boolean = true
    ): Flow<SourceSearchResult> = channelFlow {
        val semaphore = Semaphore(permits = 10)

        for (source in sourcesToSearch) {
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val matches = withTimeoutOrNull(6_000L) {
                            searchManga(
                                source = source,
                                query = query,
                                sortOrder = sortOrder,
                                hideNsfw = hideNsfw
                            )
                        }.orEmpty()

                        if (matches.isNotEmpty()) {
                            send(SourceSearchResult(source = source, results = matches))
                        }
                    } catch (_: Exception) {
                        // Skip dead/Cloudflare sites silently
                    }
                }
            }
        }
    }


    @OptIn(DirectParsersAccess::class)
    suspend fun getMangaDetails(
        source: MangaParserSource,
        manga: Manga
    ): Manga = withContext(Dispatchers.IO) {
        getParser(source).getDetails(manga)
    }

    // Step 1: Get the list of MangaPage objects for a clicked chapter
    @OptIn(DirectParsersAccess::class)
    suspend fun getChapterPages(
        source: MangaParserSource,
        chapter: MangaChapter
    ): List<MangaPage> = withContext(Dispatchers.IO) {
        getParser(source).getPages(chapter)
    }

    // Step 2: Resolve the direct image URL for a single MangaPage
    @OptIn(DirectParsersAccess::class)
    suspend fun resolvePageImageUrl(
        source: MangaParserSource,
        page: MangaPage
    ): String = withContext(Dispatchers.IO) {
        getParser(source).getPageUrl(page)
    }

}
