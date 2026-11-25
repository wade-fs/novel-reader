package com.novel.reader.util

import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object ArchiveHelper {

    private fun shouldExtractEntry(entryName: String): Boolean {
        val lower = entryName.lowercase()
        return lower.endsWith(".md") ||
                lower.endsWith(".txt") ||
                BookMetadataParser.isBookYamlFileName(entryName)
    }

    private fun resolveSafeOutputFile(destDir: File, entryName: String): File? {
        val destCanonical = destDir.canonicalPath
        val outFile = File(destDir, entryName)
        val outCanonical = outFile.canonicalPath
        return if (outCanonical.startsWith(destCanonical + File.separator) || outCanonical == destCanonical) {
            outFile
        } else {
            null
        }
    }

    /**
     * 自動偵測副檔名與魔術字元解壓縮 ZIP、TAR、TAR.GZ (.tgz)
     */
    fun extractArchive(inputStream: InputStream, fileName: String, destDir: File): Boolean {
        destDir.mkdirs()
        val bufferedIn = BufferedInputStream(inputStream)
        bufferedIn.mark(4)
        val header = ByteArray(2)
        val readBytes = bufferedIn.read(header, 0, 2)
        bufferedIn.reset()

        val isGzip = (readBytes == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte())
        val lowerName = fileName.lowercase()

        return when {
            lowerName.endsWith(".zip") -> {
                extractZip(bufferedIn, destDir)
            }
            isGzip || lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") -> {
                extractTar(GZIPInputStream(bufferedIn), destDir)
            }
            lowerName.endsWith(".tar") -> {
                extractTar(bufferedIn, destDir)
            }
            else -> {
                // 預設嘗試以 ZIP 解壓
                try {
                    extractZip(bufferedIn, destDir)
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    /**
     * 解壓 ZIP 串流（同時保留 book.yaml / book.yml 與 .md / .txt）
     */
    fun extractZip(inputStream: InputStream, destDir: File): Boolean {
        return try {
            destDir.mkdirs()
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    val entryName = entry.name
                    if (!entry.isDirectory && shouldExtractEntry(entryName)) {
                        val outFile = resolveSafeOutputFile(destDir, entryName)
                        if (outFile != null) {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 解壓 TAR 串流 (支援標準 UStar/POSIX Tar 格式，同時保留 book.yaml / book.yml 與 .md / .txt)
     */
    fun extractTar(inputStream: InputStream, destDir: File): Boolean {
        return try {
            destDir.mkdirs()
            val buffer = ByteArray(512)
            var bytesRead: Int
            while (true) {
                var totalRead = 0
                while (totalRead < 512) {
                    bytesRead = inputStream.read(buffer, totalRead, 512 - totalRead)
                    if (bytesRead <= 0) break
                    totalRead += bytesRead
                }
                if (totalRead < 512) break

                if (buffer.all { it == 0.toByte() }) {
                    break
                }

                val rawName = String(buffer, 0, 100, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                if (rawName.isEmpty()) continue

                val rawSize = String(buffer, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val fileSize = rawSize.toLongOrNull(8) ?: 0L

                val typeFlag = buffer[156].toInt().toChar()
                val isDir = (typeFlag == '5' || rawName.endsWith("/"))

                val outFile = if (!isDir && shouldExtractEntry(rawName)) {
                    resolveSafeOutputFile(destDir, rawName)
                } else null

                if (outFile != null) {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var remaining = fileSize
                        val copyBuf = ByteArray(4096)
                        while (remaining > 0) {
                            val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                            val r = inputStream.read(copyBuf, 0, toRead)
                            if (r <= 0) break
                            fos.write(copyBuf, 0, r)
                            remaining -= r
                        }
                    }
                } else {
                    skipBytesFully(inputStream, fileSize)
                }

                val padding = (512 - (fileSize % 512)) % 512
                if (padding > 0) {
                    skipBytesFully(inputStream, padding)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun skipBytesFully(inputStream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuffer = ByteArray(4096)
        while (remaining > 0) {
            val toRead = minOf(skipBuffer.size.toLong(), remaining).toInt()
            val r = inputStream.read(skipBuffer, 0, toRead)
            if (r <= 0) break
            remaining -= r
        }
    }
}
