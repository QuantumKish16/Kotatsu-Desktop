package com.kishore.kotatsu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.*
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


// Holds search results for a single source that had matches
data class SourceSearchResult @OptIn(DirectParsersAccess::class) constructor(
    val source: MangaParserSource,
    val results: List<Manga>
)

class MangaService {
    private val context = DesktopMangaContext()
    @OptIn(DirectParsersAccess::class)
    private val parserCache = mutableMapOf<MangaParserSource, MangaParser>()



    private val bootstrapClient = OkHttpClient.Builder().build()

    private val cloudflareDns = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://1.1.1".toHttpUrl()) // Must be 1.1.1.1 to match bootstrap IP!
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1")
        )
        .includeIPv6(false)
        .build()

    // Smart hybrid DNS: uses Cloudflare DoH for MangaDex, and fast system DNS for everything else
    private val smartDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return if (hostname.contains("mangadex.org", ignoreCase = true)) {
                runCatching { cloudflareDns.lookup(hostname) }
                    .getOrElse { Dns.SYSTEM.lookup(hostname) }
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }


    private val cookieJar = object : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostCookies = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { newCookie ->
                hostCookies.removeAll { it.name == newCookie.name }
                hostCookies.add(newCookie)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val hostCookies = store[url.host] ?: return emptyList()
            hostCookies.removeAll { it.expiresAt < now }
            return hostCookies.toList()
        }
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(smartDns)
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val host = original.url.host
            val builder = original.newBuilder()

            // Always send a standard Chrome User-Agent so CDNs like planeptune.us don't return HTTP 403
            if (original.header("User-Agent") == null || original.header("User-Agent")!!.startsWith("okhttp")) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
            }

            // MangaDex CDN & API require https://mangadex.org as Referer
            if (host.contains("mangadex.org")) {
                builder.header("Referer", "https://mangadex.org")
            }
            // Note: Do NOT override Referer for other CDNs because MangaPageImage already sets the exact parser base URL!

            chain.proceed(builder.build())
        }
        .build()


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


    // Caches resolved direct image URLs so preloaded pages don't re-fetch HTML
    private val resolvedPageUrlCache = ConcurrentHashMap<MangaPage, String>()

    @OptIn(DirectParsersAccess::class)
    suspend fun resolvePageImageUrl(
        source: MangaParserSource,
        page: MangaPage
    ): String = withContext(Dispatchers.IO) {
        // 1. Return immediately if we already resolved this page's URL
        resolvedPageUrlCache[page]?.let { return@withContext it }

        // 2. Otherwise fetch from the parser, save it in the cache, and return it
        val resolved = getParser(source).getPageUrl(page)
        resolvedPageUrlCache[page] = resolved
        resolved
    }

    data class MangaUpdateResult @OptIn(DirectParsersAccess::class) constructor(
        val savedManga: SavedManga,
        val freshDetails: Manga,
        val source: MangaParserSource
    )

    @OptIn(DirectParsersAccess::class)
    fun checkFavoritesForUpdates(
        favorites: List<SavedManga>
    ): Flow<MangaUpdateResult> = channelFlow {
        val semaphore = Semaphore(permits = 5)

        for (saved in favorites) {
            val source = runCatching { MangaParserSource.valueOf(saved.sourceName) }.getOrNull() ?: continue
            val stubManga = saved.toKotatsuManga() ?: continue

            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val fresh = withTimeoutOrNull(10_000L) {
                            getMangaDetails(source, stubManga)
                        }
                        if (fresh != null) {
                            send(MangaUpdateResult(saved, fresh, source))
                        }
                    } catch (_: Exception) {
                        // Skip temporarily unreachable sources during batch check
                    }
                }
            }
        }
    }


}
