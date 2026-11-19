package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE sourceUrl = :sourceUrl LIMIT 1")
    suspend fun findBySourceUrl(sourceUrl: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes, " +
            "totalBytes = :totalBytes, progressPercent = :progressPercent, " +
            "statusDetail = :statusDetail, errorMessage = :errorMessage WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        status: String,
        downloadedBytes: Long,
        totalBytes: Long,
        progressPercent: Int,
        statusDetail: String,
        errorMessage: String? = null
    )

    @Query(
        "UPDATE downloads SET status = :status, localPath = :localPath, fileName = :fileName, " +
            "mimeType = :mimeType, totalBytes = :totalBytes, downloadedBytes = :totalBytes, " +
            "progressPercent = 100, statusDetail = :statusDetail, mediaStoreUri = :mediaStoreUri, " +
            "cookie = '', errorMessage = NULL WHERE id = :id"
    )
    suspend fun markCompleted(
        id: Long,
        status: String,
        localPath: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        statusDetail: String,
        mediaStoreUri: String?
    )

    @Query("UPDATE downloads SET mediaStoreUri = :uri WHERE id = :id")
    suspend fun updateMediaStoreUri(id: Long, uri: String)

    @Query("UPDATE downloads SET cookie = ''")
    suspend fun clearAllStoredCookies()

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeIsBookmarked(url: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}
