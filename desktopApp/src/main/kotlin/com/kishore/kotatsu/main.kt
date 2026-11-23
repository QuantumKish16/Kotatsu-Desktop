package com.kishore.kotatsu

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.size.Size
import kotlinx.coroutines.Job
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

private enum class AppTab {
    BROWSE,
    FAVORITES,
    HISTORY,
    SETTINGS
}

private enum class ReaderMode(val label: String) {
    WEBTOON("Webtoon Scroll"),
    SINGLE("Single Page"),
    DOUBLE("Two-Page Spread")
}

private fun pickBackupFile(isSave: Boolean): File? {
    val dialog = FileDialog(
        null as Frame?,
        if (isSave) "Export Kotatsu Backup (.json)" else "Import Kotatsu Backup (.json)",
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

fun main() = application {
    val userStore = remember { UserDataStore() }
    var appSettings by remember { mutableStateOf(userStore.getSettings()) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Kotatsu Desktop"
    ) {
        MaterialTheme(
            colorScheme = if (appSettings.darkTheme) darkColorScheme() else lightColorScheme()
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                KotatsuApp(
                    userStore = userStore,
                    appSettings = appSettings,
                    onSettingsChanged = { updated ->
                        userStore.updateSettings(updated)
                        appSettings = updated
                    }
                )
            }
        }
    }
}

@OptIn(DirectParsersAccess::class)
@Composable
fun KotatsuApp(
    userStore: UserDataStore,
    appSettings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit
) {
    val service = remember { MangaService() }

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { service.httpClient }))
            }
            .build()
    }

    var activeTab by remember { mutableStateOf(AppTab.BROWSE) }
    var favoritesList by remember { mutableStateOf(userStore.getFavorites()) }
    var historyList by remember { mutableStateOf(userStore.getHistory()) }

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
                source = currentPair.second,
                service = service,
                onProgressUpdated = { chapter, pageIdx ->
                    userStore.recordReadingProgress(manga, currentPair.second, chapter, pageIdx)
                    refreshUserData()
                },
                onSelectChapterIndex = { newIndex ->
                    initialReaderPage = 0
                    readerState = Triple(manga, allChapters, newIndex)
                },
                onBack = {
                    initialReaderPage = 0
                    readerState = null
                }
            )
        }

        currentPair != null -> {
            MangaDetailsScreen(
                initialManga = currentPair.first,
                source = currentPair.second,
                service = service,
                userStore = userStore,
                onFavoriteToggled = { refreshUserData() },
                onChapterClick = { loadedManga, allChapters, index, startPage ->
                    initialReaderPage = startPage
                    readerState = Triple(loadedManga, allChapters, index)
                },
                onBack = { selectedMangaPair = null }
            )
        }

        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                // Clean 4-Tab Navigation Bar
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = activeTab == AppTab.BROWSE,
                            onClick = { activeTab = AppTab.BROWSE },
                            label = { Text("Browse") }
                        )
                        FilterChip(
                            selected = activeTab == AppTab.FAVORITES,
                            onClick = {
                                refreshUserData()
                                activeTab = AppTab.FAVORITES
                            },
                            label = { Text("Favorites (${favoritesList.size})") }
                        )
                        FilterChip(
                            selected = activeTab == AppTab.HISTORY,
                            onClick = {
                                refreshUserData()
                                activeTab = AppTab.HISTORY
                            },
                            label = { Text("History (${historyList.size})") }
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        FilterChip(
                            selected = activeTab == AppTab.SETTINGS,
                            onClick = { activeTab = AppTab.SETTINGS },
                            label = { Text("⚙ Settings") }
                        )
                    }
                }

                when (activeTab) {
                    AppTab.BROWSE -> MangaSearchScreen(
                        service = service,
                        appSettings = appSettings,
                        onSettingsChanged = onSettingsChanged,
                        lockedSource = lockedSource,
                        onLockedSourceChanged = { newSource -> lockedSource = newSource },
                        onMangaClick = { clickedManga, mangaSource ->
                            selectedMangaPair = clickedManga to mangaSource
                        }
                    )

                    AppTab.FAVORITES -> SavedLibraryScreen(
                        title = "Saved Favorites",
                        emptyMessage = "No favorites yet. Open any manga and click 'Add to Favorites'!",
                        items = favoritesList,
                        onOpenManga = { saved ->
                            val manga = saved.toKotatsuManga()
                            val src = runCatching { MangaParserSource.valueOf(saved.sourceName) }.getOrNull()
                            if (manga != null && src != null) {
                                selectedMangaPair = manga to src
                            }
                        }
                    )
                    AppTab.HISTORY -> ReadingHistoryScreen(
                        history = historyList,
                        onOpenEntry = { entry ->
                            val manga = entry.manga.toKotatsuManga()
                            val src = runCatching { MangaParserSource.valueOf(entry.manga.sourceName) }.getOrNull()
                            if (manga != null && src != null) {
                                selectedMangaPair = manga to src
                            }
                        }
                    )
                    AppTab.SETTINGS -> SettingsScreen(
                        appSettings = appSettings,
                        userStore = userStore,
                        onSettingsChanged = onSettingsChanged,
                        onDataRestoredOrCleared = { refreshUserData() }
                    )
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Settings & Configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
// 1. Appearance Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                        onCheckedChange = { onSettingsChanged(appSettings.copy(darkTheme = it)) }
                    )
                }
            }
        }
// 2. Search & Content Filters Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Search & Content Filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
// Hide NSFW Toggle
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
                        onCheckedChange = { onSettingsChanged(appSettings.copy(hideNsfw = it)) }
                    )
                }
                HorizontalDivider()
// Default Source Language
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Default Source Language", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("en" to "English (EN)", "all" to "All Languages", "ja" to "Japanese (JA)", "ko" to "Korean (KO)").forEach { (code, label) ->
                            FilterChip(
                                selected = appSettings.defaultLanguage == code,
                                onClick = { onSettingsChanged(appSettings.copy(defaultLanguage = code)) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                HorizontalDivider()
// Max Global Search Sources
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
                                label = { Text("$count Sources") }
                            )
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
                                label = { Text(mode.label) }
                            )
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
                        onCheckedChange = { onSettingsChanged(appSettings.copy(defaultRtl = it)) }
                    )
                }
            }
        }
// 4. Backup, Restore & Storage Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Backup, Restore & Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "Local Data File: ${userStore.getStoragePath()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val file = pickBackupFile(isSave = true)
                            if (file != null) {
                                runCatching { userStore.exportBackupTo(file) }
                                    .onSuccess { statusMessage = "Backup exported successfully to ${file.absolutePath}" }
                                    .onFailure { statusMessage = "Export failed: ${it.message}" }
                            }
                        }
                    ) {
                        Text("Export Backup (.json)")
                    }
                    OutlinedButton(
                        onClick = {
                            val file = pickBackupFile(isSave = false)
                            if (file != null) {
                                runCatching { userStore.importBackupFrom(file) }
                                    .onSuccess { (favCount, histCount) ->
                                        onDataRestoredOrCleared()
                                        statusMessage = "Restored & merged $favCount favorites and $histCount history entries!"
                                    }
                                    .onFailure { statusMessage = "Import failed: ${it.message}" }
                            }
                        }
                    ) {
                        Text("Import / Restore Backup (.json)")
                    }
                    OutlinedButton(
                        onClick = {
                            userStore.clearHistory()
                            onDataRestoredOrCleared()
                            statusMessage = "Reading history cleared."
                        }
                    ) {
                        Text("Clear History")
                    }
                }
                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
@OptIn(DirectParsersAccess::class)
@Composable
fun SourceSelectorDropdown(
    sources: List<MangaParserSource>,
    lockedSource: MangaParserSource?,
    onSourceSelected: (MangaParserSource?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    val filteredSources = remember(sources, filterText) {
        if (filterText.isBlank()) {
            sources.take(50)
        } else {
            sources.filter {
                it.name.contains(filterText, ignoreCase = true) ||
                        it.title.contains(filterText, ignoreCase = true)
            }.take(50)
        }
    }
    Box {
        OutlinedButton(
            onClick = {
                filterText = ""
                expanded = true
            },
            modifier = Modifier.height(56.dp)
        ) {
            val label = lockedSource?.title ?: "All Sources"
            Text(
                text = "Source: $label ▼",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
            DropdownMenuItem(
                text = {
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
                },
                onClick = {
                    onSourceSelected(null)
                    expanded = false
                }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            filteredSources.forEach { source ->
                DropdownMenuItem(
                    text = {
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
                    },
                    onClick = {
                        onSourceSelected(source)
                        expanded = false
                    }
                )
            }
        }
    }
}
@Composable
fun MangaCoverImage(
    coverUrl: String?,
    refererUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val platformContext = LocalPlatformContext.current
    val imageRequest = remember(coverUrl, refererUrl) {
        ImageRequest.Builder(platformContext)
            .data(coverUrl)
            .httpHeaders(
                NetworkHeaders.Builder()
                    .set("Referer", refererUrl)
                    .build()
            )
            .build()
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
    var searchQuery by remember { mutableStateOf("Naruto") }
    var multiSourceResults by remember { mutableStateOf<Map<MangaParserSource, List<Manga>>>(emptyMap()) }
    var viewedSource by remember { mutableStateOf(lockedSource) }
    var statusText by remember { mutableStateOf("Select 'All Sources' or a specific source and click Search") }
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
                    sourcesToSearch = targets,
                    query = searchQuery,
                    sortOrder = selectedSort,
                    hideNsfw = hideNsfw
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
        targetSource: MangaParserSource,
        tagsToUse: Set<MangaTag> = selectedTags,
        sortToUse: SortOrder = selectedSort
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
    val currentSource = viewedSource
    val displayedManga = if (currentSource != null) multiSourceResults[currentSource].orEmpty() else emptyList()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Lang:", style = MaterialTheme.typography.labelLarge)
            listOf("en" to "EN", "all" to "ALL", "ja" to "JA", "ko" to "KO").forEach { (code, label) ->
                FilterChip(
                    selected = selectedLanguage == code,
                    onClick = {
                        selectedLanguage = code
                        onSettingsChanged(appSettings.copy(defaultLanguage = code))
                    },
                    label = { Text(label) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            listOf(SortOrder.POPULARITY to "Popular", SortOrder.UPDATED to "Latest").forEach { (order, label) ->
                FilterChip(
                    selected = selectedSort == order,
                    onClick = {
                        selectedSort = order
                        executeSearch()
                    },
                    label = { Text(label) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SourceSelectorDropdown(
                sources = availableSources,
                lockedSource = lockedSource,
                onSourceSelected = { newSelection ->
                    onLockedSourceChanged(newSelection)
                    if (newSelection == null) {
                        if (searchQuery.isNotBlank()) triggerGlobalSearch()
                    } else {
                        triggerSingleSourceSearch(targetSource = newSelection, tagsToUse = emptySet())
                    }
                }
            )
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = {
                    Text(
                        if (lockedSource == null) "Search Title Across All Sources"
                        else "Search ${lockedSource.title}"
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { executeSearch() },
                modifier = Modifier.height(56.dp)
            ) {
                Text(
                    when {
                        isLoading -> "Searching..."
                        lockedSource == null -> "Search All"
                        else -> "Search"
                    }
                )
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
                            label = { Text("${source.title} (${list.size})") }
                        )
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
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val updatedTags = if (isSelected) selectedTags - tag else selectedTags + tag
                                selectedTags = updatedTags
                                triggerSingleSourceSearch(targetSource = currentSource, tagsToUse = updatedTags)
                            },
                            label = { Text(tag.title) }
                        )
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (currentSource != null) {
                                onMangaClick(manga, currentSource)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                                text = "Author: $authorText",
                                style = MaterialTheme.typography.bodySmall
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
@Composable
fun SavedLibraryScreen(
    title: String,
    emptyMessage: String,
    items: List<SavedManga>,
    onOpenManga: (SavedManga) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text(emptyMessage, style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items) { saved ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenManga(saved) }
                    ) {
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
                                Text(saved.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "Source: ${saved.sourceName}",
                                    style = MaterialTheme.typography.labelMedium,
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
@Composable
fun ReadingHistoryScreen(
    history: List<ReadingHistoryEntry>,
    onOpenEntry: (ReadingHistoryEntry) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Reading History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        if (history.isEmpty()) {
            Text("No reading history yet. Open any chapter to start tracking!", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenEntry(entry) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MangaCoverImage(
                                coverUrl = entry.manga.coverUrl,
                                refererUrl = entry.manga.publicUrl,
                                contentDescription = entry.manga.title,
                                modifier = Modifier.width(65.dp).height(95.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.manga.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "Last Read: ${entry.lastChapterTitle} (Page ${entry.lastPageIndex + 1})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Source: ${entry.manga.sourceName} • ${entry.readChapterIds.size} chapters read",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
@OptIn(DirectParsersAccess::class)
@Composable
fun MangaDetailsScreen(
    initialManga: Manga,
    source: MangaParserSource,
    service: MangaService,
    userStore: UserDataStore,
    onFavoriteToggled: () -> Unit,
    onChapterClick: (Manga, List<MangaChapter>, Int, Int) -> Unit,
    onBack: () -> Unit
) {
    var detailedManga by remember { mutableStateOf(initialManga) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isFavorite by remember(initialManga.id) { mutableStateOf(userStore.isFavorite(initialManga.id)) }
    val historyEntry = remember(initialManga.id) { userStore.getHistoryForManga(initialManga.id) }
    LaunchedEffect(initialManga.id, source) {
        isLoading = true
        errorMessage = null
        try {
            detailedManga = service.getMangaDetails(source, initialManga)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Failed to load details: ${e.message}"
        } finally {
            isLoading = false
        }
    }
    val chapters: List<MangaChapter> = detailedManga.chapters.orEmpty()
    val readIds = historyEntry?.readChapterIds ?: emptySet()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
            Button(
                onClick = {
                    isFavorite = userStore.toggleFavorite(detailedManga, source)
                    onFavoriteToggled()
                }
            ) {
                Text(if (isFavorite) "★ In Favorites" else "☆ Add to Favorites")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MangaCoverImage(
                coverUrl = detailedManga.largeCoverUrl ?: detailedManga.coverUrl,
                refererUrl = detailedManga.publicUrl,
                contentDescription = detailedManga.title,
                modifier = Modifier.width(140.dp).height(200.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                val authorText = if (detailedManga.authors.isEmpty()) "Unknown" else detailedManga.authors.joinToString()
                Text("Author: $authorText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Status: ${detailedManga.state ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = detailedManga.description?.ifBlank { "No description available." } ?: "Loading synopsis...",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
                if (historyEntry != null && chapters.isNotEmpty()) {
                    val resumeIndex = chapters.indexOfFirst { it.id == historyEntry.lastChapterId }
                    if (resumeIndex >= 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                onChapterClick(detailedManga, chapters, resumeIndex, historyEntry.lastPageIndex)
                            }
                        ) {
                            Text("▶ Resume ${historyEntry.lastChapterTitle} (Page ${historyEntry.lastPageIndex + 1})")
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isLoading) "Loading chapters..." else "Chapters (${chapters.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
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
                items(chapters.size) { index ->
                    val chapter = chapters[index]
                    val isRead = chapter.id in readIds
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterClick(detailedManga, chapters, index, 0) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
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
                                    text = "✓ Read  ",
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
                ImageRequest.Builder(platformContext)
                    .data(finalUrl)
                    .size(Size.ORIGINAL)
                    .httpHeaders(
                        NetworkHeaders.Builder()
                            .set("Referer", baseReferer)
                            .build()
                    )
                    .build()
            }
            if (imageLoadError != null) {
                Box(modifier = modifier.height(200.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Image failed ($finalUrl): $imageLoadError",
                        color = MaterialTheme.colorScheme.error
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
    source: MangaParserSource,
    service: MangaService,
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
    LaunchedEffect(currentPageIndex, currentChapter.id) {
        zoomScale = 1f
        panOffset = Offset.Zero
        onProgressUpdated(currentChapter, currentPageIndex)
    }
    LaunchedEffect(currentChapter.id) {
        isLoading = true
        errorMessage = null
        pages = emptyList()
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
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
            }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                Text(
                    text = currentChapter.title ?: "Chapter ${currentChapter.number}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = { updateZoom(zoomScale - 0.25f) },
                enabled = zoomScale > 1f
            ) {
                Text("-", color = Color.White)
            }
            TextButton(onClick = { updateZoom(1f) }) {
                Text("${(zoomScale * 100).toInt()}%", color = Color.White)
            }
            OutlinedButton(
                onClick = { updateZoom(zoomScale + 0.25f) },
                enabled = zoomScale < 4f
            ) {
                Text("+", color = Color.White)
            }
            FilterChip(
                selected = true,
                onClick = {
                    val modes = ReaderMode.entries
                    readerMode = modes[(readerMode.ordinal + 1) % modes.size]
                    updateZoom(1f)
                },
                label = { Text("Mode: ${readerMode.label}") }
            )
            if (readerMode == ReaderMode.DOUBLE) {
                FilterChip(
                    selected = isRightToLeft,
                    onClick = { isRightToLeft = !isRightToLeft },
                    label = { Text(if (isRightToLeft) "RTL (Manga)" else "LTR (Comic)") }
                )
                OutlinedButton(
                    onClick = {
                        currentPageIndex = if (currentPageIndex == 0 && pages.size > 1) 1
                        else (currentPageIndex - 1).coerceAtLeast(0)
                    }
                ) {
                    Text("Shift Spread", color = Color.White)
                }
            }
            OutlinedButton(
                onClick = { onSelectChapterIndex(currentChapterIndex + 1) },
                enabled = currentChapterIndex < chapters.lastIndex
            ) {
                Text("Prev Ch", color = Color.White)
            }
            OutlinedButton(
                onClick = { onSelectChapterIndex(currentChapterIndex - 1) },
                enabled = currentChapterIndex > 0
            ) {
                Text("Next Ch", color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (readerMode != ReaderMode.WEBTOON || event.keyboardModifiers.isCtrlPressed) {
                        if (scrollDelta < 0f) {
                            updateZoom(zoomScale + 0.2f)
                        } else if (scrollDelta > 0f) {
                            updateZoom(zoomScale - 0.2f)
                        }
                    }
                }
                .pointerInput(zoomScale) {
                    detectDragGestures { change, dragAmount ->
                        if (zoomScale > 1f) {
                            change.consume()
                            panOffset += dragAmount
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (zoomScale > 1f) updateZoom(1f) else updateZoom(2f)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator()
                errorMessage != null -> Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                pages.isEmpty() -> Text("No pages found for this chapter.", color = Color.White)
                readerMode == ReaderMode.WEBTOON -> {
                    val scrollState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = scrollState,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = 12.dp)
                                .graphicsLayer {
                                    scaleX = zoomScale
                                    scaleY = zoomScale
                                    translationX = panOffset.x
                                    translationY = panOffset.y
                                }
                        ) {
                            items(pages) { page ->
                                MangaPageImage(
                                    page = page,
                                    source = source,
                                    refererUrl = manga.publicUrl,
                                    service = service,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
                readerMode == ReaderMode.SINGLE -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                            contentAlignment = Alignment.Center
                        ) {
                            MangaPageImage(
                                page = pages[currentPageIndex],
                                source = source,
                                refererUrl = manga.publicUrl,
                                service = service,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = zoomScale
                                        scaleY = zoomScale
                                        translationX = panOffset.x
                                        translationY = panOffset.y
                                    }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(onClick = { goPrevPage() }, enabled = currentPageIndex > 0) {
                                Text("← Prev Page")
                            }
                            Text(
                                text = "Page ${currentPageIndex + 1} / ${pages.size}",
                                color = Color.White
                            )
                            Button(onClick = { goNextPage() }, enabled = currentPageIndex < pages.lastIndex) {
                                Text("Next Page →")
                            }
                        }
                    }
                }
                readerMode == ReaderMode.DOUBLE -> {
                    val firstPage = pages[currentPageIndex]
                    val secondPage = pages.getOrNull(currentPageIndex + 1)
                    val leftPage = if (isRightToLeft) secondPage else firstPage
                    val rightPage = if (isRightToLeft) firstPage else secondPage
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clipToBounds()
                                .graphicsLayer {
                                    scaleX = zoomScale
                                    scaleY = zoomScale
                                    translationX = panOffset.x
                                    translationY = panOffset.y
                                },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (isRightToLeft) {
                                Button(onClick = { goNextPage() }, enabled = currentPageIndex + 1 < pages.size) {
                                    Text("← Next Spread")
                                }
                                val endNum = (currentPageIndex + 2).coerceAtMost(pages.size)
                                Text(
                                    text = "Pages ${currentPageIndex + 1}–$endNum / ${pages.size} (RTL)",
                                    color = Color.White
                                )
                                Button(onClick = { goPrevPage() }, enabled = currentPageIndex > 0) {
                                    Text("Prev Spread →")
                                }
                            } else {
                                Button(onClick = { goPrevPage() }, enabled = currentPageIndex > 0) {
                                    Text("← Prev Spread")
                                }
                                val endNum = (currentPageIndex + 2).coerceAtMost(pages.size)
                                Text(
                                    text = "Pages ${currentPageIndex + 1}–$endNum / ${pages.size} (LTR)",
                                    color = Color.White
                                )
                                Button(onClick = { goNextPage() }, enabled = currentPageIndex + 1 < pages.size) {
                                    Text("Next Spread →")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
