package com.example.data.repository

import com.example.data.local.BookmarkDao
import com.example.data.local.BookmarkEntity
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import com.example.data.local.HistoryDao
import com.example.data.local.HistoryEntity
import kotlinx.coroutines.flow.Flow

class StreamRepository(
    private val downloadDao: DownloadDao,
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao
) {
    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAll()
    val bookmarks: Flow<List<BookmarkEntity>> = bookmarkDao.observeAll()
    val history: Flow<List<HistoryEntity>> = historyDao.observeRecent()

    fun observeIsBookmarked(url: String): Flow<Boolean> = bookmarkDao.observeIsBookmarked(url)

    suspend fun seedInitialBookmarksIfEmpty() {
        if (bookmarkDao.count() == 0) {
            val defaults = listOf(
                BookmarkEntity(
                    title = "Internet Archive Video",
                    url = "https://archive.org/details/movies",
                    category = "Streaming"
                ),
                BookmarkEntity(
                    title = "Wikimedia Commons Video",
                    url = "https://commons.wikimedia.org/wiki/Category:Videos",
                    category = "Edukasi"
                ),
                BookmarkEntity(
                    title = "Blender Open Movies",
                    url = "https://studio.blender.org/films/",
                    category = "Film Terbuka"
                ),
                BookmarkEntity(
                    title = "Pexels Free Videos",
                    url = "https://www.pexels.com/videos/",
                    category = "Klip Bebas"
                )
            )
            defaults.forEach { bookmarkDao.insert(it) }
        }
    }

    suspend fun addBookmark(title: String, url: String, category: String = "Umum") {
        bookmarkDao.insert(
            BookmarkEntity(
                title = title.ifBlank { url },
                url = url,
                category = category
            )
        )
    }

    suspend fun removeBookmarkByUrl(url: String) = bookmarkDao.deleteByUrl(url)

    suspend fun removeBookmarkById(id: Long) = bookmarkDao.deleteById(id)

    suspend fun recordHistory(title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("streamvault://")) return
        historyDao.insert(
            HistoryEntity(
                title = title.ifBlank { url },
                url = url
            )
        )
    }

    suspend fun deleteHistoryItem(id: Long) = historyDao.deleteById(id)

    suspend fun clearHistory() = historyDao.clearAll()

    suspend fun getDownloadById(id: Long): DownloadEntity? = downloadDao.getById(id)

    suspend fun findDownloadBySourceUrl(url: String): DownloadEntity? =
        downloadDao.findBySourceUrl(url)

    suspend fun insertDownload(entity: DownloadEntity): Long = downloadDao.insert(entity)

    suspend fun updateDownloadProgress(
        id: Long,
        status: String,
        downloadedBytes: Long,
        totalBytes: Long,
        progressPercent: Int,
        statusDetail: String,
        errorMessage: String? = null
    ) {
        downloadDao.updateProgress(
            id = id,
            status = status,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            progressPercent = progressPercent,
            statusDetail = statusDetail,
            errorMessage = errorMessage
        )
    }

    suspend fun markDownloadCompleted(
        id: Long,
        status: String,
        localPath: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        statusDetail: String,
        mediaStoreUri: String?
    ) {
        downloadDao.markCompleted(
            id = id,
            status = status,
            localPath = localPath,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = totalBytes,
            statusDetail = statusDetail,
            mediaStoreUri = mediaStoreUri
        )
    }

    suspend fun updateMediaStoreUri(id: Long, uri: String) {
        downloadDao.updateMediaStoreUri(id, uri)
    }

    suspend fun deleteDownload(id: Long) = downloadDao.deleteById(id)
}
