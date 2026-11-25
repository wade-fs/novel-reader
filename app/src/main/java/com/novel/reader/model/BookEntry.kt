package com.novel.reader.model

enum class BookSourceType(val displayLabel: String) {
    BUILTIN_ASSET("內建"),
    GITHUB_TREE("GitHub"),
    GITHUB_SINGLE("GitHub 單章"),
    LOCAL_ARCHIVE("壓縮檔"),
    LOCAL_DIRECTORY("本機目錄"),
    WEB_NOVEL("網路小說")
}

data class BookMetadata(
    val title: String = "",
    val subtitle: String = "",
    val author: String = "",
    val series: String = "",
    val era: String = "",
    val description: String = "",
    val tags: List<String> = emptyList()
)

data class BookEntry(
    val id: String,
    val title: String,
    val sourceType: BookSourceType,
    val localPath: String? = null,
    val sourceUriOrUrl: String? = null,
    val chapterCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val subtitle: String = "",
    val author: String = "",
    val series: String = "",
    val era: String = "",
    val description: String = "",
    val tags: List<String> = emptyList()
) {
    fun withMetadata(meta: BookMetadata, fallbackTitle: String = title): BookEntry {
        return copy(
            title = meta.title.ifBlank { fallbackTitle },
            subtitle = meta.subtitle.ifBlank { subtitle },
            author = meta.author.ifBlank { author },
            series = meta.series.ifBlank { series },
            era = meta.era.ifBlank { era },
            description = meta.description.ifBlank { description },
            tags = if (meta.tags.isNotEmpty()) meta.tags else tags
        )
    }
}
