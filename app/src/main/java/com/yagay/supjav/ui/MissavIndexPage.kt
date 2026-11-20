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
    LaunchedEffect(rootPath, request) {
        loading = true
        error = null
        try {
            val (entries, nextUrl) = withContext(Dispatchers.IO) {
                val html = app.source.fetch(request)
                val parsed = app.source.parseBrowseOptions(html, request)
                    .filter { candidate ->
                        candidate.path.startsWith(rootPath.trimEnd('/') + "/")
                    }
                val doc = Jsoup.parse(html, request)
                val nextLink = doc.select("a[rel=next][href], a.next[href], .pagination a[href]")
                    .firstOrNull { a ->
                        val href = a.absUrl("href")
                        val label = a.text().trim().lowercase()
                        href.startsWith(MissavSource.BASE + rootPath + "?page=") &&
                            (a.attr("rel") == "next" || a.hasClass("next") ||
                             label == "next" || label == "›" || label == "»" ||
                             label.toIntOrNull() != null)
                    }?.absUrl("href")
                parsed to nextLink
            }
            items = (items + entries).distinctBy { it.path }
            next = nextUrl?.takeUnless { it == request }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "目录加载失败"
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
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
