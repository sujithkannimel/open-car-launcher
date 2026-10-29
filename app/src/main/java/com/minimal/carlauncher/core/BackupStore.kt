package com.minimal.carlauncher.core

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup files in Download/CarLauncher, so they survive uninstalling the launcher.
 * Written through MediaStore (no permission needed). Files written by an earlier install
 * are only visible with READ_EXTERNAL_STORAGE, which the restore screen asks for.
 */
object BackupStore {

    const val FOLDER = "Download/CarLauncher/"
    private const val PREFIX = "car_launcher_backup_"
    private const val AUTO_TAG = "_auto"
    const val KEEP_AUTO = 7

    data class Entry(val uri: Uri, val name: String, val addedSec: Long)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Writes a backup file; returns its name, or null on failure. */
    fun write(context: Context, auto: Boolean): String? {
        val stamp = if (auto) today() + AUTO_TAG
            else SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val name = "$PREFIX$stamp.json"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(Prefs.backupJson().toByteArray()) }
                ?: return null
            if (auto) prune(context)
            name
        } catch (_: Exception) {
            null
        }
    }

    /** Backup files in the folder, newest first. */
    fun list(context: Context): List<Entry> = try {
        val out = ArrayList<Entry>()
        val proj = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_ADDED)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf(FOLDER, "$PREFIX%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Entry(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                    c.getString(1) ?: "", c.getLong(2)
                )
            }
        }
        // Files copied in over ADB (or written by an earlier install) may not be indexed:
        // with storage permission, scan the folder directly too.
        try {
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), FOLDER)
            val known = out.map { it.name }.toSet()
            dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".json") }
                ?.filter { it.name !in known }
                ?.forEach { out += Entry(Uri.fromFile(it), it.name, it.lastModified() / 1000L) }
        } catch (_: Exception) {}
        out.sortedByDescending { it.addedSec }
    } catch (_: Exception) {
        emptyList()
    }

    fun read(context: Context, e: Entry): Backup.Parsed? = try {
        context.contentResolver.openInputStream(e.uri)?.bufferedReader()?.use { Backup.parse(it.readText()) }
    } catch (_: Exception) {
        null
    }

    /** Keeps the newest [KEEP_AUTO] automatic backups; manual ones are never deleted. */
    private fun prune(context: Context) {
        list(context).filter { it.name.contains(AUTO_TAG) }.drop(KEEP_AUTO).forEach {
            try {
                if (it.uri.scheme == "file") java.io.File(it.uri.path ?: "").delete()
                else context.contentResolver.delete(it.uri, null, null)
            } catch (_: Exception) {}
        }
    }

    /** One automatic backup per day; call from a background thread. */
    fun autoBackupIfDue(context: Context) {
        val day = today()
        if (Prefs.lastAutoBackupDay == day) return
        if (write(context, auto = true) != null) Prefs.lastAutoBackupDay = day
    }
}
