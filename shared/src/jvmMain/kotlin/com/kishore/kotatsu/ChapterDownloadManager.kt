package com.kishore.kotatsu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class DownloadFormat(val label: String) {
    IMAGES("Folder of Images (Automatic)"),
    CBZ("CBZ Comic Archive (.cbz)")
}

enum class DownloadTaskStatus {
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    ERROR
}

data class MangaDownloadTask @OptIn(DirectParsersAccess::class) constructor(
    val mangaId: Long,
    val mangaTitle: String,
    val coverUrl: String?,
    val publicUrl: String,
    val sourceName: String,
    val totalChaptersToDownload: Int,
    val completedChapters: Int = 0,
    val currentChapterTitle: String = "",
    val currentChapterPage: Int = 0,
    val currentChapterTotalPages: Int = 0,
    val status: DownloadTaskStatus = DownloadTaskStatus.DOWNLOADING,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    // Saved for 1-click retry on failure
    val retryManga: Manga? = null,
    val retryChapters: List<MangaChapter> = emptyList(),
    val retrySource: MangaParserSource? = null,
    val retryFormat: DownloadFormat = DownloadFormat.IMAGES,
    val retryDestinationDir: File = File(System.getProperty("user.home"), ".kotatsu-desktop/downloads")
)
 {
    val overallProgress: Float
        get() {
            if (totalChaptersToDownload <= 0) return 0f
            val chapterFrac = if (currentChapterTotalPages > 0) {
                currentChapterPage.toFloat() / currentChapterTotalPages.toFloat()
            } else 0f
            return ((completedChapters + chapterFrac) / totalChaptersToDownload.toFloat()).coerceIn(0f, 1f)
        }
}

data class DownloadedMangaSummary(
    val mangaId: Long,
    val mangaTitle: String,
    val coverUrl: String?,
    val publicUrl: String,
    val sourceName: String,
    val downloadedChapterCount: Int,
    val updatedAt: Long
)

class ChapterDownloadManager(
    private val service: MangaService
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val defaultDownloadsDir: File = File(
        System.getProperty("user.home"),
        ".kotatsu-desktop/downloads"
    ).apply { mkdirs() }

    private val _tasks = MutableStateFlow<Map<Long, MangaDownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<Long, MangaDownloadTask>> = _tasks.asStateFlow()

    private val activeJobs = mutableMapOf<Long, Job>()
    private val pausedMangaIds = mutableSetOf<Long>()


    fun getChapterDir(mangaId: Long, chapterId: Long, baseDir: File = defaultDownloadsDir): File =
        File(File(baseDir, mangaId.toString()), chapterId.toString())

    fun getDownloadedPageFiles(mangaId: Long, chapterId: Long): List<File> {
        val dir = getChapterDir(mangaId, chapterId)
        val marker = File(dir, ".complete")
        if (!dir.exists() || !marker.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".cbz") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    fun isChapterDownloaded(mangaId: Long, chapterId: Long): Boolean {
        val dir = getChapterDir(mangaId, chapterId)
        return File(dir, ".complete").exists() ||
                (dir.parentFile?.listFiles()?.any { it.name.endsWith("_${chapterId}.cbz") } == true)
    }

    fun deleteDownloadedChapter(mangaId: Long, chapterId: Long) {
        val dir = getChapterDir(mangaId, chapterId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        dir.parentFile?.listFiles()
            ?.filter { it.name.endsWith("_${chapterId}.cbz") }
            ?.forEach { it.delete() }
    }

    fun getAllDownloadedManga(userStore: UserDataStore): List<DownloadedMangaSummary> {
        if (!defaultDownloadsDir.exists()) return emptyList()
        val summaries = mutableListOf<DownloadedMangaSummary>()

        val favMap = userStore.getFavorites().associateBy { it.id }
        val histMap = userStore.getHistory().associate { it.manga.id to it.manga }

        defaultDownloadsDir.listFiles()?.filter { it.isDirectory }?.forEach { mangaDir ->
            val mangaId = mangaDir.name.toLongOrNull() ?: return@forEach
            val completedDirs = mangaDir.listFiles()?.count {
                (it.isDirectory && File(it, ".complete").exists()) || it.name.endsWith(".cbz")
            } ?: 0

            if (completedDirs > 0) {
                val saved = favMap[mangaId] ?: histMap[mangaId]
                val existingTask = _tasks.value[mangaId]
                summaries.add(
                    DownloadedMangaSummary(
                        mangaId = mangaId,
                        mangaTitle = saved?.title ?: existingTask?.mangaTitle ?: "Manga #$mangaId",
                        coverUrl = saved?.coverUrl ?: existingTask?.coverUrl,
                        publicUrl = saved?.publicUrl ?: existingTask?.publicUrl ?: "",
                        sourceName = saved?.sourceName ?: existingTask?.sourceName ?: "",
                        downloadedChapterCount = completedDirs,
                        updatedAt = mangaDir.lastModified()
                    )
                )
            }
        }
        return summaries.sortedByDescending { it.updatedAt }
    }

    @OptIn(DirectParsersAccess::class)
    fun togglePause(mangaId: Long) {
        val current = _tasks.value[mangaId] ?: return
        if (current.status == DownloadTaskStatus.DOWNLOADING) {
            pausedMangaIds.add(mangaId)
            _tasks.value = _tasks.value + (mangaId to current.copy(status = DownloadTaskStatus.PAUSED))
        } else if (current.status == DownloadTaskStatus.PAUSED) {
            pausedMangaIds.remove(mangaId)
            _tasks.value = _tasks.value + (mangaId to current.copy(status = DownloadTaskStatus.DOWNLOADING))
        }
    }

    fun cancelDownload(mangaId: Long) {
        pausedMangaIds.remove(mangaId)
        activeJobs[mangaId]?.cancel()
        activeJobs.remove(mangaId)
        _tasks.value = _tasks.value - mangaId
    }


    @OptIn(DirectParsersAccess::class)
    fun enqueueChaptersDownload(
        manga: Manga,
        chaptersToDownload: List<MangaChapter>,
        source: MangaParserSource,
        format: DownloadFormat = DownloadFormat.IMAGES,
        destinationDir: File = defaultDownloadsDir,
        onChapterCompleted: () -> Unit = {}
    ) {
        if (chaptersToDownload.isEmpty()) return

        // Cancel any previous running job for this manga before starting the new batch
        activeJobs[manga.id]?.cancel()
        pausedMangaIds.remove(manga.id)

        val initialTask = MangaDownloadTask(
            mangaId = manga.id,
            mangaTitle = manga.title,
            coverUrl = manga.largeCoverUrl ?: manga.coverUrl,
            publicUrl = manga.publicUrl,
            sourceName = source.name,
            totalChaptersToDownload = chaptersToDownload.size,
            completedChapters = 0,
            currentChapterTitle = chaptersToDownload.first().title ?: "Chapter ${chaptersToDownload.first().number}",
            status = DownloadTaskStatus.DOWNLOADING,
            retryManga = manga,
            retryChapters = chaptersToDownload,
            retrySource = source,
            retryFormat = format,
            retryDestinationDir = destinationDir
        )

        _tasks.value = _tasks.value + (manga.id to initialTask)



        val job = managerScope.launch {
            try {
                val baseReferer = runCatching { service.getSourceBaseUrl(source) }.getOrDefault(manga.publicUrl)

                chaptersToDownload.forEachIndexed { chIndex, chapter ->
                    while (manga.id in pausedMangaIds) {
                        delay(300L)
                    }

                    val chTitle = chapter.title ?: "Chapter ${chapter.number}"
                    updateTask(manga.id) {
                        it.copy(
                            completedChapters = chIndex,
                            currentChapterTitle = chTitle,
                            currentChapterPage = 0,
                            currentChapterTotalPages = 0,
                            status = DownloadTaskStatus.DOWNLOADING,
                            errorMessage = null
                        )
                    }

                    downloadSingleChapterInternal(
                        manga = manga,
                        chapter = chapter,
                        source = source,
                        baseReferer = baseReferer,
                        format = format,
                        destinationDir = destinationDir
                    ) { pageIdx, totalPages ->
                        updateTask(manga.id) {
                            it.copy(
                                currentChapterPage = pageIdx + 1,
                                currentChapterTotalPages = totalPages
                            )
                        }
                    }

                    withContext(Dispatchers.Main) {
                        onChapterCompleted()
                    }
                }

                updateTask(manga.id) {
                    it.copy(
                        completedChapters = chaptersToDownload.size,
                        currentChapterPage = it.currentChapterTotalPages,
                        status = DownloadTaskStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val friendlyError = when {
                    e.message?.contains("429") == true -> "Too many requests (HTTP 429). Try again later."
                    else -> e.message ?: "Download error occurred"
                }
                updateTask(manga.id) {
                    it.copy(
                        status = DownloadTaskStatus.ERROR,
                        errorMessage = friendlyError,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            } finally {
                activeJobs.remove(manga.id)
            }
        }
        activeJobs[manga.id] = job
    }
    private inline fun updateTask(mangaId: Long, transform: (MangaDownloadTask) -> MangaDownloadTask) {
        val existing = _tasks.value[mangaId] ?: return
        _tasks.value = _tasks.value + (mangaId to transform(existing))
    }
    @OptIn(DirectParsersAccess::class)
    private suspend fun downloadSingleChapterInternal(
        manga: Manga,
        chapter: MangaChapter,
        source: MangaParserSource,
        baseReferer: String,
        format: DownloadFormat,
        destinationDir: File,
        onPageProgress: suspend (Int, Int) -> Unit
    ) {
        val chapterDir = getChapterDir(manga.id, chapter.id, destinationDir)
        if (chapterDir.exists()) {
            chapterDir.deleteRecursively()
        }
        chapterDir.mkdirs()
        val pages = service.getChapterPages(source, chapter)
        if (pages.isEmpty()) {
            throw IllegalStateException("Chapters list / pages must not be empty")
        }
        val downloadedFiles = mutableListOf<File>()
        pages.forEachIndexed { index, page ->
            while (manga.id in pausedMangaIds) {
                delay(300L)
            }
            onPageProgress(index, pages.size)
            val imageUrl = service.resolvePageImageUrl(source, page)
            val ext = imageUrl.substringAfterLast('.', "jpg")
                .substringBefore('?')
                .takeIf { it.length in 3..4 } ?: "jpg"
            val outFile = File(chapterDir, "%04d.%s".format(index + 1, ext))
            val request = Request.Builder()
                .url(imageUrl)
                .header("Referer", baseReferer)
                .build()
            service.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val bytes = response.body?.bytes()
                    ?: throw IllegalStateException("Empty image body")
                outFile.writeBytes(bytes)
                downloadedFiles.add(outFile)
            }
        }
        if (format == DownloadFormat.CBZ) {
            val safeTitle = (chapter.title ?: "Chapter_${chapter.number}")
                .replace(Regex("[^a-zA-Z0-9.-]"), "")
            val cbzFile = File(chapterDir.parentFile, "${safeTitle}_${chapter.id}.cbz")
            ZipOutputStream(cbzFile.outputStream().buffered()).use { zos ->
                downloadedFiles.forEach { imgFile ->
                    zos.putNextEntry(ZipEntry(imgFile.name))
                    zos.write(imgFile.readBytes())
                    zos.closeEntry()
                }
            }
            chapterDir.deleteRecursively()
        } else {
            File(chapterDir, ".complete").writeText("ok")
        }
    }

    @OptIn(DirectParsersAccess::class)
    fun retryDownload(mangaId: Long) {
        val task = _tasks.value[mangaId] ?: return
        val manga = task.retryManga ?: return
        val source = task.retrySource ?: return
        if (task.retryChapters.isEmpty()) return

        enqueueChaptersDownload(
            manga = manga,
            chaptersToDownload = task.retryChapters,
            source = source,
            format = task.retryFormat,
            destinationDir = task.retryDestinationDir
        )
    }

    fun deleteAllDownloadsForManga(mangaId: Long) {
        cancelDownload(mangaId)
        val mangaDir = File(defaultDownloadsDir, mangaId.toString())
        if (mangaDir.exists()) {
            mangaDir.deleteRecursively()
        }
    }

}