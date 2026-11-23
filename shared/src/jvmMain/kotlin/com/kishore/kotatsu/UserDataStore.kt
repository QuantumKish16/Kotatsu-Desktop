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
data class AppSettings(
    val defaultLanguage: String = "en",
    val hideNsfw: Boolean = true,
    val defaultReaderModeName: String = "SINGLE",
    val defaultRtl: Boolean = true,
    val maxSearchSources: Int = 60,
    val darkTheme: Boolean = false
)

@Serializable
data class KotatsuBackupData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings = AppSettings(),
    val favorites: List<SavedManga> = emptyList(),
    val history: List<ReadingHistoryEntry> = emptyList()
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
}
