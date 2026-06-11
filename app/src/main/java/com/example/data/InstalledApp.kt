package com.example.data

data class InstalledApp(
    val name: String,
    val packageName: String,
    val sourceDir: String,
    val isSystemApp: Boolean
)
