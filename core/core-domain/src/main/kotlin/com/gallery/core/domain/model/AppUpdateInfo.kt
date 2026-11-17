package com.gallery.core.domain.model

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val fileSizeBytes: Long,
    val publishedAt: String
)
