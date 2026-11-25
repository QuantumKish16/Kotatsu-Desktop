package com.kishore.kotatsu

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.zip.ZipInputStream


@Serializable
data class SavedManga(
    val id: Long,
    val title: String,
    val altTitles: Set<String> = emptySet(),
    val url: String,
    val publicUrl: String,
    val rating: Float = -1f,
    val isNsfw: Boolean = false,
    val coverUrl: String? = null,
    val largeCoverUrl: String? = null,
    val authors: Set<String> = emptySet(),
    val stateName: String? = null,
    val sourceName: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    // Converts this saved JSON record back into a Kotatsu Manga object so getDetails() works seamlessly!
    @OptIn(DirectParsersAccess::class)
    fun toKotatsuManga(): Manga? {
        val source = runCatching { MangaParserSource.valueOf(sourceName) }.getOrNull() ?: return null
        val state = stateName?.let { runCatching { MangaState.valueOf(it) }.getOrNull() }
        return Manga(
            id = id,
            title = title,
            altTitles = altTitles,
            url = url,
            publicUrl = publicUrl,
            rating = rating,
            contentRating = null,
            coverUrl = coverUrl,
            largeCoverUrl = largeCoverUrl,
            tags = emptySet(),
            state = state,
            authors = authors,
            source = source
        )
    }

    companion object {
        @OptIn(DirectParsersAccess::class)
        fun fromKotatsuManga(manga: Manga, source: MangaParserSource): SavedManga {
            return SavedManga(
                id = manga.id,
                title = manga.title,
                altTitles = manga.altTitles,
                url = manga.url,
                publicUrl = manga.publicUrl,
                rating = manga.rating,
                isNsfw = manga.isNsfw,
                coverUrl = manga.coverUrl,
                largeCoverUrl = manga.largeCoverUrl,
                authors = manga.authors,
                stateName = manga.state?.name,
                sourceName = source.name
            )
        }
    }
}

@Serializable
data class ReadingHistoryEntry(
    val manga: SavedManga,
    val lastChapterId: Long,
    val lastChapterTitle: String,
    val lastChapterNumber: Float,
    val lastPageIndex: Int = 0,
    val readChapterIds: Set<Long> = emptySet(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class CachedChapter(
    val id: Long,
    val title: String? = null,
    val number: Float = 0f,
    val volume: Int = 0,
    val url: String,
    val scanlator: String? = null,
    val uploadDate: Long = 0L,
    val branch: String? = null,
    val sourceName: String
) {
    @OptIn(DirectParsersAccess::class)
    fun toKotatsuChapter(): MangaChapter? {
        val src = runCatching { MangaParserSource.valueOf(sourceName) }.getOrNull() ?: return null
        return MangaChapter(
            id = id,
            title = title,
            number = number,
            volume = volume,
            url = url,
            scanlator = scanlator,
            uploadDate = uploadDate,
            branch = branch,
            source = src
        )
    }

    companion object {
        @OptIn(DirectParsersAccess::class)
        fun fromKotatsuChapter(chapter: MangaChapter, source: MangaParserSource): CachedChapter {
            return CachedChapter(
                id = chapter.id,
                title = chapter.title,
                number = chapter.number,
                volume = chapter.volume,
                url = chapter.url,
                scanlator = chapter.scanlator,
                uploadDate = chapter.uploadDate,
                branch = chapter.branch,
                sourceName = source.name
            )
        }
    }
}

@Serializable
data class CachedMangaDetails(
    val mangaId: Long,
    val description: String? = null,
    val chapters: List<CachedChapter> = emptyList(),
    val newChaptersCount: Int = 0,
    val lastCheckedAt: Long = System.currentTimeMillis()
)


@Serializable
data class AppSettings(
    val defaultLanguage: String = "en",
    val hideNsfw: Boolean = true,
    val defaultReaderModeName: String = "WEBTOON",
    val defaultRtl: Boolean = true,
    val maxSearchSources: Int = 60,
    val darkTheme: Boolean = false,
    val preloadPagesCount: Int = 3,
    val incognitoMode: Boolean = false,
    val compactGrid: Boolean = false
)


@Serializable
data class KotatsuBackupData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings = AppSettings(),
    val favorites: List<SavedManga> = emptyList(),
    val history: List<ReadingHistoryEntry> = emptyList(),
    val cachedDetails: Map<Long, CachedMangaDetails> = emptyMap()
)


class UserDataStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Active save file stored in C:\Users\<YourName>\.kotatsu-desktop\userdata.json
    private val appDir = File(System.getProperty("user.home"), ".kotatsu-desktop").apply { mkdirs() }
    private val dataFile = File(appDir, "userdata.json")

    private var currentData: KotatsuBackupData = loadFromDisk()

    fun getSettings(): AppSettings = currentData.settings

    fun updateSettings(newSettings: AppSettings) {
        currentData = currentData.copy(settings = newSettings)
        saveToDisk()
    }

    fun clearHistory() {
        currentData = currentData.copy(history = emptyList())
        saveToDisk()
    }

    fun getStoragePath(): String = dataFile.absolutePath

    private fun loadFromDisk(): KotatsuBackupData {
        if (!dataFile.exists()) return KotatsuBackupData()
        return runCatching {
            json.decodeFromString<KotatsuBackupData>(dataFile.readText())
        }.getOrDefault(KotatsuBackupData())
    }

    private fun saveToDisk() {
        runCatching {
            dataFile.writeText(json.encodeToString(currentData))
        }.onFailure { it.printStackTrace() }
    }

    fun getFavorites(): List<SavedManga> = currentData.favorites.sortedByDescending { it.addedAt }

    fun getHistory(): List<ReadingHistoryEntry> = currentData.history.sortedByDescending { it.updatedAt }

    fun isFavorite(mangaId: Long): Boolean = currentData.favorites.any { it.id == mangaId }

    fun getHistoryForManga(mangaId: Long): ReadingHistoryEntry? =
        currentData.history.find { it.manga.id == mangaId }

    @OptIn(DirectParsersAccess::class)
    fun toggleFavorite(manga: Manga, source: MangaParserSource): Boolean {
        val exists = isFavorite(manga.id)
        val updatedFavorites = if (exists) {
            currentData.favorites.filterNot { it.id == manga.id }
        } else {
            listOf(SavedManga.fromKotatsuManga(manga, source)) + currentData.favorites
        }
        currentData = currentData.copy(favorites = updatedFavorites)
        saveToDisk()
        return !exists
    }

    @OptIn(DirectParsersAccess::class)
    fun recordReadingProgress(
        manga: Manga,
        source: MangaParserSource,
        chapter: MangaChapter,
        pageIndex: Int
    ) {
        val existing = getHistoryForManga(manga.id)
        val updatedReadChapters = (existing?.readChapterIds ?: emptySet()) + chapter.id
        val newEntry = ReadingHistoryEntry(
            manga = SavedManga.fromKotatsuManga(manga, source),
            lastChapterId = chapter.id,
            lastChapterTitle = chapter.title ?: "Chapter ${chapter.number}",
            lastChapterNumber = chapter.number,
            lastPageIndex = pageIndex,
            readChapterIds = updatedReadChapters,
            updatedAt = System.currentTimeMillis()
        )
        val otherEntries = currentData.history.filterNot { it.manga.id == manga.id }
        currentData = currentData.copy(history = listOf(newEntry) + otherEntries)
        saveToDisk()
    }

    // --- BACKUP & RESTORE ---

    fun exportBackupTo(targetFile: File) {
        val exportPayload = currentData.copy(exportedAt = System.currentTimeMillis())
        targetFile.writeText(json.encodeToString(exportPayload))
    }

    // Restores & merges a backup JSON file without losing existing newer progress
    fun importBackupFrom(backupFile: File): Pair<Int, Int> {
        val imported = json.decodeFromString<KotatsuBackupData>(backupFile.readText())
        // Merge Favorites by ID (keep existing + add new from backup)
        val mergedFavoritesMap = LinkedHashMap<Long, SavedManga>()
        imported.favorites.forEach { mergedFavoritesMap[it.id] = it }
        currentData.favorites.forEach { mergedFavoritesMap[it.id] = it }

        // Merge History by Manga ID (keep whichever has the newer updatedAt timestamp + combine readChapterIds)
        val mergedHistoryMap = LinkedHashMap<Long, ReadingHistoryEntry>()
        imported.history.forEach { mergedHistoryMap[it.manga.id] = it }
        currentData.history.forEach { local ->
            val fromBackup = mergedHistoryMap[local.manga.id]
            if (fromBackup == null) {
                mergedHistoryMap[local.manga.id] = local
            } else {
                val combinedReadIds = local.readChapterIds + fromBackup.readChapterIds
                val newer = if (local.updatedAt >= fromBackup.updatedAt) local else fromBackup
                mergedHistoryMap[local.manga.id] = newer.copy(readChapterIds = combinedReadIds)
            }
        }

        currentData = KotatsuBackupData(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            favorites = mergedFavoritesMap.values.toList(),
            history = mergedHistoryMap.values.toList()
        )

        saveToDisk()
        return mergedFavoritesMap.size to mergedHistoryMap.size

    }

    fun getCachedDetails(mangaId: Long): CachedMangaDetails? = currentData.cachedDetails[mangaId]

    fun clearNewChaptersBadge(mangaId: Long) {
        val existing = currentData.cachedDetails[mangaId] ?: return
        if (existing.newChaptersCount > 0) {
            val updated = currentData.cachedDetails + (mangaId to existing.copy(newChaptersCount = 0))
            currentData = currentData.copy(cachedDetails = updated)
            saveToDisk()
        }
    }

    /**
     * Saves fresh chapters for a manga and returns how many brand-new chapters were detected
     * compared to the previously cached list.
     */
    @OptIn(DirectParsersAccess::class)
    fun updateCachedDetails(
        manga: Manga,
        source: MangaParserSource,
        freshChapters: List<MangaChapter>,
        isBackgroundUpdateCheck: Boolean = false
    ): Int {
        val existing = currentData.cachedDetails[manga.id]
        val cachedList = freshChapters.map { CachedChapter.fromKotatsuChapter(it, source) }

        val newlyFoundCount = if (existing != null && existing.chapters.isNotEmpty()) {
            val oldIds = existing.chapters.map { it.id }.toSet()
            freshChapters.count { it.id !in oldIds }
        } else {
            0
        }

        val badgeCount = if (isBackgroundUpdateCheck) {
            (existing?.newChaptersCount ?: 0) + newlyFoundCount
        } else {
            0 // User is actively viewing this manga right now, so clear the unread badge
        }

        val newRecord = CachedMangaDetails(
            mangaId = manga.id,
            description = manga.description ?: existing?.description,
            chapters = cachedList,
            newChaptersCount = badgeCount,
            lastCheckedAt = System.currentTimeMillis()
        )

        currentData = currentData.copy(
            cachedDetails = currentData.cachedDetails + (manga.id to newRecord)
        )
        saveToDisk()
        return newlyFoundCount
    }

    fun removeFavorite(mangaId: Long) {
        currentData = currentData.copy(
            favorites = currentData.favorites.filterNot { it.id == mangaId }
        )
        saveToDisk()
    }

    fun removeFromHistory(mangaId: Long) {
        currentData = currentData.copy(
            history = currentData.history.filterNot { it.manga.id == mangaId }
        )
        saveToDisk()
    }

    fun resetReadChapters(mangaId: Long) {
        val existing = getHistoryForManga(mangaId) ?: return
        val updatedEntry = existing.copy(
            lastPageIndex = 0,
            readChapterIds = emptySet(),
            updatedAt = System.currentTimeMillis()
        )
        val others = currentData.history.filterNot { it.manga.id == mangaId }
        currentData = currentData.copy(history = listOf(updatedEntry) + others)
        saveToDisk()
    }

    private fun parseAndroidMangaObject(obj: JsonObject): SavedManga? {
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val publicUrl = obj["public_url"]?.jsonPrimitive?.contentOrNull
            ?: obj["publicUrl"]?.jsonPrimitive?.contentOrNull
            ?: url
        val coverUrl = obj["cover_url"]?.jsonPrimitive?.contentOrNull
            ?: obj["coverUrl"]?.jsonPrimitive?.contentOrNull
        val sourceName = obj["source"]?.jsonPrimitive?.contentOrNull ?: return null
        val author = obj["author"]?.jsonPrimitive?.contentOrNull
        val isNsfw = obj["nsfw"]?.jsonPrimitive?.booleanOrNull
            ?: (obj["content_rating"]?.jsonPrimitive?.contentOrNull == "ADULT")

        return SavedManga(
            id = id,
            title = title,
            url = url,
            publicUrl = publicUrl,
            coverUrl = coverUrl,
            sourceName = sourceName,
            authors = listOfNotNull(author).filter { it.isNotBlank() }.toSet(),
            isNsfw = isNsfw
        )
    }

    /**
     * Imports favorites and reading history from an official Android Kotatsu `.bk.zip` backup file,
     * merging them safely with existing desktop data.
     */
    fun importKotatsuAndroidZip(zipFile: File): Pair<Int, Int> {
        var favouritesJsonText: String? = null
        var historyJsonText: String? = null

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name.substringAfterLast('/').substringBefore('.')
                    when (entryName.lowercase()) {
                        "favourites", "favorites" -> {
                            favouritesJsonText = zis.readBytes().toString(Charsets.UTF_8)
                        }
                        "history" -> {
                            historyJsonText = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val importedFavorites = mutableListOf<SavedManga>()
        favouritesJsonText?.let { raw ->
            val root: JsonElement = json.parseToJsonElement(raw)
            if (root is JsonArray) {
                for (item in root) {
                    val itemObj = item.jsonObject
                    val mangaObj = itemObj["manga"]?.jsonObject ?: itemObj
                    parseAndroidMangaObject(mangaObj)?.let { importedFavorites.add(it) }
                }
            }
        }

        val importedHistory = mutableListOf<ReadingHistoryEntry>()
        historyJsonText?.let { raw ->
            val root: JsonElement = json.parseToJsonElement(raw)
            if (root is JsonArray) {
                for (item in root) {
                    val itemObj = item.jsonObject
                    val mangaObj = itemObj["manga"]?.jsonObject ?: continue
                    val savedManga = parseAndroidMangaObject(mangaObj) ?: continue
                    val chapterId = itemObj["chapter_id"]?.jsonPrimitive?.longOrNull
                        ?: itemObj["chapterId"]?.jsonPrimitive?.longOrNull
                        ?: 0L
                    val pageIndex = itemObj["page"]?.jsonPrimitive?.intOrNull ?: 0
                    val updatedAt = itemObj["updated_at"]?.jsonPrimitive?.longOrNull
                        ?: itemObj["updatedAt"]?.jsonPrimitive?.longOrNull
                        ?: System.currentTimeMillis()

                    importedHistory.add(
                        ReadingHistoryEntry(
                            manga = savedManga,
                            lastChapterId = chapterId,
                            lastChapterTitle = if (chapterId != 0L) "Chapter (Imported)" else "Started",
                            lastChapterNumber = 0f,
                            lastPageIndex = pageIndex,
                            readChapterIds = if (chapterId != 0L) setOf(chapterId) else emptySet(),
                            updatedAt = updatedAt
                        )
                    )
                }
            }
        }

        // Merge with existing desktop Favorites & History without duplicates
        val existingFavIds = currentData.favorites.map { it.id }.toSet()
        val mergedFavorites = currentData.favorites + importedFavorites.filter { it.id !in existingFavIds }

        val existingHistIds = currentData.history.map { it.manga.id }.toSet()
        val mergedHistory = (currentData.history + importedHistory.filter { it.manga.id !in existingHistIds })
            .sortedByDescending { it.updatedAt }

        currentData = currentData.copy(
            favorites = mergedFavorites,
            history = mergedHistory
        )
        saveToDisk()

        return importedFavorites.size to importedHistory.size
    }



}
