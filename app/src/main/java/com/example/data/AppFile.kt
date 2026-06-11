package com.example.data

sealed class AppFile {
    abstract val name: String
    abstract val size: Long
    abstract val isEditable: Boolean

    data class ApkAsset(
        override val name: String,
        val relativePath: String,
        override val size: Long,
        override val isEditable: Boolean = true,
        val appPackageName: String
    ) : AppFile()

    data class SafDocument(
        override val name: String,
        val uriString: String,
        val relativePath: String,
        override val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean,
        override val isEditable: Boolean = true
    ) : AppFile()
}
