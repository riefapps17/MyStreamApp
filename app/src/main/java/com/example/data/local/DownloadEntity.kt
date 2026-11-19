package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val fileName: String,
    val sourceUrl: String,
    val pageUrl: String,
    val pageTitle: String,
    val format: String,
    val localPath: String,
    val mediaStoreUri: String? = null,
    val mimeType: String,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: String = DownloadStatus.QUEUED.name,
    val progressPercent: Int = 0,
    val statusDetail: String = "",
    val errorMessage: String? = null,
    val referer: String = "",
    val userAgent: String = "",
    val cookie: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
