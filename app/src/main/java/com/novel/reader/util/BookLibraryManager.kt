package com.novel.reader.util

import android.content.Context
import android.content.SharedPreferences
import com.novel.reader.model.BookEntry
import com.novel.reader.model.BookSourceType
import java.io.File

/**
 * 純 Kotlin 字串序列/反序列化工具（不依賴 Android org.json 實作，可在 JVM Unit Test 直接驗證）
 */
object BookEntryCodec {

    private const val FIELD_SEP = "\u001F"
    private const val RECORD_SEP = "\u001E"

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(FIELD_SEP, "\\u001F")
            .replace(RECORD_SEP, "\\u001E")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when {
                    value.startsWith("\\u001F", i) -> {
                        sb.append(FIELD_SEP)
                        i += 6
                    }
                    value.startsWith("\\u001E", i) -> {
                        sb.append(RECORD_SEP)
                        i += 6
                    }
                    value[i + 1] == 'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    value[i + 1] == 'r' -> {
                        sb.append('\r')
                        i += 2
                    }
                    value[i + 1] == '\\' -> {
                        sb.append('\\')
                        i += 2
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun encode(entries: List<BookEntry>): String {
        return entries.joinToString(RECORD_SEP) { e ->
            listOf(
                escape(e.id),
                escape(e.title),
                e.sourceType.name,
                escape(e.localPath ?: ""),
                escape(e.sourceUriOrUrl ?: ""),
                e.chapterCount.toString(),
                e.addedAt.toString(),
                e.lastOpenedAt.toString()
            ).joinToString(FIELD_SEP)
        }
    }

    fun decode(raw: String?): List<BookEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val result = mutableListOf<BookEntry>()
        for (record in raw.split(RECORD_SEP)) {
            if (record.isBlank()) continue
            val parts = record.split(FIELD_SEP)
            if (parts.size < 8) continue
            val id = unescape(parts[0])
            val title = unescape(parts[1])
            if (id.isBlank()) continue
            val sourceType = try {
                BookSourceType.valueOf(parts[2])
            } catch (_: Exception) {
                BookSourceType.LOCAL_ARCHIVE
            }
            val localPath = unescape(parts[3]).ifEmpty { null }
            val sourceUriOrUrl = unescape(parts[4]).ifEmpty { null }
            val chapterCount = parts[5].toIntOrNull() ?: 0
            val addedAt = parts[6].toLongOrNull() ?: 0L
            val lastOpenedAt = parts[7].toLongOrNull() ?: addedAt
            result.add(
                BookEntry(
                    id = id,
                    title = title.ifBlank { id },
                    sourceType = sourceType,
                    localPath = localPath,
                    sourceUriOrUrl = sourceUriOrUrl,
                    chapterCount = chapterCount,
                    addedAt = addedAt,
                    lastOpenedAt = lastOpenedAt
                )
            )
        }
        return result
    }
}

/**
 * 書單管理與持久化（儲存所有增加或下載過的小說，支援開啟與刪除）
 */
class BookLibraryManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("novel_book_library", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BOOKS = "saved_books_v1"
        private const val KEY_LAST_ACTIVE_BOOK_ID = "last_active_book_id"
        private const val KEY_INITIALIZED_BUILTINS = "initialized_builtins_v1"
    }

    fun getBooks(): List<BookEntry> {
        return BookEntryCodec.decode(prefs.getString(KEY_BOOKS, null))
            .sortedByDescending { it.lastOpenedAt }
    }

    fun getBookById(bookId: String): BookEntry? {
        return getBooks().firstOrNull { it.id == bookId }
    }

    fun addOrUpdateBook(entry: BookEntry) {
        val current = getBooks().toMutableList()
        val existingIdx = current.indexOfFirst { it.id == entry.id }
        val updated = if (existingIdx != -1) {
            val old = current[existingIdx]
            entry.copy(addedAt = old.addedAt)
        } else {
            entry
        }
        if (existingIdx != -1) {
            current[existingIdx] = updated
        } else {
            current.add(updated)
        }
        saveBooks(current)
    }

    fun markOpened(bookId: String) {
        val now = System.currentTimeMillis()
        val current = getBooks().map {
            if (it.id == bookId) it.copy(lastOpenedAt = now) else it
        }
        prefs.edit()
            .putString(KEY_BOOKS, BookEntryCodec.encode(current))
            .putString(KEY_LAST_ACTIVE_BOOK_ID, bookId)
            .apply()
    }

    fun getLastActiveBookId(): String? {
        return prefs.getString(KEY_LAST_ACTIVE_BOOK_ID, null)?.takeIf { it.isNotBlank() }
    }

    fun hasInitializedBuiltins(): Boolean {
        return prefs.getBoolean(KEY_INITIALIZED_BUILTINS, false)
    }

    fun setInitializedBuiltins(initialized: Boolean = true) {
        prefs.edit().putBoolean(KEY_INITIALIZED_BUILTINS, initialized).apply()
    }

    /**
     * 從書單刪除指定書籍，若其本機快取目錄位於 App 內部 filesDir 中則一併刪除以釋放空間。
     */
    fun deleteBook(bookId: String): BookEntry? {
        val current = getBooks().toMutableList()
        val idx = current.indexOfFirst { it.id == bookId }
        if (idx == -1) return null
        val removed = current.removeAt(idx)

        val editor = prefs.edit().putString(KEY_BOOKS, BookEntryCodec.encode(current))
        if (prefs.getString(KEY_LAST_ACTIVE_BOOK_ID, null) == bookId) {
            val nextId = current.maxByOrNull { it.lastOpenedAt }?.id
            if (nextId != null) {
                editor.putString(KEY_LAST_ACTIVE_BOOK_ID, nextId)
            } else {
                editor.remove(KEY_LAST_ACTIVE_BOOK_ID)
            }
        }
        editor.apply()

        // 僅刪除 App 內部 filesDir 下的快取檔案，絕不刪除使用者外部儲存空間目錄
        removed.localPath?.let { path ->
            try {
                val target = File(path)
                val appFilesCanonical = context.filesDir.canonicalPath
                if (target.exists() && target.canonicalPath.startsWith(appFilesCanonical + File.separator)) {
                    target.deleteRecursively()
                }
            } catch (_: Exception) {
            }
        }

        return removed
    }

    private fun saveBooks(books: List<BookEntry>) {
        prefs.edit().putString(KEY_BOOKS, BookEntryCodec.encode(books)).apply()
    }
}
