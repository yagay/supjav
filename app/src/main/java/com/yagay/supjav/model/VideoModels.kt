package com.yagay.supjav.model

data class VideoItem(
    val id: String,
    val title: String,
    val coverUrl: String = "",
    val detailUrl: String,
    val code: String = "",
    val tags: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val publishedAt: String = "",
    val durationLabel: String = ""
)

data class VideoServer(
    val id: String,
    val name: String,
    val link: String,
    val detailUrl: String,
    /** Only set from a successful selected-wrapper HTTP redirect. Never logged. */
    val verifiedPlayerUrl: String? = null,
    /** CD part on the page. Null when the source has no verifiable part. */
    val part: String? = null
)

data class BrowseLink(val kind: FeedKind, val label: String, val path: String)

data class VideoDetail(
    val video: VideoItem,
    val description: String,
    val tags: List<String>,
    val actors: List<String>,
    val servers: List<VideoServer>,
    val related: List<VideoItem>,
    val categories: List<String> = emptyList(),
    val makers: List<String> = emptyList(),
    val taxonomy: List<BrowseLink> = emptyList()
)

enum class FeedKind(val title: String) {
    LATEST("最新"), DAY("今日热门"), WEEK("本周热门"), MONTH("本月热门"),
    CATEGORY("分类"), TAG("标签"), ACTOR("演员"), MAKER("制作商"), SEARCH("搜索")
}

data class PlaybackSource(
    val url: String,
    val mimeType: String,
    val referer: String,
    val includeOrigin: Boolean = true
)

sealed interface ResolveResult {
    data class Playable(val source: PlaybackSource) : ResolveResult
    data class Unavailable(
        val reason: String,
        val verifiedPlayerUrl: String? = null
    ) : ResolveResult
    data object VerificationRequired : ResolveResult
}
