package com.yagay.supjav.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.data.MissavSource
import com.yagay.supjav.data.MissavPageNavigation
import com.yagay.supjav.data.SupjavSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Genre, Maker and Actress index pages are catalogues, not video feeds.
 * Load the selected index and its actual pagination independently.
 */
@Composable
internal fun MissavIndexPage(
    app: SupjavApp,
    rootPath: String,
    onSelect: (SupjavSource.BrowseOption) -> Unit
) {
    var items by remember(rootPath) { mutableStateOf(emptyList<SupjavSource.BrowseOption>()) }
    var next by remember(rootPath) { mutableStateOf<String?>(null) }
    var loading by remember(rootPath) { mutableStateOf(false) }
    var error by remember(rootPath) { mutableStateOf<String?>(null) }
    var request by remember(rootPath) { mutableStateOf(MissavSource.BASE + rootPath) }
    var sorts by remember(rootPath) {
        mutableStateOf(emptyList<MissavPageNavigation.Choice>())
    }
    var currentSort by remember(rootPath) { mutableStateOf("") }
    var sortMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(rootPath, request) {
        loading = true
        error = null
        try {
            val (entries, nextUrl, pageSorts) = withContext(Dispatchers.IO) {
                val html = app.source.fetch(request)
                val expectedGroup = rootPath.substringBefore('?').trimEnd('/')
                    .split('/').let { if (it.last() == "ranking") "actresses" else it.last() }
                // Actual result links may use /dmN/en/... even when the index
                // itself is /en/genres, so compare the taxonomy segment instead
                // of requiring identical URL prefixes.
                val parsed = app.source.parseBrowseOptions(html, request)
                    .filter { candidate ->
                        candidate.path.trimEnd('/').split('/').dropLast(1).lastOrNull() ==
                            expectedGroup
                    }
                val doc = Jsoup.parse(html, request)
                val pagePattern = Regex("(?:[?&])page=([0-9]+)")
                val currentNumber = pagePattern.find(request)?.groupValues?.get(1)
                    ?.toIntOrNull() ?: 1
                val nextLink = doc.select("a[rel=next][href], a.next[href], .pagination a[href]")
                    .firstOrNull { a ->
                        val href = a.absUrl("href")
                        val number = pagePattern.find(href)?.groupValues?.get(1)
                            ?.toIntOrNull()
                        href.startsWith(MissavSource.BASE + rootPath + "?") &&
                            number != null && number > currentNumber &&
                            (a.attr("rel") == "next" || a.hasClass("next") ||
                                number == currentNumber + 1)
                    }?.absUrl("href")
                Triple(parsed, nextLink,
                    MissavPageNavigation.parseControls(html, request).sorts)
            }
            items = (items + entries).distinctBy { it.path }
            next = nextUrl?.takeUnless { it == request }
            sorts = pageSorts
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "目录加载失败"
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (sorts.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedButton(onClick = { sortMenuOpen = true }) {
                    Text("排序：" + (sorts.firstOrNull {
                        it.value == currentSort
                    }?.label ?: "Default"))
                }
                DropdownMenu(expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }) {
                    sorts.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                sortMenuOpen = false
                                currentSort = option.value
                                items = emptyList()
                                next = null
                                request = MissavSource.BASE + rootPath +
                                    if (currentSort.isEmpty()) "" else "?sort=" + currentSort
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) {
            Text("读取目录失败：" + error, Modifier.padding(16.dp))
            TextButton(onClick = { request = request.substringBefore('?') + "?page=1" }) {
                Text("重试")
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(145.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.path }) { entry ->
                OutlinedCard(onClick = { onSelect(entry) }) {
                    Text(entry.label, Modifier.padding(12.dp))
                }
            }
        }
        if (next != null && !loading) {
            TextButton(onClick = { request = next!! }, modifier = Modifier.fillMaxWidth()) {
                Text("加载更多目录")
            }
        }
    }
}
