package com.example.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.data.InstalledApp

object AppManager {
    fun getInstalledApps(context: Context, query: String = ""): List<InstalledApp> {
        val pm = context.packageManager
        // Retrieve all applications safely
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = apps.map { appInfo ->
            InstalledApp(
                name = pm.getApplicationLabel(appInfo).toString(),
                packageName = appInfo.packageName,
                sourceDir = appInfo.sourceDir,
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        
        val filtered = if (query.isNotEmpty()) {
            result.filter { 
                it.name.contains(query, ignoreCase = true) || 
                it.packageName.contains(query, ignoreCase = true) 
            }
        } else {
            result
        }
        
        // Sort: user/launcher apps first, then system apps alphabetically
        return filtered.sortedWith(
            compareBy<InstalledApp> { it.isSystemApp }
                .thenBy { it.name.lowercase() }
        )
    }
}
