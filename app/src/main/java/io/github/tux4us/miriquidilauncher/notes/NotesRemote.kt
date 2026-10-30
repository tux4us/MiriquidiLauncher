// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

import io.github.tux4us.miriquidilauncher.feed.NewsApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Der Server hat mit dem Statuscode [code] geantwortet, der keinen Erfolg bedeutet. */
class NotesHttpException(val code: Int) : IOException("HTTP $code")

/**
 * Zugriff auf die Nextcloud-Notes-Schnittstelle (v1) mit Basic-Auth (Benutzer und App-Passwort), ausschließlich über
 * HTTPS und nur zur eigenen Nextcloud. Weiterleitungen werden nur innerhalb desselben Hosts befolgt.
 */
class NotesRemote(
    private val baseUrl: String,
    private val user: String,
    private val password: String,
) {
    suspend fun list(): List<RemoteNote> = NotesApi.parseNotes(request("GET", NotesApi.LIST_PATH, null))

    /** Prüft Adresse und Zugang, ohne Inhalte zu laden. */
    suspend fun check() {
        request("GET", NotesApi.LIST_PATH + "?exclude=content&chunkSize=1", null)
    }

    suspend fun create(content: String, category: String, favorite: Boolean, modifiedSeconds: Long): RemoteNote =
        NotesApi.parseNote(request("POST", NotesApi.LIST_PATH, NotesApi.encodeNote(content, category, favorite, modifiedSeconds)))
            ?: throw IOException("Antwort unlesbar")

    suspend fun update(id: Long, content: String, category: String, favorite: Boolean, modifiedSeconds: Long): RemoteNote =
        NotesApi.parseNote(request("PUT", NotesApi.notePath(id), NotesApi.encodeNote(content, category, favorite, modifiedSeconds)))
            ?: throw IOException("Antwort unlesbar")

    /** Eine bereits gelöschte Notiz (404) gilt als erfolgreich gelöscht. */
    suspend fun delete(id: Long) {
        try {
            request("DELETE", NotesApi.notePath(id), null)
        } catch (e: NotesHttpException) {
            if (e.code != 404) throw e
        }
    }

    private suspend fun request(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        var target = URL(NotesApi.url(baseUrl, path))
        val host = target.host
        val payload = body?.toByteArray(Charsets.UTF_8)
        for (hop in 0..MAX_REDIRECTS) {
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Authorization", NewsApi.basicAuth(user, password))
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (payload != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(payload.size)
                    connection.outputStream.use { it.write(payload) }
                }
                val code = connection.responseCode
                if (code in REDIRECTS && hop < MAX_REDIRECTS) {
                    val next = connection.getHeaderField("Location")
                        ?.let { runCatching { URI(target.toString()).resolve(it).toURL() }.getOrNull() }
                    if (next != null && next.protocol == "https" && next.host.equals(host, ignoreCase = true)) {
                        target = next
                        continue
                    }
                }
                if (code !in 200..299) throw NotesHttpException(code)
                return@withContext readBody(connection)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    private fun readBody(connection: HttpURLConnection): String =
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("Antwort zu groß")
            }
            out.toString(Charsets.UTF_8.name())
        }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val USER_AGENT = "MiriquidiLauncher/1.0 (FOSS Android launcher; https://github.com/tux4us)"
    }
}
