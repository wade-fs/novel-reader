package com.novel.reader.util

import com.novel.reader.model.BookEntry
import com.novel.reader.model.BookSourceType
import org.junit.Assert.*
import org.junit.Test

class BookLibraryCodecTest {

    @Test
    fun testEncodeAndDecodeMultipleBooks() {
        val entries = listOf(
            BookEntry(
                id = "gh_wade_novel_sex",
                title = "萬界風月樓",
                sourceType = BookSourceType.GITHUB_TREE,
                localPath = "/data/user/0/com.novel.reader/files/gh_wade_novel_sex",
                sourceUriOrUrl = "https://github.com/wade-fs/novel/tree/main/sex",
                chapterCount = 88,
                addedAt = 1000L,
                lastOpenedAt = 2000L
            ),
            BookEntry(
                id = "archive_demo_zip",
                title = "測試小說\\含特殊字元",
                sourceType = BookSourceType.LOCAL_ARCHIVE,
                localPath = "/data/files/imported_demo_zip",
                sourceUriOrUrl = "demo.zip",
                chapterCount = 12,
                addedAt = 1500L,
                lastOpenedAt = 2500L
            ),
            BookEntry(
                id = "folder_12345",
                title = "外部資料夾",
                sourceType = BookSourceType.LOCAL_DIRECTORY,
                localPath = null,
                sourceUriOrUrl = "content://com.android.externalstorage.documents/tree/primary%3Anovel",
                chapterCount = 5,
                addedAt = 1800L,
                lastOpenedAt = 1900L
            )
        )

        val encoded = BookEntryCodec.encode(entries)
        val decoded = BookEntryCodec.decode(encoded)

        assertEquals(entries, decoded)
    }

    @Test
    fun testDecodeEmptyOrBlank() {
        assertTrue(BookEntryCodec.decode(null).isEmpty())
        assertTrue(BookEntryCodec.decode("").isEmpty())
        assertTrue(BookEntryCodec.decode("   ").isEmpty())
    }
}
