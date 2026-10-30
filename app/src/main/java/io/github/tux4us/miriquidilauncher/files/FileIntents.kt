// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Öffnen und Teilen über den `FileProvider` der App; jede Freigabe gilt nur für die genannten Dateien. */
object FileIntents {
    private fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** `false`, wenn keine App die Datei öffnen kann oder der Pfad nicht freigegeben werden kann. */
    fun open(context: Context, file: File): Boolean = try {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, FileTypes.mimeOf(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    /** Teilt Dateien (Ordner werden nicht geteilt). `false`, wenn nichts zu teilen ist oder der Vorgang scheitert. */
    fun share(context: Context, files: List<File>): Boolean = try {
        val plain = files.filter { it.isFile }
        if (plain.isEmpty()) {
            false
        } else {
            val uris = plain.map { uriFor(context, it) }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND)
                    .setType(FileTypes.mimeOf(plain[0].name))
                    .putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newRawUri(null, uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}
