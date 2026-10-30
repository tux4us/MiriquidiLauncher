// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/** Ein Speicher (intern oder Wechselmedium) mit seinem Wurzelpfad. */
data class Volume(val path: String, val primary: Boolean)

object FileVolumes {
    /** Gemeinsamer interner Speicher und eingesteckte Wechselmedien, soweit das System sie meldet. */
    fun list(context: Context): List<Volume> {
        val primary = Environment.getExternalStorageDirectory()
        val volumes = ArrayList<Volume>()
        volumes.add(Volume(primary.absolutePath, primary = true))
        // Die app-eigenen Ordner jedes Speichers zeigen auf dessen Wurzel: .../Android/data/<Paket>/files
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val marker = "/Android/data/"
            val index = dir.absolutePath.indexOf(marker)
            if (index > 0) {
                val root = File(dir.absolutePath.substring(0, index))
                if (root.absolutePath != primary.absolutePath && root.canRead() && volumes.none { it.path == root.absolutePath }) {
                    volumes.add(Volume(root.absolutePath, primary = false))
                }
            }
        }
        return volumes
    }

    /** Übliche Ordner im internen Speicher, die es gibt. */
    fun quickAccess(): List<File> = listOf(
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_MUSIC,
        Environment.DIRECTORY_MOVIES,
    ).map { Environment.getExternalStoragePublicDirectory(it) }.filter { it.isDirectory }

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q ||
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
}
