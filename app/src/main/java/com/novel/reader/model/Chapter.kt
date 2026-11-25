package com.novel.reader.model

data class Chapter(
    val id: String,                 // 例: "vol1_chap001" 或章節網址
    val volumeIndex: Int,           // 1..N
    val volumeName: String,         // 例: "第一卷"
    val chapterNumber: Int,         // 1..N
    val title: String,              // 例: "第一章　上都煙塵"
    val assetPath: String? = null,  // assets 內的相對路徑
    val fileUriString: String? = null, // 本機檔案或快取檔案的 URI
    val remoteUrl: String? = null   // 網路小說章節原始網址（按需抓取並快取）
)
