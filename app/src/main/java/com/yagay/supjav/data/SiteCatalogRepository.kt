package com.yagay.supjav.data

import android.os.SystemClock
import com.yagay.supjav.model.FeedKind
import com.yagay.supjav.model.VideoDetail
import com.yagay.supjav.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single catalogue entry point for home, directory, search, related works and
 * detail pages. The website transport remains SupjavSource; the repository
 * owns directory freshness and merges actual site links into offline storage.
 */
class SiteCatalogRepository(
    private val source: SupjavSource,
    private val categories: CategoryStore
) {
    private val directoryLock = Mutex()
    private var lastScan: SupjavSource.DirectoryScan? = null
    private var lastDepth = 0
    private var lastScanAt = 0L
    private val directoryTtlMs = 10 * 60_000L

    suspend fun directory(
        depth: Int = 2, force: Boolean = false
    ): SupjavSource.DirectoryScan = directoryLock.withLock {
        val now = SystemClock.elapsedRealtime()
        val cached = lastScan
        if (!force && cached != null && lastDepth >= depth &&
            now - lastScanAt in 0 until directoryTtlMs) return@withLock cached
        val scan = source.browseDirectory(depth)
        if (scan.options.isNotEmpty()) {
            withContext(Dispatchers.IO) { categories.save(scan.options) }
        }
        lastScan = scan
        lastDepth = depth
        lastScanAt = now
        scan
    }

    fun directoryMatches(
        entries: List<SupjavSource.BrowseOption>, kind: FeedKind, input: String
    ): List<SupjavSource.BrowseOption> {
        val query = input.trim()
        return entries.asSequence()
            .filter { it.kind == kind }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.path.contains(query, ignoreCase = true) ||
                    it.value.contains(query, ignoreCase = true)
            }
            .distinctBy { it.path }.toList()
    }

    suspend fun listing(
        kind: FeedKind, value: String = "", page: Int = 1, nextUrl: String? = null
    ): SupjavSource.ListingPage = source.listPage(kind, value, page, nextUrl)

    suspend fun detail(video: VideoItem): VideoDetail {
        val detail = source.detail(video)
        if (detail.taxonomy.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                categories.save(detail.taxonomy.map {
                    SupjavSource.BrowseOption(
                        it.kind, it.path.substringAfterLast('/'), it.label, it.path
                    )
                })
            }
        }
        return detail
    }
}
