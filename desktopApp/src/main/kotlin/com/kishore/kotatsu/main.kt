package com.kishore.kotatsu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.size.Size
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private enum class AppTab {
    HISTORY, FAVORITES, BROWSE, DOWNLOADS, STATISTICS, SETTINGS
}

private enum class ReaderMode(val label: String) {
    WEBTOON("Webtoon Scroll"), SINGLE("Single Page"), DOUBLE("Two-Page Spread")
}

private fun calculateMangaProgress(
    totalChapters: Int,
    readChapterIds: Set<Long>,
    lastChapterId: Long?,
    orderedChapterIds: List<Long>
): Pair<Int, Float> {
    if (totalChapters <= 0) return 0 to 0f
    val reachedCount = if (lastChapterId != null) {
        val idx = orderedChapterIds.indexOf(lastChapterId)
        if (idx >= 0) idx + 1 else 0
    } else 0
    val effectiveRead = maxOf(readChapterIds.size, reachedCount).coerceIn(0, totalChapters)
    val fraction = (effectiveRead.toFloat() / totalChapters.toFloat()).coerceIn(0f, 1f)
    return effectiveRead to fraction
}

private fun extractChapterNumber(number: Float, title: String?): Float {
    if (number > 0f) return number
    if (title != null) {
        val match = Regex("(\\d+(?:\\.\\d+)?)").find(title)
        if (match != null) {
            return match.groupValues[1].toFloatOrNull() ?: 0f
        }
    }
    return 0f
}

private fun getEffectiveReadChapterIds(
    orderedFromFirstToLast: List<Pair<Long, Float>>, // (chapterId, chapterNumber)
    explicitReadIds: Set<Long>,
    lastChapterId: Long?
): Set<Long> {
    if (orderedFromFirstToLast.isEmpty()) return explicitReadIds
    if (lastChapterId == null) return explicitReadIds

    val reachedIndex = orderedFromFirstToLast.indexOfFirst { it.first == lastChapterId }
    if (reachedIndex < 0) return explicitReadIds

    val impliedIds = orderedFromFirstToLast.take(reachedIndex + 1).map { it.first }
    return explicitReadIds + impliedIds
}

private fun computeSeriesProgress(
    chaptersIdAndNum: List<Pair<Long, Float>>,
    explicitReadIds: Set<Long>,
    lastChapterId: Long?
): Triple<Set<Long>, Int, Float> {
    if (chaptersIdAndNum.isEmpty()) return Triple(explicitReadIds, 0, 0f)
    val oldestToNewest = if (
        chaptersIdAndNum.size >= 2 &&
        chaptersIdAndNum.first().second > chaptersIdAndNum.last().second
    ) {
        chaptersIdAndNum.asReversed()
    } else {
        chaptersIdAndNum
    }
    val effectiveIds = getEffectiveReadChapterIds(oldestToNewest, explicitReadIds, lastChapterId)
    val count = chaptersIdAndNum.count { it.first in effectiveIds }
    val fraction = (count.toFloat() / chaptersIdAndNum.size.toFloat()).coerceIn(0f, 1f)
    return Triple(effectiveIds, count, fraction)
}


private fun pickBackupFile(isSave: Boolean, titleOverride: String? = null): File? {
    val dialog = FileDialog(
        null as Frame?,
        titleOverride ?: if (isSave) "Export Kotatsu Backup (.json)" else "Import Backup (.json or .bk.zip)",
        if (isSave) FileDialog.SAVE else FileDialog.LOAD
    )
    if (isSave) {
        dialog.file = "kotatsu_backup_${LocalDate.now()}.json"
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file)
}


private fun cleanHtmlDescription(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</p>"), "\n\n").replace(Regex("<[^>]*>"), "")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
        .replace("&nbsp;", " ").replace(Regex("\n{3,}"), "\n\n").trim()
}

private fun formatHistoryDateHeader(timestampMillis: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM"))
    }
}

@Composable
private fun CoverProgressBadge(
    progressFraction: Float, modifier: Modifier = Modifier
) {
    val percent = (progressFraction.coerceIn(0f, 1f) * 100).toInt()
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(50),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (percent >= 100) "✓" else "$percent%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}


fun main() = application {
    val userStore = remember { UserDataStore() }
    var appSettings by remember { mutableStateOf(userStore.getSettings()) }
    val windowState = rememberWindowState(
        width = 1280.dp,
        height = 820.dp
    )
    val appIcon = painterResource("app_icon.png")

    // On Windows with undecorated = true, Maximized gives true borderless fullscreen without AWT exclusive-mode lockups!
    val isBorderlessFullscreen = windowState.placement == WindowPlacement.Maximized ||
            windowState.placement == WindowPlacement.Fullscreen

    fun toggleBorderlessFullscreen() {
        windowState.placement = if (isBorderlessFullscreen) {
            WindowPlacement.Floating
        } else {
            WindowPlacement.Maximized
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        undecorated = true,
        icon = appIcon,
        title = "Kotatsu Desktop",
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                when {
                    event.key == Key.F11 -> {
                        toggleBorderlessFullscreen()
                        true
                    }
                    event.key == Key.Escape && isBorderlessFullscreen -> {
                        windowState.placement = WindowPlacement.Floating
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    ) {
        val rootFocusRequester = remember { FocusRequester() }
        LaunchedEffect(isBorderlessFullscreen) {
            runCatching { rootFocusRequester.requestFocus() }
        }

        MaterialTheme(
            colorScheme = if (appSettings.darkTheme) darkColorScheme() else lightColorScheme()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(rootFocusRequester)
                    .focusable(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Custom Dark Title Bar (shown in windowed mode; hidden in borderless fullscreen)
                    if (!isBorderlessFullscreen) {
                        WindowDraggableArea {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Image(
                                    painter = appIcon,
                                    contentDescription = "Kotatsu Logo",
                                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                                )
                                Text(
                                    text = "Kotatsu Desktop",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { windowState.isMinimized = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Rounded.Remove, contentDescription = "Minimize", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { toggleBorderlessFullscreen() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Rounded.Fullscreen, contentDescription = "Fullscreen (F11)", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = ::exitApplication,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        KotatsuApp(
                            userStore = userStore,
                            appSettings = appSettings,
                            isFullscreen = isBorderlessFullscreen,
                            onToggleFullscreen = { toggleBorderlessFullscreen() },
                            onSettingsChanged = { updated ->
                                userStore.updateSettings(updated)
                                appSettings = updated
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(DirectParsersAccess::class)
@Composable
fun KotatsuApp(
    userStore: UserDataStore,
    appSettings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {}
) {
    val service = remember { MangaService() }
    val downloadManager = remember { ChapterDownloadManager(service) }


    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { service.httpClient }))
        }.build()
    }

    // History is now the default first page
    var activeTab by remember { mutableStateOf(AppTab.HISTORY) }
    var favoritesList by remember { mutableStateOf(userStore.getFavorites()) }
    var historyList by remember { mutableStateOf(userStore.getHistory()) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Global top search bar state
    var globalSearchQuery by remember { mutableStateOf("Naruto") }
    var searchExecutionTick by remember { mutableStateOf(0) }

    fun refreshUserData() {
        favoritesList = userStore.getFavorites()
        historyList = userStore.getHistory()
    }

    var lockedSource by remember { mutableStateOf<MangaParserSource?>(null) }
    var selectedMangaPair by remember { mutableStateOf<Pair<Manga, MangaParserSource>?>(null) }
    var readerState by remember { mutableStateOf<Triple<Manga, List<MangaChapter>, Int>?>(null) }
    var initialReaderPage by remember { mutableStateOf(0) }

    val currentReader = readerState
    val currentPair = selectedMangaPair

    when {
        currentReader != null && currentPair != null -> {
            val (manga, allChapters, chapterIndex) = currentReader
            MangaReaderScreen(
                manga = manga,
                chapters = allChapters,
                currentChapterIndex = chapterIndex,
                initialPageIndex = initialReaderPage,
                appSettings = appSettings,
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
                source = currentPair.second,
                service = service,
                downloadManager = downloadManager,
                onProgressUpdated = { chapter, pageIdx ->
                    // Respect Incognito Mode: do not record history when active
                    if (!appSettings.incognitoMode) {
                        userStore.recordReadingProgress(manga, currentPair.second, chapter, pageIdx)
                        refreshUserData()
                    }
                },
                onSelectChapterIndex = { newIndex ->
                    initialReaderPage = 0
                    readerState = Triple(manga, allChapters, newIndex)
                },
                onBack = {
                    initialReaderPage = 0
                    readerState = null
                })
        }

        currentPair != null -> {
            MangaDetailsScreen(
                initialManga = currentPair.first,
                source = currentPair.second,
                service = service,
                userStore = userStore,
                downloadManager = downloadManager,
                onFavoriteToggled = { refreshUserData() },
                onChapterClick = { loadedManga, allChapters, index, startPage ->
                    initialReaderPage = startPage
                    readerState = Triple(loadedManga, allChapters, index)
                },
                onBack = { selectedMangaPair = null })
        }

        else -> {
            Row(modifier = Modifier.fillMaxSize()) {
                // Sleek Left Navigation Rail (like your screenshot!)
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(modifier = Modifier.height(12.dp))

                    NavigationRailItem(
                        selected = activeTab == AppTab.HISTORY,
                        onClick = {
                            refreshUserData()
                            activeTab = AppTab.HISTORY
                        },
                        icon = { Icon(Icons.Rounded.History, contentDescription = "History") },
                        label = { Text("History") },
                        alwaysShowLabel = false
                    )

                    NavigationRailItem(
                        selected = activeTab == AppTab.FAVORITES, onClick = {
                            refreshUserData()
                            activeTab = AppTab.FAVORITES
                        }, icon = {
                            Icon(
                                if (activeTab == AppTab.FAVORITES) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Favorites"
                            )
                        }, label = { Text("Favorites") }, alwaysShowLabel = false
                    )

                    NavigationRailItem(
                        selected = activeTab == AppTab.BROWSE,
                        onClick = { activeTab = AppTab.BROWSE },
                        icon = { Icon(Icons.Rounded.Explore, contentDescription = "Browse") },
                        label = { Text("Browse") },
                        alwaysShowLabel = false
                    )

                    NavigationRailItem(
                        selected = activeTab == AppTab.DOWNLOADS,
                        onClick = { activeTab = AppTab.DOWNLOADS },
                        icon = { Icon(Icons.Rounded.Download, contentDescription = "Downloads") },
                        label = { Text("Downloads") },
                        alwaysShowLabel = false
                    )


                    NavigationRailItem(
                        selected = activeTab == AppTab.STATISTICS,
                        onClick = {
                            refreshUserData()
                            activeTab = AppTab.STATISTICS
                        },
                        icon = { Icon(Icons.Rounded.BarChart, contentDescription = "Statistics") },
                        label = { Text("Stats") },
                        alwaysShowLabel = false
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Pinned at the bottom just like the gear icon in your screenshot!
                    NavigationRailItem(
                        selected = activeTab == AppTab.SETTINGS,
                        onClick = { activeTab = AppTab.SETTINGS },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        alwaysShowLabel = false
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Right Side: Clean Top Search Bar + Active Screen Content
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Surface(tonalElevation = 1.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = globalSearchQuery,
                                onValueChange = { globalSearchQuery = it },
                                placeholder = { Text("Search manga across sources...") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        activeTab = AppTab.BROWSE
                                        searchExecutionTick++
                                        true
                                    } else {
                                        false
                                    }
                                })

                            if (appSettings.incognitoMode) {
                                AssistChip(
                                    onClick = { onSettingsChanged(appSettings.copy(incognitoMode = false)) },
                                    label = { Text("Incognito") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.VisibilityOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    })
                            }

                            // Clean 3-Dot Vector Icon Button
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                                }

                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                    modifier = Modifier.width(230.dp)
                                ) {
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                                contentDescription = null
                                            )
                                        },
                                        text = { Text(if (isFullscreen) "Exit Fullscreen (F11)" else "Fullscreen (F11)") },
                                        onClick = {
                                            onToggleFullscreen()
                                            menuExpanded = false
                                        }
                                    )

                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
                                        text = { Text(if (appSettings.incognitoMode) "Incognito: ON" else "Incognito: OFF") },
                                        onClick = {
                                            onSettingsChanged(appSettings.copy(incognitoMode = !appSettings.incognitoMode))
                                            menuExpanded = false
                                        })
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                if (appSettings.compactGrid) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                                                contentDescription = null
                                            )
                                        },
                                        text = { Text(if (appSettings.compactGrid) "Switch to List View" else "Switch to Grid View") },
                                        onClick = {
                                            onSettingsChanged(appSettings.copy(compactGrid = !appSettings.compactGrid))
                                            menuExpanded = false
                                        })
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                                        text = { Text("Clear History") },
                                        onClick = {
                                            userStore.clearHistory()
                                            refreshUserData()
                                            menuExpanded = false
                                        })
                                }
                            }
                        }
                    }

                    // Active Tab Screen
                    when (activeTab) {
                        AppTab.HISTORY -> ReadingHistoryScreen(
                            history = historyList,
                            isCompactGrid = appSettings.compactGrid,
                            userStore = userStore,
                            onDataChanged = { refreshUserData() },
                            onOpenEntry = { entry ->
                                val manga = entry.manga.toKotatsuManga()
                                val src = runCatching { MangaParserSource.valueOf(entry.manga.sourceName) }.getOrNull()
                                if (manga != null && src != null) {
                                    selectedMangaPair = manga to src
                                }
                            })

                        AppTab.FAVORITES -> SavedLibraryScreen(
                            title = "Saved Favorites",
                            emptyMessage = "No favorites yet. Search a manga above and click 'Add to Favorites'!",
                            items = favoritesList,
                            isCompactGrid = appSettings.compactGrid,
                            service = service,
                            userStore = userStore,
                            onDataChanged = { refreshUserData() },
                            onOpenManga = { saved ->
                                val manga = saved.toKotatsuManga()
                                val src = runCatching { MangaParserSource.valueOf(saved.sourceName) }.getOrNull()
                                if (manga != null && src != null) {
                                    selectedMangaPair = manga to src
                                }
                            })

                        AppTab.BROWSE -> MangaSearchScreen(
                            service = service,
                            appSettings = appSettings,
                            onSettingsChanged = onSettingsChanged,
                            searchQuery = globalSearchQuery,
                            searchTriggerTick = searchExecutionTick,
                            lockedSource = lockedSource,
                            onLockedSourceChanged = { newSource -> lockedSource = newSource },
                            onMangaClick = { clickedManga, mangaSource ->
                                selectedMangaPair = clickedManga to mangaSource
                            })

                        AppTab.STATISTICS -> StatisticsScreen(
                            favorites = favoritesList,
                            history = historyList,
                            onBackToHistory = { activeTab = AppTab.HISTORY })

                        AppTab.SETTINGS -> SettingsScreen(
                            appSettings = appSettings,
                            userStore = userStore,
                            onSettingsChanged = onSettingsChanged,
                            onDataRestoredOrCleared = { refreshUserData() })

                        AppTab.DOWNLOADS -> DownloadsScreen(
                            downloadManager = downloadManager, userStore = userStore, onOpenMangaById = { mangaId ->
                                val saved = userStore.getFavorites().find { it.id == mangaId } ?: userStore.getHistory()
                                    .find { it.manga.id == mangaId }?.manga
                                val manga = saved?.toKotatsuManga()
                                val src =
                                    saved?.let { runCatching { MangaParserSource.valueOf(it.sourceName) }.getOrNull() }
                                if (manga != null && src != null) {
                                    selectedMangaPair = manga to src
                                }
                            })

                    }
                }
            }
        }
    }
}

@Composable
fun StatisticsScreen(
    favorites: List<SavedManga>, history: List<ReadingHistoryEntry>, onBackToHistory: () -> Unit
) {
    val totalChaptersRead = remember(history) { history.sumOf { it.readChapterIds.size } }
    val topSources = remember(favorites, history) {
        val counts = mutableMapOf<String, Int>()
        favorites.forEach { counts[it.sourceName] = (counts[it.sourceName] ?: 0) + 1 }
        history.forEach { counts[it.manga.sourceName] = (counts[it.manga.sourceName] ?: 0) + 1 }
        counts.entries.sortedByDescending { it.value }.take(5)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Reading Statistics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onBackToHistory) {
                Text("← Back to History")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Saved Favorites",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "${favorites.size}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Series Started",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "${history.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                    )
                }
            }
            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Chapters Read",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "$totalChaptersRead",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Top Sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (topSources.isEmpty()) {
                    Text(
                        "Start reading or favouring manga to see source stats!",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    topSources.forEach { (sourceName, count) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(sourceName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "$count title(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    appSettings: AppSettings,
    userStore: UserDataStore,
    onSettingsChanged: (AppSettings) -> Unit,
    onDataRestoredOrCleared: () -> Unit
) {
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Settings & Configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
// 1. Appearance & Privacy Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance & Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Dark Mode", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Switch between light and dark desktop theme",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(
                        checked = appSettings.darkTheme,
                        onCheckedChange = { onSettingsChanged(appSettings.copy(darkTheme = it)) })
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Incognito Mode", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Pause reading history tracking while reading chapters",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(
                        checked = appSettings.incognitoMode,
                        onCheckedChange = { onSettingsChanged(appSettings.copy(incognitoMode = it)) })
                }
            }
        }
// 2. Search & Content Filters Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Search & Content Filters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hide NSFW / Adult Content", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Filter out adult sources, Hentai categories, and NSFW-tagged titles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(
                        checked = appSettings.hideNsfw,
                        onCheckedChange = { onSettingsChanged(appSettings.copy(hideNsfw = it)) })
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Default Source Language", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "en" to "English (EN)",
                            "all" to "All Languages",
                            "ja" to "Japanese (JA)",
                            "ko" to "Korean (KO)"
                        ).forEach { (code, label) ->
                            FilterChip(
                                selected = appSettings.defaultLanguage == code,
                                onClick = { onSettingsChanged(appSettings.copy(defaultLanguage = code)) },
                                label = { Text(label) })
                        }
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Global Search Scope (Max Sources Scanned)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Higher values search more sites in 'All Sources' mode, while lower values finish faster",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 100, 150).forEach { count ->
                            FilterChip(
                                selected = appSettings.maxSearchSources == count,
                                onClick = { onSettingsChanged(appSettings.copy(maxSearchSources = count)) },
                                label = { Text("$count Sources") })
                        }
                    }
                }
            }
        }
// 3. Reader Defaults Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Reader Defaults", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Default Reading Mode", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderMode.entries.forEach { mode ->
                            FilterChip(
                                selected = appSettings.defaultReaderModeName == mode.name,
                                onClick = { onSettingsChanged(appSettings.copy(defaultReaderModeName = mode.name)) },
                                label = { Text(mode.label) })
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Right-to-Left (RTL) Spread Direction", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Recommended for Japanese manga (like One Piece) in Two-Page Spread mode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(
                        checked = appSettings.defaultRtl,
                        onCheckedChange = { onSettingsChanged(appSettings.copy(defaultRtl = it)) })
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Background Page Preloading", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Silently downloads upcoming pages in the background for instant page turns",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Off", 2 to "2 Pages", 3 to "3 Pages", 5 to "5 Pages").forEach { (count, label) ->
                            FilterChip(
                                selected = appSettings.preloadPagesCount == count,
                                onClick = { onSettingsChanged(appSettings.copy(preloadPagesCount = count)) },
                                label = { Text(label) })
                        }
                    }
                }
            }
        }
// 4. Backup, Restore & Storage Section
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val file = pickBackupFile(isSave = true)
                    if (file != null) {
                        runCatching { userStore.exportBackupTo(file) }.onSuccess {
                            statusMessage = "Backup exported successfully to ${file.absolutePath}"
                        }.onFailure { statusMessage = "Export failed: ${it.message}" }
                    }
                }) {
                Text("Export Desktop Backup (.json)")
            }

            OutlinedButton(
                onClick = {
                    val file = pickBackupFile(
                        isSave = false, titleOverride = "Select Desktop (.json) or Android (.bk.zip) Backup"
                    )
                    if (file != null) {
                        val result = if (file.name.endsWith(".zip", ignoreCase = true)) {
                            runCatching { userStore.importKotatsuAndroidZip(file) }
                        } else {
                            runCatching { userStore.importBackupFrom(file) }
                        }
                        result.onSuccess { (favCount, histCount) ->
                            onDataRestoredOrCleared()
                            statusMessage = "Imported & merged $favCount favorites and $histCount history entries!"
                        }.onFailure { statusMessage = "Import failed: ${it.message}" }
                    }
                }) {
                Text("Import Backup (.json / Android .bk.zip)")
            }

            OutlinedButton(
                onClick = {
                    userStore.clearHistory()
                    onDataRestoredOrCleared()
                    statusMessage = "Reading history cleared."
                }) {
                Text("Clear History")
            }
        }

    }
}

@OptIn(DirectParsersAccess::class)
@Composable
fun SourceSelectorDropdown(
    sources: List<MangaParserSource>, lockedSource: MangaParserSource?, onSourceSelected: (MangaParserSource?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    val filteredSources = remember(sources, filterText) {
        if (filterText.isBlank()) {
            sources.take(50)
        } else {
            sources.filter {
                it.name.contains(filterText, ignoreCase = true) || it.title.contains(filterText, ignoreCase = true)
            }.take(50)
        }
    }
    Box {
        OutlinedButton(
            onClick = {
                filterText = ""
                expanded = true
            }, modifier = Modifier.height(44.dp)
        ) {
            val label = lockedSource?.title ?: "All Sources"
            Text(
                text = "Source: $label ▼", maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(280.dp).heightIn(max = 420.dp)
        ) {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = { Text("Filter sources (${sources.size})...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            DropdownMenuItem(text = {
                Column {
                    Text(
                        text = "All Sources (Global Search)",
                        fontWeight = if (lockedSource == null) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Searches multiple sources in parallel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }, onClick = {
                onSourceSelected(null)
                expanded = false
            })
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            filteredSources.forEach { source ->
                DropdownMenuItem(text = {
                    Column {
                        Text(
                            text = source.title,
                            fontWeight = if (source == lockedSource) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = "${source.locale.ifBlank { "MULTI" }.uppercase()} • ${source.contentType.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }, onClick = {
                    onSourceSelected(source)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun MangaCoverImage(
    coverUrl: String?, refererUrl: String, contentDescription: String, modifier: Modifier = Modifier
) {
    val platformContext = LocalPlatformContext.current
    val imageRequest = remember(coverUrl, refererUrl) {
        ImageRequest.Builder(platformContext).data(coverUrl).httpHeaders(
            NetworkHeaders.Builder().set("Referer", refererUrl).build()
        ).build()
    }
    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    )
}

@OptIn(DirectParsersAccess::class)
@Composable
fun MangaSearchScreen(
    service: MangaService,
    appSettings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    searchQuery: String,
    searchTriggerTick: Int,
    lockedSource: MangaParserSource?,
    onLockedSourceChanged: (MangaParserSource?) -> Unit,
    onMangaClick: (Manga, MangaParserSource) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedLanguage by remember(appSettings.defaultLanguage) { mutableStateOf(appSettings.defaultLanguage) }
    val hideNsfw = appSettings.hideNsfw
    var selectedSort by remember { mutableStateOf(SortOrder.POPULARITY) }
    var selectedTags by remember { mutableStateOf<Set<MangaTag>>(emptySet()) }
    var filterOptions by remember { mutableStateOf<MangaListFilterOptions?>(null) }
    val availableSources = remember(selectedLanguage, hideNsfw) {
        service.getFilteredSources(language = selectedLanguage, hideNsfw = hideNsfw)
    }
    var multiSourceResults by remember { mutableStateOf<Map<MangaParserSource, List<Manga>>>(emptyMap()) }
    var viewedSource by remember { mutableStateOf(lockedSource) }
    var statusText by remember { mutableStateOf("Ready to search") }
    var isLoading by remember { mutableStateOf(false) }
    var activeSearchJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(viewedSource) {
        selectedTags = emptySet()
        val target = viewedSource
        filterOptions = if (target != null) {
            try {
                service.getSourceFilterOptions(target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }
    fun triggerGlobalSearch() {
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            isLoading = true
            multiSourceResults = emptyMap()
            viewedSource = null
            val targets = availableSources.take(appSettings.maxSearchSources)
            statusText = "Searching ${targets.size} sources for \"$searchQuery\"..."
            try {
                service.searchAcrossSources(
                    sourcesToSearch = targets, query = searchQuery, sortOrder = selectedSort, hideNsfw = hideNsfw
                ).collect { hit ->
                    val wasEmpty = multiSourceResults.isEmpty()
                    multiSourceResults = multiSourceResults + (hit.source to hit.results)
                    if (wasEmpty) {
                        viewedSource = hit.source
                    }
                    statusText = "Found matches in ${multiSourceResults.size} source(s)..."
                }
                statusText = if (multiSourceResults.isEmpty()) {
                    "No results found across ${targets.size} sources."
                } else {
                    "Done! Found results in ${multiSourceResults.size} source(s)."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusText = "Search error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun triggerSingleSourceSearch(
        targetSource: MangaParserSource, tagsToUse: Set<MangaTag> = selectedTags, sortToUse: SortOrder = selectedSort
    ) {
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            isLoading = true
            statusText = "Searching ${targetSource.title}..."
            try {
                val results = service.searchManga(
                    source = targetSource,
                    query = searchQuery,
                    selectedTags = tagsToUse,
                    sortOrder = sortToUse,
                    hideNsfw = hideNsfw
                )
                multiSourceResults = mapOf(targetSource to results)
                viewedSource = targetSource
                statusText = "Found ${results.size} results on ${targetSource.title}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                multiSourceResults = emptyMap()
                statusText = "Error on ${targetSource.title}: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun executeSearch() {
        if (lockedSource == null) {
            triggerGlobalSearch()
        } else {
            triggerSingleSourceSearch(lockedSource)
        }
    }
// Trigger search automatically whenever the user submits the top search bar
    LaunchedEffect(searchTriggerTick) {
        if (searchTriggerTick > 0) {
            executeSearch()
        }
    }
    val currentSource = viewedSource
    val displayedManga = if (currentSource != null) multiSourceResults[currentSource].orEmpty() else emptyList()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
// Filter & Source Bar (Search input lives in the top header!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SourceSelectorDropdown(
                sources = availableSources, lockedSource = lockedSource, onSourceSelected = { newSelection ->
                    onLockedSourceChanged(newSelection)
                    if (newSelection == null) {
                        if (searchQuery.isNotBlank()) triggerGlobalSearch()
                    } else {
                        triggerSingleSourceSearch(targetSource = newSelection, tagsToUse = emptySet())
                    }
                })
            Text("Lang:", style = MaterialTheme.typography.labelLarge)
            listOf("en" to "EN", "all" to "ALL", "ja" to "JA", "ko" to "KO").forEach { (code, label) ->
                FilterChip(selected = selectedLanguage == code, onClick = {
                    selectedLanguage = code
                    onSettingsChanged(appSettings.copy(defaultLanguage = code))
                }, label = { Text(label) })
            }
            Spacer(modifier = Modifier.weight(1f))
            listOf(SortOrder.POPULARITY to "Popular", SortOrder.UPDATED to "Latest").forEach { (order, label) ->
                FilterChip(selected = selectedSort == order, onClick = {
                    selectedSort = order
                    executeSearch()
                }, label = { Text(label) })
            }
        }
        if (multiSourceResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            val sourcesListState = rememberLazyListState()
            val entries = remember(multiSourceResults) { multiSourceResults.entries.toList() }
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    state = sourcesListState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        Text(
                            text = "Matched Sources (${multiSourceResults.size}):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(entries) { (source, list) ->
                        FilterChip(
                            selected = source == viewedSource,
                            onClick = { viewedSource = source },
                            label = { Text("${source.title} (${list.size})") })
                    }
                }
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(sourcesListState),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                )
            }
        }
        val tags = filterOptions?.availableTags?.toList().orEmpty()
        if (tags.isNotEmpty() && currentSource != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val genresListState = rememberLazyListState()
            val visibleTags = remember(tags) { tags.take(30) }
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    state = genresListState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        Text("Genres (${currentSource.title}):", style = MaterialTheme.typography.labelMedium)
                    }
                    items(visibleTags) { tag ->
                        val isSelected = tag in selectedTags
                        FilterChip(selected = isSelected, onClick = {
                            val updatedTags = if (isSelected) selectedTags - tag else selectedTags + tag
                            selectedTags = updatedTags
                            triggerSingleSourceSearch(targetSource = currentSource, tagsToUse = updatedTags)
                        }, label = { Text(tag.title) })
                    }
                }
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(genresListState),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = statusText, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(displayedManga) { manga ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (currentSource != null) {
                            onMangaClick(manga, currentSource)
                        }
                    }) {
                    Row(
                        modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MangaCoverImage(
                            coverUrl = manga.coverUrl,
                            refererUrl = manga.publicUrl,
                            contentDescription = manga.title,
                            modifier = Modifier.width(70.dp).height(100.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = manga.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val authorText = if (manga.authors.isEmpty()) "Unknown" else manga.authors.joinToString()
                            Text(
                                text = "Author: $authorText", style = MaterialTheme.typography.bodySmall
                            )
                            if (manga.tags.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = manga.tags.take(4).joinToString(" • ") { it.title },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(DirectParsersAccess::class, ExperimentalComposeUiApi::class)
@Composable
fun SavedLibraryScreen(
    title: String,
    emptyMessage: String,
    items: List<SavedManga>,
    isCompactGrid: Boolean = false,
    service: MangaService,
    userStore: UserDataStore,
    onDataChanged: () -> Unit,
    onOpenManga: (SavedManga) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var cacheRefreshTick by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (items.isNotEmpty()) {
                Button(
                    enabled = !isCheckingUpdates, onClick = {
                        scope.launch {
                            isCheckingUpdates = true
                            var checkedCount = 0
                            var totalNewChapters = 0
                            updateStatusText = "Checking ${items.size} favorite(s) for new chapters..."

                            try {
                                service.checkFavoritesForUpdates(items).collect { result ->
                                    checkedCount++
                                    val freshChapters = result.freshDetails.chapters.orEmpty()
                                    val added = userStore.updateCachedDetails(
                                        manga = result.freshDetails,
                                        source = result.source,
                                        freshChapters = freshChapters,
                                        isBackgroundUpdateCheck = true
                                    )
                                    totalNewChapters += added
                                    cacheRefreshTick++
                                    updateStatusText =
                                        "Checked $checkedCount / ${items.size} • Found $totalNewChapters new chapter(s)..."
                                }
                                updateStatusText = if (totalNewChapters > 0) {
                                    "Done! Found $totalNewChapters new chapter(s) across your library."
                                } else {
                                    "All ${items.size} favorites are up to date!"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                updateStatusText = "Update check error: ${e.message}"
                            } finally {
                                isCheckingUpdates = false
                            }
                        }
                    }) {
                    Text(if (isCheckingUpdates) "Checking..." else "Check for Updates")
                }
            }
        }

        updateStatusText?.let { msg ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (items.isEmpty()) {
            Text(emptyMessage, style = MaterialTheme.typography.bodyMedium)
        } else if (isCompactGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.id }) { saved ->
                    val cached = remember(saved.id, cacheRefreshTick) { userStore.getCachedDetails(saved.id) }
                    val newCount = cached?.newChaptersCount ?: 0
                    val totalCh = cached?.chapters?.size ?: 0
                    val readCh = remember(saved.id, cacheRefreshTick) {
                        userStore.getHistoryForManga(saved.id)?.readChapterIds?.size ?: 0
                    }
                    val favProgress = if (totalCh > 0) (readCh.toFloat() / totalCh).coerceIn(0f, 1f) else 0f

                    var showContextMenu by remember { mutableStateOf(false) }

                    Box {
                        Card(
                            modifier = Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Press) { event ->
                                when {
                                    event.buttons.isSecondaryPressed -> showContextMenu = true
                                    event.buttons.isPrimaryPressed -> {
                                        userStore.clearNewChaptersBadge(saved.id)
                                        cacheRefreshTick++
                                        onOpenManga(saved)
                                    }
                                }
                            }) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Box {
                                    MangaCoverImage(
                                        coverUrl = saved.coverUrl,
                                        refererUrl = saved.publicUrl,
                                        contentDescription = saved.title,
                                        modifier = Modifier.fillMaxWidth().height(190.dp)
                                    )
                                    if (newCount > 0) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                        ) {
                                            Text(
                                                text = "+$newCount NEW",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = saved.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = saved.sourceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        FavoriteItemContextMenu(
                            expanded = showContextMenu,
                            saved = saved,
                            userStore = userStore,
                            onDismiss = { showContextMenu = false },
                            onOpen = {
                                userStore.clearNewChaptersBadge(saved.id)
                                cacheRefreshTick++
                                onOpenManga(saved)
                            },
                            onDataChanged = {
                                cacheRefreshTick++
                                onDataChanged()
                            })
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { saved ->
                    val cached = remember(saved.id, cacheRefreshTick) { userStore.getCachedDetails(saved.id) }
                    val newCount = cached?.newChaptersCount ?: 0
                    val totalCh = cached?.chapters?.size ?: 0
                    val readCh = remember(saved.id, cacheRefreshTick) {
                        userStore.getHistoryForManga(saved.id)?.readChapterIds?.size ?: 0
                    }
                    val favProgress = if (totalCh > 0) (readCh.toFloat() / totalCh).coerceIn(0f, 1f) else 0f
                    var showContextMenu by remember { mutableStateOf(false) }

                    Box {
                        Card(
                            modifier = Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Press) { event ->
                                when {
                                    event.buttons.isSecondaryPressed -> showContextMenu = true
                                    event.buttons.isPrimaryPressed -> {
                                        userStore.clearNewChaptersBadge(saved.id)
                                        cacheRefreshTick++
                                        onOpenManga(saved)
                                    }
                                }
                            }) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MangaCoverImage(
                                    coverUrl = saved.coverUrl,
                                    refererUrl = saved.publicUrl,
                                    contentDescription = saved.title,
                                    modifier = Modifier.width(65.dp).height(95.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        saved.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Source: ${saved.sourceName} • ${cached?.chapters?.size ?: 0} cached chapters",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (newCount > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = "+$newCount NEW",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        FavoriteItemContextMenu(
                            expanded = showContextMenu,
                            saved = saved,
                            userStore = userStore,
                            onDismiss = { showContextMenu = false },
                            onOpen = {
                                userStore.clearNewChaptersBadge(saved.id)
                                cacheRefreshTick++
                                onOpenManga(saved)
                            },
                            onDataChanged = {
                                cacheRefreshTick++
                                onDataChanged()
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteItemContextMenu(
    expanded: Boolean,
    saved: SavedManga,
    userStore: UserDataStore,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDataChanged: () -> Unit
) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(230.dp)
    ) {
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Rounded.OpenInNew, contentDescription = null) },
            text = { Text("Open Manga") },
            onClick = {
                onDismiss()
                onOpen()
            })
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Rounded.DoneAll, contentDescription = null) },
            text = { Text("Clear NEW Badge") },
            onClick = {
                userStore.clearNewChaptersBadge(saved.id)
                onDataChanged()
                onDismiss()
            })
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(leadingIcon = {
            Icon(
                Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error
            )
        }, text = { Text("Remove from Favorites", color = MaterialTheme.colorScheme.error) }, onClick = {
            userStore.removeFavorite(saved.id)
            onDataChanged()
            onDismiss()
        })
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReadingHistoryScreen(
    history: List<ReadingHistoryEntry>,
    isCompactGrid: Boolean = false,
    userStore: UserDataStore,
    onDataChanged: () -> Unit,
    onOpenEntry: (ReadingHistoryEntry) -> Unit
) {
    val groupedHistory = remember(history) {
        history.groupBy { formatHistoryDateHeader(it.updatedAt) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val gridColumns = (maxWidth / 175.dp).toInt().coerceAtLeast(2)

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Reading History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            if (history.isEmpty()) {
                Text(
                    text = "No reading history yet. Use the search bar above to find and read any manga!",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    groupedHistory.forEach { (dateHeader, entriesForDate) ->
                        item(key = "header_$dateHeader") {
                            Text(
                                text = dateHeader,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }

                        if (isCompactGrid) {
                            item(key = "grid_$dateHeader") {
                                val rows = entriesForDate.chunked(gridColumns)
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    rows.forEach { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            rowItems.forEach { entry ->
                                                val cachedChapters = userStore.getCachedDetails(entry.manga.id)?.chapters.orEmpty()
                                                val (_, effectiveRead, progress) = remember(cachedChapters, entry) {
                                                    val pairs = cachedChapters.map { it.id to extractChapterNumber(it.number, it.title) }
                                                    computeSeriesProgress(
                                                        chaptersIdAndNum = pairs,
                                                        explicitReadIds = entry.readChapterIds,
                                                        lastChapterId = entry.lastChapterId
                                                    )
                                                }

                                                var showContextMenu by remember { mutableStateOf(false) }

                                                Box(modifier = Modifier.weight(1f)) {
                                                    Card(
                                                        modifier = Modifier.fillMaxWidth()
                                                            .onPointerEvent(PointerEventType.Press) { event ->
                                                                when {
                                                                    event.buttons.isSecondaryPressed -> showContextMenu =
                                                                        true

                                                                    event.buttons.isPrimaryPressed -> onOpenEntry(entry)
                                                                }
                                                            }) {
                                                        Column(modifier = Modifier.padding(8.dp)) {
                                                            Box {
                                                                MangaCoverImage(
                                                                    coverUrl = entry.manga.coverUrl,
                                                                    refererUrl = entry.manga.publicUrl,
                                                                    contentDescription = entry.manga.title,
                                                                    modifier = Modifier.fillMaxWidth()
                                                                        .aspectRatio(0.68f)
                                                                )
                                                                if (cachedChapters.isNotEmpty()) {
                                                                    CoverProgressBadge(
                                                                        progressFraction = progress,
                                                                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                                                    )
                                                                }
                                                            }
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            Text(
                                                                text = entry.manga.title,
                                                                style = MaterialTheme.typography.titleSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = entry.lastChapterTitle,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.primary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }

                                                    HistoryItemContextMenu(
                                                        expanded = showContextMenu,
                                                        entry = entry,
                                                        userStore = userStore,
                                                        onDismiss = { showContextMenu = false },
                                                        onOpen = { onOpenEntry(entry) },
                                                        onDataChanged = onDataChanged
                                                    )
                                                }
                                            }
                                            repeat(gridColumns - rowItems.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            items(entriesForDate, key = { it.manga.id }) { entry ->
                                val cachedChapters = userStore.getCachedDetails(entry.manga.id)?.chapters.orEmpty()
                                val (_, effectiveRead, progress) = remember(cachedChapters, entry) {
                                    val pairs = cachedChapters.map { it.id to extractChapterNumber(it.number, it.title) }
                                    computeSeriesProgress(
                                        chaptersIdAndNum = pairs,
                                        explicitReadIds = entry.readChapterIds,
                                        lastChapterId = entry.lastChapterId
                                    )
                                }

                                var showContextMenu by remember { mutableStateOf(false) }

                                Box {
                                    Card(
                                        modifier = Modifier.fillMaxWidth()
                                            .onPointerEvent(PointerEventType.Press) { event ->
                                                when {
                                                    event.buttons.isSecondaryPressed -> showContextMenu = true
                                                    event.buttons.isPrimaryPressed -> onOpenEntry(entry)
                                                }
                                            }) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box {
                                                MangaCoverImage(
                                                    coverUrl = entry.manga.coverUrl,
                                                    refererUrl = entry.manga.publicUrl,
                                                    contentDescription = entry.manga.title,
                                                    modifier = Modifier.width(65.dp).height(95.dp)
                                                )
                                                if (cachedChapters.isNotEmpty()) {
                                                    CoverProgressBadge(
                                                        progressFraction = progress,
                                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                                    )
                                                }
                                            }
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    entry.manga.title,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Last Read: ${entry.lastChapterTitle} (Page ${entry.lastPageIndex + 1})",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                if (cachedChapters.isNotEmpty()) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        LinearProgressIndicator(
                                                            progress = { progress },
                                                            modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                                                        )
                                                        // --- PASTE THE SECOND TEXT BLOCK RIGHT HERE ---
                                                        Text(
                                                            text = "$effectiveRead/${cachedChapters.size} (${(progress * 100).toInt()}%)",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.secondary
                                                        )
                                                        // ----------------------------------------------
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    HistoryItemContextMenu(
                                        expanded = showContextMenu,
                                        entry = entry,
                                        userStore = userStore,
                                        onDismiss = { showContextMenu = false },
                                        onOpen = { onOpenEntry(entry) },
                                        onDataChanged = onDataChanged
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

    @Composable
    fun SaveMangaDownloadDialog(
        mangaTitle: String,
        totalChapters: Int,
        unreadCount: Int,
        defaultDir: File,
        onDismiss: () -> Unit,
        onConfirmSave: (selectionMode: Int, customCount: Int, format: DownloadFormat) -> Unit
    ) {
        // 0 = Whole manga, 1 = First N chapters, 2 = Next unread N chapters
        var selectedMode by remember { mutableStateOf(0) }
        var firstNCount by remember { mutableStateOf(5) }
        var nextUnreadCount by remember { mutableStateOf(1) }
        var showMoreOptions by remember { mutableStateOf(false) }
        var selectedFormat by remember { mutableStateOf(DownloadFormat.IMAGES) }

        AlertDialog(onDismissRequest = onDismiss, title = {
            Column {
                Text("Save manga", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = mangaTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Option 1: The whole manga
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedMode = 0 },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedMode == 0, onClick = { selectedMode = 0 })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("The whole manga", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "$totalChapters chapters",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                // Option 2: First N chapters
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedMode = 1 },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedMode == 1, onClick = { selectedMode = 1 })
                    Text(
                        text = "First $firstNCount chapters",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(5, 10, 25).forEach { count ->
                            FilterChip(selected = firstNCount == count, onClick = {
                                selectedMode = 1
                                firstNCount = count
                            }, label = { Text("$count") })
                        }
                    }
                }

                // Option 3: Next unread N chapters
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedMode = 2 },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedMode == 2, onClick = { selectedMode = 2 })
                    Text(
                        text = "Next unread $nextUnreadCount chapter(s)",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1, 5, 10).forEach { count ->
                            FilterChip(selected = nextUnreadCount == count, onClick = {
                                selectedMode = 2
                                nextUnreadCount = count
                            }, label = { Text("$count") })
                        }
                    }
                }

                HorizontalDivider()

                // Expandable "More options" (just like Image 2!)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showMoreOptions = !showMoreOptions },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "More options",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        if (showMoreOptions) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null
                    )
                }

                if (showMoreOptions) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Destination directory",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Surface(
                            tonalElevation = 2.dp,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = defaultDir.absolutePath,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        Text(
                            "Preferred download format",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DownloadFormat.entries.forEach { fmt ->
                                FilterChip(
                                    selected = selectedFormat == fmt,
                                    onClick = { selectedFormat = fmt },
                                    label = { Text(fmt.label) })
                            }
                        }
                    }
                }
            }
        }, confirmButton = {
            Button(
                onClick = {
                    val count = when (selectedMode) {
                        1 -> firstNCount
                        2 -> nextUnreadCount
                        else -> totalChapters
                    }
                    onConfirmSave(selectedMode, count, selectedFormat)
                }) {
                Text("Save")
            }
        }, dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        })
    }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DownloadsScreen(
    downloadManager: ChapterDownloadManager,
    userStore: UserDataStore,
    onOpenMangaById: (Long) -> Unit
) {
    val tasksMap by downloadManager.tasks.collectAsState()
    var refreshTick by remember { mutableStateOf(0) }

    val activeAndErroredTasks = remember(tasksMap) {
        tasksMap.values
            .filter { it.status != DownloadTaskStatus.COMPLETED }
            .sortedByDescending { it.updatedAt }
    }

    val completedDownloads = remember(tasksMap, refreshTick) {
        downloadManager.getAllDownloadedManga(userStore)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Downloads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // 1. In Progress & Recent Errors Section
        if (activeAndErroredTasks.isNotEmpty()) {
            Text(
                text = "In progress & Queue",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            activeAndErroredTasks.forEach { task ->
                val isError = task.status == DownloadTaskStatus.ERROR
                val isPaused = task.status == DownloadTaskStatus.PAUSED
                var showMenu by remember { mutableStateOf(false) }

                Box {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPointerEvent(PointerEventType.Press) { event ->
                                if (event.buttons.isSecondaryPressed) {
                                    showMenu = true
                                }
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MangaCoverImage(
                                    coverUrl = task.coverUrl,
                                    refererUrl = task.publicUrl,
                                    contentDescription = task.mangaTitle,
                                    modifier = Modifier.width(60.dp).height(85.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.mangaTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    when {
                                        isError -> {
                                            Text(
                                                text = "An error occurred",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                            Text(
                                                text = task.errorMessage ?: "Unknown error",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        isPaused -> {
                                            Text(
                                                text = "Paused • ${task.completedChapters}/${task.totalChaptersToDownload} chapters",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                        else -> {
                                            Text(
                                                text = "Downloading ${task.currentChapterTitle} (${task.completedChapters}/${task.totalChaptersToDownload} ch)...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                if (!isError) {
                                    Text(
                                        text = "${(task.overallProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (!isError) {
                                LinearProgressIndicator(
                                    progress = { task.overallProgress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { downloadManager.togglePause(task.mangaId) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (isPaused) "Resume" else "Pause")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            downloadManager.cancelDownload(task.mangaId)
                                            refreshTick++
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                                ) {
                                    Button(onClick = { downloadManager.retryDownload(task.mangaId) }) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Retry")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            downloadManager.cancelDownload(task.mangaId)
                                            refreshTick++
                                        }
                                    ) {
                                        Text("Dismiss")
                                    }
                                }
                            }
                        }
                    }

                    // Right-Click Context Menu for Active / Errored Tasks
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.width(220.dp)
                    ) {
                        if (isError) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                text = { Text("Retry Download") },
                                onClick = {
                                    downloadManager.retryDownload(task.mangaId)
                                    showMenu = false
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        contentDescription = null
                                    )
                                },
                                text = { Text(if (isPaused) "Resume Download" else "Pause Download") },
                                onClick = {
                                    downloadManager.togglePause(task.mangaId)
                                    showMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.OpenInNew, contentDescription = null) },
                            text = { Text("Open Manga") },
                            onClick = {
                                showMenu = false
                                onOpenMangaById(task.mangaId)
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            text = { Text("Cancel & Delete Files", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                downloadManager.deleteAllDownloadsForManga(task.mangaId)
                                refreshTick++
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
// 2. Completed Offline Library Section
        Text(
            text = "Downloaded Library",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        if (completedDownloads.isEmpty() && activeAndErroredTasks.isEmpty()) {
            Text(
                text = "No downloads yet. Open any manga and click the Download button to save chapters for offline reading!",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            completedDownloads.forEach { summary ->
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPointerEvent(PointerEventType.Press) { event ->
                                when {
                                    event.buttons.isSecondaryPressed -> showMenu = true
                                    event.buttons.isPrimaryPressed -> onOpenMangaById(summary.mangaId)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MangaCoverImage(
                                coverUrl = summary.coverUrl,
                                refererUrl = summary.publicUrl,
                                contentDescription = summary.mangaTitle,
                                modifier = Modifier.width(60.dp).height(85.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = summary.mangaTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Downloaded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${summary.downloadedChapterCount} chapter(s)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            IconButton(
                                onClick = {
                                    downloadManager.deleteAllDownloadsForManga(summary.mangaId)
                                    refreshTick++
                                }
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete downloaded manga",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
// Right-Click Context Menu for Completed Downloads
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.width(230.dp)
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.OpenInNew, contentDescription = null) },
                            text = { Text("Open Manga") },
                            onClick = {
                                showMenu = false
                                onOpenMangaById(summary.mangaId)
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                            text = { Text("Open Folder in Explorer") },
                            onClick = {
                                val folder = File(downloadManager.defaultDownloadsDir, summary.mangaId.toString())
                                if (folder.exists() && java.awt.Desktop.isDesktopSupported()) {
                                    runCatching { java.awt.Desktop.getDesktop().open(folder) }
                                }
                                showMenu = false
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            text = { Text("Delete Downloaded Manga", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                downloadManager.deleteAllDownloadsForManga(summary.mangaId)
                                refreshTick++
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}


@OptIn(DirectParsersAccess::class)
@Composable
private fun HistoryItemContextMenu(
    expanded: Boolean,
    entry: ReadingHistoryEntry,
    userStore: UserDataStore,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDataChanged: () -> Unit
) {
    val isFav = remember(expanded, entry.manga.id) { userStore.isFavorite(entry.manga.id) }

    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(230.dp)
    ) {
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Rounded.OpenInNew, contentDescription = null) },
            text = { Text("Open Manga") },
            onClick = {
                onDismiss()
                onOpen()
            })
        DropdownMenuItem(leadingIcon = {
            Icon(
                if (isFav) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = null
            )
        }, text = { Text(if (isFav) "Remove from Favorites" else "Add to Favorites") }, onClick = {
            val manga = entry.manga.toKotatsuManga()
            val src = runCatching { MangaParserSource.valueOf(entry.manga.sourceName) }.getOrNull()
            if (manga != null && src != null) {
                userStore.toggleFavorite(manga, src)
                onDataChanged()
            }
            onDismiss()
        })
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
            text = { Text("Reset Read Chapters") },
            onClick = {
                userStore.resetReadChapters(entry.manga.id)
                onDataChanged()
                onDismiss()
            })
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(leadingIcon = {
            Icon(
                Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error
            )
        }, text = { Text("Remove from History", color = MaterialTheme.colorScheme.error) }, onClick = {
            userStore.removeFromHistory(entry.manga.id)
            onDataChanged()
            onDismiss()
        })
    }
}

@OptIn(DirectParsersAccess::class)
@Composable
fun MangaDetailsScreen(
    initialManga: Manga,
    source: MangaParserSource,
    service: MangaService,
    userStore: UserDataStore,
    downloadManager: ChapterDownloadManager,
    onFavoriteToggled: () -> Unit,
    onChapterClick: (Manga, List<MangaChapter>, Int, Int) -> Unit,
    onBack: () -> Unit
) {
    val initialCache = remember(initialManga.id) { userStore.getCachedDetails(initialManga.id) }
    var cachedChapters by remember(initialManga.id) {
        mutableStateOf(initialCache?.chapters?.mapNotNull { it.toKotatsuChapter() }.orEmpty())
    }
    var detailedManga by remember { mutableStateOf(initialManga) }
    var isRefreshing by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isFavorite by remember(initialManga.id) { mutableStateOf(userStore.isFavorite(initialManga.id)) }
    val historyEntry = remember(initialManga.id) { userStore.getHistoryForManga(initialManga.id) }
    val scope = rememberCoroutineScope()
    val downloadTasks by downloadManager.tasks.collectAsState()
    val activeMangaTask = downloadTasks[detailedManga.id]?.takeIf {
        it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.PAUSED
    }
    var downloadRefreshTick by remember { mutableStateOf(0) }

    var chapterFilterQuery by remember { mutableStateOf("") }
    var sortChaptersAscending by remember { mutableStateOf(true) }
    var showUnreadOnly by remember { mutableStateOf(false) }
    var showBatchSaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(initialManga.id, source) {
        isRefreshing = true
        errorMessage = null
        userStore.clearNewChaptersBadge(initialManga.id)
        try {
            val fresh = service.getMangaDetails(source, initialManga)
            detailedManga = fresh
            val freshList = fresh.chapters.orEmpty()
            if (freshList.isNotEmpty()) {
                cachedChapters = freshList
                userStore.updateCachedDetails(
                    manga = fresh, source = source, freshChapters = freshList, isBackgroundUpdateCheck = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cachedChapters.isEmpty()) {
                errorMessage = "Failed to load details: ${e.message}"
            }
        } finally {
            isRefreshing = false
        }
    }

    // Declare chapters & readIds BEFORE computing displayedChapters!
    val chapters: List<MangaChapter> = detailedManga.chapters?.takeIf { it.isNotEmpty() } ?: cachedChapters
    // Normalize chapter order from Chapter 1 -> Latest even if the source lists newest first!
    val oldestToNewestPairs = remember(chapters) {
        val withNums = chapters.map { it.id to extractChapterNumber(it.number, it.title) }
        if (withNums.size >= 2 && withNums.first().second > withNums.last().second) {
            withNums.asReversed()
        } else {
            withNums
        }
    }

    val readIds: Set<Long> = remember(oldestToNewestPairs, historyEntry) {
        getEffectiveReadChapterIds(
            orderedFromFirstToLast = oldestToNewestPairs,
            explicitReadIds = historyEntry?.readChapterIds ?: emptySet(),
            lastChapterId = historyEntry?.lastChapterId
        )
    }


    val displayedChapters: List<Pair<Int, MangaChapter>> = remember(
        chapters, chapterFilterQuery, sortChaptersAscending, showUnreadOnly, readIds
    ) {
        val indexed = chapters.mapIndexed { idx, ch -> idx to ch }
        val filtered = indexed.filter { (_, ch) ->
            val matchesQuery = chapterFilterQuery.isBlank() || (ch.title?.contains(
                chapterFilterQuery,
                ignoreCase = true
            ) == true) || ch.number.toString().contains(chapterFilterQuery)
            val matchesUnread = !showUnreadOnly || (ch.id !in readIds)
            matchesQuery && matchesUnread
        }
        if (sortChaptersAscending) filtered else filtered.asReversed()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onBack) {
                Text("← Back")
            }
            Text(
                text = detailedManga.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            OutlinedButton(
                onClick = { showBatchSaveDialog = true }, enabled = chapters.isNotEmpty()
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Download")
            }
            Button(
                onClick = {
                    isFavorite = userStore.toggleFavorite(detailedManga, source)
                    onFavoriteToggled()
                }) {
                Text(if (isFavorite) "★ In Favorites" else "☆ Add to Favorites")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MangaCoverImage(
                coverUrl = detailedManga.largeCoverUrl ?: detailedManga.coverUrl,
                refererUrl = detailedManga.publicUrl,
                contentDescription = detailedManga.title,
                modifier = Modifier.width(140.dp).height(200.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                detailedManga.altTitles.firstOrNull()?.let { alt ->
                    Text(
                        text = alt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val authorText =
                    if (detailedManga.authors.isEmpty()) "Unknown" else detailedManga.authors.joinToString()
                Text(
                    "Author: $authorText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(onClick = {}, label = { Text("Source: ${source.title}") })
                    SuggestionChip(onClick = {}, label = { Text("Status: ${detailedManga.state?.name ?: "Unknown"}") })
                    if (detailedManga.rating > 0f) {
                        val score10 = String.format("%.1f", detailedManga.rating * 10f)
                        SuggestionChip(onClick = {}, label = { Text("★ $score10 / 10") })
                    }
                }

                if (chapters.isNotEmpty()) {
                    val readCount = chapters.count { it.id in readIds }
                    val progressFraction = (readCount.toFloat() / chapters.size.toFloat()).coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Progress ($readCount/${chapters.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            text = "${(progressFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (detailedManga.tags.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(detailedManga.tags.toList()) { tag ->
                            AssistChip(
                                onClick = {},
                                label = { Text(tag.title, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val cleanDesc = remember(detailedManga.description) {
                    cleanHtmlDescription(detailedManga.description)
                }
                Text(
                    text = cleanDesc.ifBlank { if (isRefreshing) "Loading synopsis..." else "No description available." },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )

                if (historyEntry != null && chapters.isNotEmpty()) {
                    val resumeIndex = chapters.indexOfFirst { it.id == historyEntry.lastChapterId }
                    if (resumeIndex >= 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = {
                                onChapterClick(detailedManga, chapters, resumeIndex, historyEntry.lastPageIndex)
                            }) {
                            Text("▶ Resume ${historyEntry.lastChapterTitle} (Page ${historyEntry.lastPageIndex + 1})")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Chapter Filter, Unread Toggle & Ascending/Descending Sort Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = when {
                    isRefreshing && chapters.isEmpty() -> "Loading chapters..."
                    isRefreshing -> "Chapters (${displayedChapters.size}/${chapters.size}) • Checking..."
                    else -> "Chapters (${displayedChapters.size}/${chapters.size})"
                }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))

            OutlinedTextField(
                value = chapterFilterQuery,
                onValueChange = { chapterFilterQuery = it },
                placeholder = { Text("Filter chapter...") },
                singleLine = true,
                modifier = Modifier.width(180.dp)
            )

            FilterChip(
                selected = showUnreadOnly,
                onClick = { showUnreadOnly = !showUnreadOnly },
                label = { Text("Unread") })

            OutlinedButton(onClick = { sortChaptersAscending = !sortChaptersAscending }) {
                Text(if (sortChaptersAscending) "Sort: Oldest ↑" else "Sort: Newest ↓")
            }
        }

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().padding(end = 12.dp)
            ) {
                items(displayedChapters, key = { it.second.id }) { (originalIndex, chapter) ->
                    val isRead = chapter.id in readIds
                    val isDownloaded = remember(detailedManga.id, chapter.id, downloadRefreshTick, downloadTasks) {
                        downloadManager.isChapterDownloaded(detailedManga.id, chapter.id)
                    }
                    val isThisChapterDownloading =
                        activeMangaTask != null && activeMangaTask.currentChapterTitle == (chapter.title
                            ?: "Chapter ${chapter.number}")
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onChapterClick(detailedManga, chapters, originalIndex, 0) }) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chapter.title ?: "Chapter ${chapter.number}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isRead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (isRead) {
                                Text(
                                    text = "✓ Read",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            chapter.scanlator?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            when {
                                isThisChapterDownloading -> {
                                    val totalP = activeMangaTask.currentChapterTotalPages
                                    val pageText =
                                        if (totalP > 0) "${activeMangaTask.currentChapterPage}/$totalP" else "..."
                                    Text(
                                        text = "Downloading ($pageText)...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                isDownloaded -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Downloaded",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        IconButton(
                                            onClick = {
                                                downloadManager.deleteDownloadedChapter(detailedManga.id, chapter.id)
                                                downloadRefreshTick++
                                            }, modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = "Delete download",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                else -> {
                                    IconButton(
                                        onClick = {
                                            downloadManager.enqueueChaptersDownload(
                                                manga = detailedManga,
                                                chaptersToDownload = listOf(chapter),
                                                source = source,
                                                onChapterCompleted = { downloadRefreshTick++ })
                                        }, modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Download,
                                            contentDescription = "Download chapter",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }

    if (showBatchSaveDialog) {
        SaveMangaDownloadDialog(
            mangaTitle = detailedManga.title,
            totalChapters = chapters.size,
            unreadCount = chapters.count { it.id !in readIds },
            defaultDir = downloadManager.defaultDownloadsDir,
            onDismiss = { showBatchSaveDialog = false },
            onConfirmSave = { mode, count, format ->
                showBatchSaveDialog = false
                val selectedChapters = when (mode) {
                    1 -> chapters.take(count)
                    2 -> chapters.filter { it.id !in readIds }.take(count)
                    else -> chapters
                }
                downloadManager.enqueueChaptersDownload(
                    manga = detailedManga,
                    chaptersToDownload = selectedChapters,
                    source = source,
                    format = format,
                    onChapterCompleted = { downloadRefreshTick++ })
            })
    }

}

@OptIn(DirectParsersAccess::class)
@Composable
fun MangaPageImage(
    page: MangaPage,
    source: MangaParserSource,
    refererUrl: String,
    service: MangaService,
    contentScale: ContentScale,
    alignment: Alignment = Alignment.Center,
    modifier: Modifier = Modifier
) {
    var resolvedUrl by remember(page) { mutableStateOf<String?>(null) }
    var resolveError by remember(page) { mutableStateOf<String?>(null) }
    LaunchedEffect(page) {
        resolvedUrl = null
        resolveError = null
        try {
            resolvedUrl = service.resolvePageImageUrl(source, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            resolveError = e.message ?: "Failed to resolve page URL"
        }
    }
    val platformContext = LocalPlatformContext.current
    val finalUrl = resolvedUrl
    when {
        resolveError != null -> {
            Box(modifier = modifier.height(200.dp), contentAlignment = Alignment.Center) {
                Text("Page error: $resolveError", color = MaterialTheme.colorScheme.error)
            }
        }

        finalUrl == null -> {
            Box(modifier = modifier.height(300.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        else -> {
            var imageLoadError by remember(finalUrl) { mutableStateOf<String?>(null) }
            val baseReferer = remember(source, refererUrl) {
                runCatching { service.getSourceBaseUrl(source) }.getOrDefault(refererUrl)
            }
            val request = remember(finalUrl, baseReferer) {
                ImageRequest.Builder(platformContext).data(finalUrl).size(Size.ORIGINAL).httpHeaders(
                    NetworkHeaders.Builder().set("Referer", baseReferer).build()
                ).build()
            }
            if (imageLoadError != null) {
                Box(modifier = modifier.height(200.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Image failed ($finalUrl): $imageLoadError", color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                AsyncImage(
                    model = request,
                    contentDescription = "Manga Page",
                    contentScale = contentScale,
                    alignment = alignment,
                    filterQuality = FilterQuality.High,
                    onError = { state ->
                        imageLoadError = state.result.throwable.message ?: "Unknown image error"
                        println("Coil page load error for $finalUrl: ${state.result.throwable}")
                    },
                    modifier = modifier
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, DirectParsersAccess::class)
@Composable
fun MangaReaderScreen(
    manga: Manga,
    chapters: List<MangaChapter>,
    currentChapterIndex: Int,
    initialPageIndex: Int = 0,
    appSettings: AppSettings,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    source: MangaParserSource,
    service: MangaService,
    downloadManager: ChapterDownloadManager,
    onProgressUpdated: (MangaChapter, Int) -> Unit,
    onSelectChapterIndex: (Int) -> Unit,
    onBack: () -> Unit
) {
    val currentChapter = chapters.getOrNull(currentChapterIndex) ?: return
    var pages by remember { mutableStateOf<List<MangaPage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var readerMode by remember {
        mutableStateOf(
            runCatching { ReaderMode.valueOf(appSettings.defaultReaderModeName) }.getOrDefault(ReaderMode.WEBTOON)
        )
    }
    var isRightToLeft by remember { mutableStateOf(appSettings.defaultRtl) }
    var currentPageIndex by remember(currentChapter.id) { mutableStateOf(initialPageIndex) }
    var showControls by remember { mutableStateOf(true) }
    var hudInteractionTick by remember { mutableStateOf(0) }
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    fun updateZoom(newScale: Float) {
        val clamped = newScale.coerceIn(1f, 4f)
        zoomScale = clamped
        if (clamped == 1f) {
            panOffset = Offset.Zero
        }
    }

    val pageStep = if (readerMode == ReaderMode.DOUBLE) 2 else 1
    fun goNextPage() {
        if (pages.isEmpty()) return
        val next = (currentPageIndex + pageStep).coerceAtMost(pages.lastIndex)
        if (next != currentPageIndex) currentPageIndex = next
    }

    fun goPrevPage() {
        if (pages.isEmpty()) return
        val prev = (currentPageIndex - pageStep).coerceAtLeast(0)
        if (prev != currentPageIndex) currentPageIndex = prev
    }

    val focusRequester = remember { FocusRequester() }
    val platformContext = LocalPlatformContext.current
    val webtoonScrollState = rememberLazyListState()
    val webtoonCurrentIndex by remember {
        derivedStateOf { webtoonScrollState.firstVisibleItemIndex }
    }
    val activeAnchorIndex = if (readerMode == ReaderMode.WEBTOON) webtoonCurrentIndex else currentPageIndex
    LaunchedEffect(showControls, hudInteractionTick, isLoading) {
        if (showControls && !isLoading && pages.isNotEmpty()) {
            delay(2_500L.milliseconds)
            showControls = false
        }
    }
    LaunchedEffect(showControls, readerMode) {
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(pages, activeAnchorIndex, currentChapter.id, appSettings.preloadPagesCount) {
        val preloadCount = appSettings.preloadPagesCount
        if (pages.isEmpty() || preloadCount <= 0) return@LaunchedEffect
        val startIndex = activeAnchorIndex + 1
        val endIndex = (activeAnchorIndex + preloadCount).coerceAtMost(pages.lastIndex)
        if (startIndex > endIndex) return@LaunchedEffect
        val imageLoader = SingletonImageLoader.get(platformContext)
        val baseReferer = runCatching { service.getSourceBaseUrl(source) }.getOrDefault(manga.publicUrl)
        for (i in startIndex..endIndex) {
            val upcomingPage = pages[i]
            try {
                val resolvedUrl = service.resolvePageImageUrl(source, upcomingPage)
                val prefetchRequest =
                    ImageRequest.Builder(platformContext).data(resolvedUrl).size(Size.ORIGINAL).httpHeaders(
                        NetworkHeaders.Builder().set("Referer", baseReferer).build()
                    ).build()
                imageLoader.enqueue(prefetchRequest)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
    LaunchedEffect(currentPageIndex, currentChapter.id) {
        zoomScale = 1f
        panOffset = Offset.Zero
        onProgressUpdated(currentChapter, currentPageIndex)
    }
    LaunchedEffect(currentChapter.id) {
        isLoading = true
        errorMessage = null
        pages = emptyList()
        showControls = true
        try {
            pages = service.getChapterPages(source, currentChapter)
            if (currentPageIndex > pages.lastIndex && pages.isNotEmpty()) {
                currentPageIndex = 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Failed to load pages: ${e.message}"
        } finally {
            isLoading = false
        }
        runCatching { focusRequester.requestFocus() }
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E0E)).focusRequester(focusRequester).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        event.key == Key.F11 -> {
                            onToggleFullscreen()
                            true
                        }

                        event.key == Key.H -> {
                            showControls = !showControls
                            if (showControls) hudInteractionTick++
                            true
                        }

                        event.key == Key.Escape -> {
                            if (isFullscreen) {
                                onToggleFullscreen()
                            } else {
                                showControls = !showControls
                                if (showControls) hudInteractionTick++
                            }
                            true
                        }

                        event.isCtrlPressed && (event.key == Key.Equals || event.key == Key.NumPadAdd) -> {
                            updateZoom(zoomScale + 0.25f)
                            true
                        }

                        event.isCtrlPressed && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> {
                            updateZoom(zoomScale - 0.25f)
                            true
                        }

                        event.isCtrlPressed && event.key == Key.Zero -> {
                            updateZoom(1f)
                            true
                        }

                        readerMode != ReaderMode.WEBTOON && pages.isNotEmpty() && event.key == Key.Spacebar -> {
                            goNextPage()
                            true
                        }

                        readerMode != ReaderMode.WEBTOON && pages.isNotEmpty() && event.key == Key.DirectionRight -> {
                            if (readerMode == ReaderMode.DOUBLE && isRightToLeft) goPrevPage() else goNextPage()
                            true
                        }

                        readerMode != ReaderMode.WEBTOON && pages.isNotEmpty() && event.key == Key.DirectionLeft -> {
                            if (readerMode == ReaderMode.DOUBLE && isRightToLeft) goNextPage() else goPrevPage()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }.onPointerEvent(PointerEventType.Move) { event ->
                val y = event.changes.firstOrNull()?.position?.y ?: return@onPointerEvent
                if (y < 60f) {
                    showControls = true
                    hudInteractionTick++
                }
            }.onPointerEvent(PointerEventType.Scroll) { event ->
                val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (readerMode != ReaderMode.WEBTOON || event.keyboardModifiers.isCtrlPressed) {
                    if (scrollDelta < 0f) {
                        updateZoom(zoomScale + 0.2f)
                    } else if (scrollDelta > 0f) {
                        updateZoom(zoomScale - 0.2f)
                    }
                }
            }.pointerInput(zoomScale) {
                detectDragGestures { change, dragAmount ->
                    if (zoomScale > 1f) {
                        change.consume()
                        panOffset += dragAmount
                    }
                }
            }.pointerInput(readerMode, isRightToLeft, pages) {
                detectTapGestures(onTap = { tapOffset ->
                    val width = size.width.toFloat()
                    if (readerMode != ReaderMode.WEBTOON && pages.isNotEmpty() && width > 0f) {
                        when {
                            tapOffset.x < width * 0.25f -> {
                                if (readerMode == ReaderMode.DOUBLE && isRightToLeft) goNextPage() else goPrevPage()
                            }

                            tapOffset.x > width * 0.75f -> {
                                if (readerMode == ReaderMode.DOUBLE && isRightToLeft) goPrevPage() else goNextPage()
                            }

                            else -> {
                                showControls = !showControls
                                if (showControls) hudInteractionTick++
                            }
                        }
                    } else {
                        showControls = !showControls
                        if (showControls) hudInteractionTick++
                    }
                }, onDoubleTap = {
                    if (zoomScale > 1f) updateZoom(1f) else updateZoom(2f)
                })
            }, contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator()
            errorMessage != null -> Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
            pages.isEmpty() -> Text("No pages found for this chapter.", color = Color.White)
            readerMode == ReaderMode.WEBTOON -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = webtoonScrollState,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                            translationX = panOffset.x
                            translationY = panOffset.y
                        }) {
                        items(pages) { page ->
                            MangaPageImage(
                                page = page,
                                source = source,
                                refererUrl = manga.publicUrl,
                                service = service,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.widthIn(max = 950.dp).fillMaxWidth()
                            )
                        }
                    }
                    if (showControls) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(webtoonScrollState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
            }

            readerMode == ReaderMode.SINGLE -> {
                MangaPageImage(
                    page = pages[currentPageIndex],
                    source = source,
                    refererUrl = manga.publicUrl,
                    service = service,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panOffset.x
                        translationY = panOffset.y
                    })
            }

            readerMode == ReaderMode.DOUBLE -> {
                val firstPage = pages[currentPageIndex]
                val secondPage = pages.getOrNull(currentPageIndex + 1)
                val leftPage = if (isRightToLeft) secondPage else firstPage
                val rightPage = if (isRightToLeft) firstPage else secondPage
                Row(
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panOffset.x
                        translationY = panOffset.y
                    }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leftPage != null) {
                        MangaPageImage(
                            page = leftPage,
                            source = source,
                            refererUrl = manga.publicUrl,
                            service = service,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterEnd,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (rightPage != null) {
                        MangaPageImage(
                            page = rightPage,
                            source = source,
                            refererUrl = manga.publicUrl,
                            service = service,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xD9161616))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("← Chapters", color = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = manga.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val chapterLabel = currentChapter.title ?: "Chapter ${currentChapter.number}"
                    val incognitoSuffix = if (appSettings.incognitoMode) " • 🕶 Incognito" else ""
                    Text(
                        text = "$chapterLabel$incognitoSuffix",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = {
                        hudInteractionTick++
                        updateZoom(zoomScale - 0.25f)
                    }, enabled = zoomScale > 1f
                ) {
                    Text("-", color = Color.White)
                }
                TextButton(
                    onClick = {
                        hudInteractionTick++
                        updateZoom(1f)
                    }) {
                    Text("${(zoomScale * 100).toInt()}%", color = Color.White)
                }
                OutlinedButton(
                    onClick = {
                        hudInteractionTick++
                        updateZoom(zoomScale + 0.25f)
                    }, enabled = zoomScale < 4f
                ) {
                    Text("+", color = Color.White)
                }
                FilterChip(selected = true, onClick = {
                    hudInteractionTick++
                    val modes = ReaderMode.entries
                    readerMode = modes[(readerMode.ordinal + 1) % modes.size]
                    updateZoom(1f)
                }, label = { Text("Mode: ${readerMode.label}") })
                if (readerMode == ReaderMode.DOUBLE) {
                    FilterChip(selected = isRightToLeft, onClick = {
                        hudInteractionTick++
                        isRightToLeft = !isRightToLeft
                    }, label = { Text(if (isRightToLeft) "RTL" else "LTR") })
                    OutlinedButton(
                        onClick = {
                            hudInteractionTick++
                            currentPageIndex = if (currentPageIndex == 0 && pages.size > 1) 1
                            else (currentPageIndex - 1).coerceAtLeast(0)
                        }) {
                        Text("Shift Spread", color = Color.White)
                    }
                }
                OutlinedButton(
                    onClick = {
                        hudInteractionTick++
                        onToggleFullscreen()
                    }) {
                    Text(if (isFullscreen) "Exit Fullscreen (F11)" else "Fullscreen (F11)", color = Color.White)
                }
                OutlinedButton(
                    onClick = { onSelectChapterIndex(currentChapterIndex + 1) },
                    enabled = currentChapterIndex < chapters.lastIndex
                ) {
                    Text("Prev Ch", color = Color.White)
                }
                OutlinedButton(
                    onClick = { onSelectChapterIndex(currentChapterIndex - 1) }, enabled = currentChapterIndex > 0
                ) {
                    Text("Next Ch", color = Color.White)
                }
            }
        }
        AnimatedVisibility(
            visible = showControls && readerMode != ReaderMode.WEBTOON && pages.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xD9161616))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (readerMode == ReaderMode.DOUBLE && isRightToLeft) {
                    Button(
                        onClick = {
                            hudInteractionTick++
                            goNextPage()
                        }, enabled = currentPageIndex + 1 < pages.size
                    ) {
                        Text("← Next Spread")
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    val endNum = (currentPageIndex + 2).coerceAtMost(pages.size)
                    Text(
                        text = "Pages ${currentPageIndex + 1}–$endNum / ${pages.size} (RTL)", color = Color.White
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = {
                            hudInteractionTick++
                            goPrevPage()
                        }, enabled = currentPageIndex > 0
                    ) {
                        Text("Prev Spread →")
                    }
                } else {
                    Button(
                        onClick = {
                            hudInteractionTick++
                            goPrevPage()
                        }, enabled = currentPageIndex > 0
                    ) {
                        Text(if (readerMode == ReaderMode.DOUBLE) "← Prev Spread" else "← Prev Page")
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    val pageLabel = if (readerMode == ReaderMode.DOUBLE) {
                        val endNum = (currentPageIndex + 2).coerceAtMost(pages.size)
                        "Pages ${currentPageIndex + 1}–$endNum / ${pages.size} (LTR)"
                    } else {
                        "Page ${currentPageIndex + 1} / ${pages.size}"
                    }
                    Text(text = pageLabel, color = Color.White)
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = {
                            hudInteractionTick++
                            goNextPage()
                        },
                        enabled = if (readerMode == ReaderMode.DOUBLE) currentPageIndex + 1 < pages.size else currentPageIndex < pages.lastIndex
                    ) {
                        Text(if (readerMode == ReaderMode.DOUBLE) "Next Spread →" else "Next Page →")
                    }
                }

            }
        }
    }
}