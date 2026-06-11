package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.data.AppFile
import com.example.data.BackupEntity
import com.example.data.BackupRepository
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

object AppPackageReader {

    /**
     * Extracts known editable/browseable assets directly from the APK of an installed package
     */
    fun readApkAssets(context: Context, packageName: String): List<AppFile.ApkAsset> {
        val result = mutableListOf<AppFile.ApkAsset>()
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val apkFile = File(appInfo.sourceDir)
            if (!apkFile.exists()) return emptyList()

            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("assets/") && !entry.isDirectory) {
                        // Check extension to see if it's text/editable
                        val name = entry.name.substringAfterLast('/')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val editableExtensions = setOf("json", "xml", "txt", "csv", "ini", "cfg", "properties", "dat", "yaml", "yml", "db")
                        if (editableExtensions.contains(ext) || entry.size < 1024 * 1024) { // small files under 1MB
                            result.add(
                                AppFile.ApkAsset(
                                    name = name,
                                    relativePath = entry.name,
                                    size = entry.size,
                                    isEditable = editableExtensions.contains(ext),
                                    appPackageName = packageName
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.sortedBy { it.name }
    }

    /**
     * Reads the text content of an asset inside the target app's APK
     */
    fun readAssetContent(context: Context, packageName: String, relativePath: String): String {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val apkFile = File(appInfo.sourceDir)
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(relativePath) ?: return "קובץ לא נמצא"
                zip.getInputStream(entry).use { input ->
                    return input.bufferedReader().use { it.readText() }
                }
            }
        } catch (e: Exception) {
            return "שגיאה בקריאת הקובץ: ${e.localizedMessage}"
        }
    }

    /**
     * Helper to initiate Document Tree selection for an app-specific data directory
     */
    fun getSafFolderIntent(packageName: String): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        // Attempt to pre-target Android/data/<packageName> to make selection instant for users
        val encFolder = Uri.encode("Android/data/$packageName/files")
        val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A$encFolder")
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        return intent
    }

    /**
     * Lists files from a granted SAF folder tree URI
     */
    fun listFilesFromSaf(context: Context, treeUriString: String): List<AppFile.SafDocument> {
        val results = mutableListOf<AppFile.SafDocument>()
        try {
            val treeUri = Uri.parse(treeUriString)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            traverseDirectory(rootDoc, "", results)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    private fun traverseDirectory(
        dir: DocumentFile,
        currentPath: String,
        results: MutableList<AppFile.SafDocument>
    ) {
        val files = dir.listFiles()
        for (file in files) {
            val nextPath = if (currentPath.isEmpty()) file.name.orEmpty() else "$currentPath/${file.name}"
            if (file.isDirectory) {
                // Add directory record
                results.add(
                    AppFile.SafDocument(
                        name = file.name.orEmpty(),
                        uriString = file.uri.toString(),
                        relativePath = nextPath,
                        size = 0,
                        lastModified = file.lastModified(),
                        isDirectory = true,
                        isEditable = false
                    )
                )
                traverseDirectory(file, nextPath, results)
            } else {
                val name = file.name.orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase()
                val isEditable = setOf("json", "xml", "txt", "csv", "ini", "cfg", "properties", "dat", "yaml", "yml").contains(ext)
                results.add(
                    AppFile.SafDocument(
                        name = name,
                        uriString = file.uri.toString(),
                        relativePath = nextPath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        isDirectory = false,
                        isEditable = isEditable
                    )
                )
            }
        }
    }

    /**
     * Reads text content of a SAF Document Uri
     */
    fun readSafFileContent(context: Context, fileUriString: String): String {
        return try {
            val fileUri = Uri.parse(fileUriString)
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                input.bufferedReader().use { it.readText() }
            } ?: "לא ניתן לפתוח קובץ זה"
        } catch (e: Exception) {
            "שגיאה בקריאת הקובץ: ${e.localizedMessage}"
        }
    }

    /**
     * Backs up a file to private app storage, writes a record in Room database, and then overwrites the original file
     */
    suspend fun backupAndWriteSafFileContent(
        context: Context,
        fileUriString: String,
        content: String,
        appName: String,
        packageName: String,
        backupRepository: BackupRepository
    ): Boolean {
        try {
            val fileUri = Uri.parse(fileUriString)
            val docFile = DocumentFile.fromSingleUri(context, fileUri) ?: return false
            val fileName = docFile.name ?: "file"
            val totalSize = docFile.length()

            // 1. PERFORM AUTOMATIC BACKUP BEFORE WRITING
            val backupDir = File(context.filesDir, "backups/$packageName").apply { mkdirs() }
            val timestamp = System.currentTimeMillis()
            val backupFile = File(backupDir, "backup_${timestamp}_$fileName")

            context.contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(backupFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 2. LOG THE BACKUP TO ROOM DB
            val backupEntity = BackupEntity(
                packageName = packageName,
                appName = appName,
                originalFileName = fileName,
                originalFilePath = fileUriString,
                backupFilePath = backupFile.absolutePath,
                timestamp = timestamp,
                fileSize = if (totalSize > 0) totalSize else backupFile.length(),
                note = "גיבוי אוטומטי לפני עריכה"
            )
            backupRepository.insert(backupEntity)

            // 3. WRITE THE NEW CONTENT TO THE ORIGINAL FILE
            context.contentResolver.openOutputStream(fileUri, "rwt")?.use { output ->
                output.bufferedWriter().use { it.write(content) }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Replaces a SAF file with content from a local sourceUri (user file selection), performing auto-backup first
     */
    suspend fun backupAndReplaceSafFile(
        context: Context,
        fileUriString: String,
        sourceUri: Uri,
        appName: String,
        packageName: String,
        backupRepository: BackupRepository
    ): Boolean {
        try {
            val fileUri = Uri.parse(fileUriString)
            val docFile = DocumentFile.fromSingleUri(context, fileUri) ?: return false
            val fileName = docFile.name ?: "file"
            val totalSize = docFile.length()

            // 1. AUTO BACKUP THE ORIGINAL FILE FIRST
            val backupDir = File(context.filesDir, "backups/$packageName").apply { mkdirs() }
            val timestamp = System.currentTimeMillis()
            val backupFile = File(backupDir, "backup_${timestamp}_$fileName")

            context.contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(backupFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Save backup log in DB
            val backupEntity = BackupEntity(
                packageName = packageName,
                appName = appName,
                originalFileName = fileName,
                originalFilePath = fileUriString,
                backupFilePath = backupFile.absolutePath,
                timestamp = timestamp,
                fileSize = if (totalSize > 0) totalSize else backupFile.length(),
                note = "גיבוי אוטומטי לפני החלפת קובץ"
            )
            backupRepository.insert(backupEntity)

            // 2. WRITE THE NEW SELECTED FILE'S BYTES TO THE ORIGINAL FILE
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(fileUri, "rwt")?.use { output ->
                    input.copyTo(output)
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Restores a backed up file to its original SAF location
     */
    fun restoreBackup(context: Context, backup: BackupEntity): Boolean {
        return try {
            val originalUri = Uri.parse(backup.originalFilePath)
            val backupFile = File(backup.backupFilePath)
            if (!backupFile.exists()) return false

            // Overwrite original with backup file's bytes
            backupFile.inputStream().use { input ->
                context.contentResolver.openOutputStream(originalUri, "rwt")?.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
