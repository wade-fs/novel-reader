package com.novel.reader.model

enum class BookSourceType(val displayLabel: String) {
    BUILTIN_ASSET("內建"),
    GITHUB_TREE("GitHub"),
    GITHUB_SINGLE("GitHub 單章"),
    LOCAL_ARCHIVE("壓縮檔"),
    LOCAL_DIRECTORY("本機目錄"),
    WEB_NOVEL("網路小說")
}

data class BookEntry(
    val id: String,
    val title: String,
    val sourceType: BookSourceType,
    val localPath: String? = null,
    val sourceUriOrUrl: String? = null,
    val chapterCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis()
)
