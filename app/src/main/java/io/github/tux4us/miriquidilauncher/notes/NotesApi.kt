// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/** Adressen sowie Lesen und Schreiben des JSON der Nextcloud-Notes-Schnittstelle (v1). Ohne Android-Klassen. */
object NotesApi {
    fun url(baseUrl: String, path: String): String = "$baseUrl/index.php/apps/notes/api/v1/$path"

    /** Alle Notizen mit Inhalt. */
    const val LIST_PATH = "notes"

    fun notePath(id: Long): String = "notes/$id"

    fun parseNotes(json: String): List<RemoteNote> {
        val array = MiniJson.parse(json).asArray() ?: return emptyList()
        return array.mapNotNull { parseObject(it.asObject()) }
    }

    fun parseNote(json: String): RemoteNote? = parseObject(MiniJson.parse(json).asObject())

    private fun parseObject(obj: Map<String, Any?>?): RemoteNote? {
        obj ?: return null
        val id = obj["id"].asDouble()?.toLong() ?: return null
        val content = (obj["content"].asString() ?: "").take(NotesLogic.MAX_NOTE_CHARS)
        return RemoteNote(
            id = id,
            etag = obj["etag"].asString().orEmpty(),
            title = obj["title"].asString().orEmpty(),
            content = content,
            category = NotesLogic.sanitizeCategory(obj["category"].asString().orEmpty()),
            favorite = obj["favorite"] as? Boolean ?: false,
            modified = obj["modified"].asDouble()?.toLong() ?: 0L,
            readonly = obj["readonly"] as? Boolean ?: false,
        )
    }

    /** JSON-Rumpf für Anlegen (POST) und Ändern (PUT). Der Titel wird vom Server aus der ersten Zeile gebildet. */
    fun encodeNote(content: String, category: String, favorite: Boolean, modifiedSeconds: Long): String =
        "{\"content\":${NotesJson.str(content)},\"category\":${NotesJson.str(category)}," +
            "\"favorite\":$favorite,\"modified\":$modifiedSeconds}"
}

/** Schreibhilfen für JSON. */
object NotesJson {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

/** Textformat des lokalen Zustands (Favoriten, Zuordnung zu Server-Notizen). */
object NotesStateCodec {
    private const val VERSION = 1

    fun encode(state: NotesState): String {
        val fav = state.favorites.sorted().joinToString(",") { NotesJson.str(it) }
        val idx = state.index.joinToString(",") {
            "{\"path\":${NotesJson.str(it.path)},\"id\":${it.remoteId},\"etag\":${NotesJson.str(it.etag)}," +
                "\"hash\":${NotesJson.str(it.hash)},\"fav\":${it.favorite}}"
        }
        return "{\"v\":$VERSION,\"favorites\":[$fav],\"index\":[$idx]}"
    }

    /** Leerer Zustand bei unlesbaren oder fremden Daten. */
    fun decode(text: String): NotesState {
        val root = try {
            MiniJson.parse(text).asObject()
        } catch (e: MiniJson.JsonException) {
            null
        } ?: return NotesState()
        if (root["v"].asDouble()?.toInt() != VERSION) return NotesState()
        val favorites = root["favorites"].asArray()?.mapNotNull { it.asString()?.takeIf(NotesLogic::isSafePath) }?.toSet().orEmpty()
        val index = root["index"].asArray()?.mapNotNull { entry ->
            val o = entry.asObject() ?: return@mapNotNull null
            val path = o["path"].asString()?.takeIf(NotesLogic::isSafePath) ?: return@mapNotNull null
            val id = o["id"].asDouble()?.toLong() ?: return@mapNotNull null
            IndexEntry(path, id, o["etag"].asString().orEmpty(), o["hash"].asString().orEmpty(), o["fav"] as? Boolean ?: false)
        }.orEmpty()
        return NotesState(favorites, index)
    }
}
