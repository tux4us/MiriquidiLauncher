// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import io.github.tux4us.miriquidilauncher.feed.NewsApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Der Server hat mit dem Statuscode [code] geantwortet, der keinen Erfolg bedeutet. */
class ChatHttpException(val code: Int) : IOException("HTTP $code")

/**
 * Zugriff auf Nextcloud Talk mit Basic-Auth (Benutzer und App-Passwort), ausschließlich über HTTPS und nur zur
 * eigenen Nextcloud. Weiterleitungen werden nur innerhalb desselben Hosts befolgt. Wird die aufrufende Coroutine
 * abgebrochen, schließt das die Verbindung (wichtig für das Long-Polling).
 */
class ChatRemote(
    private val baseUrl: String,
    private val user: String,
    private val password: String,
) {
    private class Response(val code: Int, val body: String)

    /** Körper einer Anfrage: entweder [bytes] oder eine [file], die ohne vollständiges Laden gesendet wird. */
    private class Payload(val contentType: String, val bytes: ByteArray? = null, val file: File? = null)

    /** Prüft Adresse, Zugang und Talk und liefert die Benutzer-Id (ersatzweise den Anmeldenamen). */
    suspend fun check(): String {
        val rooms = request("GET", ChatApi.roomsUrl(baseUrl), null, TIMEOUT_MS)
        if (ChatApi.parseRooms(rooms.body) == null) throw ChatHttpException(404)
        val id = try {
            ChatApi.parseUserId(request("GET", ChatApi.userUrl(baseUrl), null, TIMEOUT_MS).body)
        } catch (e: ChatHttpException) {
            null
        }
        return id ?: user
    }

    suspend fun rooms(): List<ChatRoom> =
        ChatApi.parseRooms(request("GET", ChatApi.roomsUrl(baseUrl), null, TIMEOUT_MS).body) ?: throw IOException("Antwort unlesbar")

    /**
     * Nachrichten aus [token]. Mit [future] wartet der Server bis zu [ChatLogic.POLL_SECONDS] auf neue Nachrichten nach
     * [lastKnownId] und antwortet sonst mit 304 (leere Liste); ohne [future] kommen die [limit] Nachrichten vor [lastKnownId].
     */
    suspend fun messages(token: String, future: Boolean, lastKnownId: Long, limit: Int): List<ChatMessage> {
        require(ChatApi.isSafeToken(token))
        val query = ChatApi.messagesQuery(future, lastKnownId, limit, ChatLogic.POLL_SECONDS)
        val timeout = if (future) (ChatLogic.POLL_SECONDS + 20) * 1000 else TIMEOUT_MS
        val r = request("GET", ChatApi.chatUrl(baseUrl, token, query), null, timeout)
        if (r.code == 304 || r.body.isBlank()) return emptyList()
        return ChatApi.parseMessages(r.body) ?: throw IOException("Antwort unlesbar")
    }

    suspend fun send(token: String, text: String, replyToId: Long?, referenceId: String): ChatMessage {
        require(ChatApi.isSafeToken(token))
        val r = request("POST", ChatApi.chatUrl(baseUrl, token), ChatApi.encodeSend(text, replyToId, referenceId), TIMEOUT_MS)
        return ChatApi.parseSingleMessage(r.body) ?: throw IOException("Antwort unlesbar")
    }

    /** Markiert das Gespräch bis [lastReadId] als gelesen. */
    suspend fun markRead(token: String, lastReadId: Long) {
        require(ChatApi.isSafeToken(token))
        request("POST", ChatApi.readUrl(baseUrl, token), ChatApi.encodeRead(lastReadId), TIMEOUT_MS)
    }

    /** Fügt die Reaktion [emoji] hinzu ([add]) oder nimmt die eigene zurück. */
    suspend fun react(token: String, messageId: Long, emoji: String, add: Boolean) {
        require(ChatApi.isSafeToken(token) && ChatApi.isValidReaction(emoji) && messageId > 0)
        if (add) {
            request("POST", ChatApi.reactionUrl(baseUrl, token, messageId), ChatApi.encodeReaction(emoji), TIMEOUT_MS)
        } else {
            request("DELETE", ChatApi.reactionDeleteUrl(baseUrl, token, messageId, emoji), null, TIMEOUT_MS)
        }
    }

    /**
     * Legt über CalDAV einen Termin im Standardkalender („personal“, sonst dem ersten) an. [buildIcs] bekommt die
     * Datei-Kennung (UID) und liefert die iCalendar-Datei. Gibt den Namen des Kalenders zurück.
     */
    suspend fun createCalendarEvent(userId: String, uid: String, buildIcs: (String) -> String): String {
        val home = ChatReminder.calendarHomeUrl(baseUrl, userId)
        // Die Abfrage der Kalender nutzt PROPFIND, das nicht jede Android-Version über HttpURLConnection sendet. Schlägt sie
        // fehl oder liefert nichts Lesbares, gilt der Standardkalender „personal“, den Nextcloud für jedes Konto anlegt.
        val calendars = try {
            ChatReminder.parseCalendars(
                call(
                    "PROPFIND", home,
                    Payload("application/xml; charset=utf-8", bytes = ChatReminder.PROPFIND_BODY.toByteArray(Charsets.UTF_8)),
                    TIMEOUT_MS, headers = mapOf("Depth" to "1"),
                ).body,
            )
        } catch (e: ChatHttpException) {
            if (e.code == 401 || e.code == 403) throw e
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        val calendar = ChatReminder.pickCalendar(calendars)
        val folder = if (calendar != null) {
            ChatReminder.resolveHref(baseUrl, calendar.href) ?: throw IOException("Kalenderadresse nicht erlaubt")
        } else {
            home + "personal/"
        }
        val url = folder.trimEnd('/') + "/" + ChatReminder.fileName(uid)
        call(
            "PUT", url, Payload("text/calendar; charset=utf-8", bytes = buildIcs(uid).toByteArray(Charsets.UTF_8)),
            TIMEOUT_MS, ifNoneMatch = true,
        )
        return calendar?.name ?: "personal"
    }

    /** Ordner für Anhänge laut Server; bei fehlender oder unlesbarer Antwort der Standard „Talk“. */
    suspend fun attachmentFolder(): String {
        val folder = try {
            ChatApi.parseAttachmentFolder(request("GET", ChatApi.capabilitiesUrl(baseUrl), null, TIMEOUT_MS).body)
        } catch (e: ChatHttpException) {
            null
        }
        return ChatApi.sanitizeFolder(folder)
    }

    /**
     * Lädt [file] in den Ordner [folder] von [userId] hoch. Fehlt der Ordner (409), wird er angelegt; gelingt das nicht,
     * landet die Datei im Wurzelordner. Gibt es den Namen schon, wird `Name (2).ext` usw. versucht. Gibt den Pfad der
     * Datei relativ zum Dateibereich des Benutzers zurück (für [share]).
     */
    suspend fun upload(userId: String, folder: String, name: String, file: File): String {
        var target = folder
        var folderTried = false
        var attempt = 1
        while (attempt <= MAX_NAME_ATTEMPTS) {
            val candidate = ChatApi.numberedName(name, attempt)
            try {
                call(
                    "PUT", ChatApi.davFileUrl(baseUrl, userId, target, candidate),
                    Payload("application/octet-stream", file = file), UPLOAD_TIMEOUT_MS, ifNoneMatch = true,
                )
                return ChatApi.relativePath(target, candidate)
            } catch (e: ChatHttpException) {
                when {
                    e.code == 412 -> attempt++ // Name ist vergeben
                    e.code == 409 && target.isNotEmpty() && !folderTried -> { // Ordner fehlt
                        folderTried = true
                        if (!createFolders(userId, target)) target = ""
                    }
                    else -> throw e
                }
            }
        }
        throw IOException("Kein freier Dateiname")
    }

    /** Legt [folder] mit allen Oberordnern an; `false`, wenn das nicht möglich ist. */
    private suspend fun createFolders(userId: String, folder: String): Boolean {
        for (dir in ChatApi.folderChain(folder)) {
            try {
                call("MKCOL", ChatApi.davFolderUrl(baseUrl, userId, dir), null, TIMEOUT_MS)
            } catch (e: ChatHttpException) {
                if (e.code != 405) return false // 405: Ordner ist schon da
            } catch (e: IOException) {
                return false
            }
        }
        return true
    }

    /** Teilt die hochgeladene Datei ([relativePath] aus [upload]) im Gespräch [token]; die Nachricht kommt über den Chat zurück. */
    suspend fun share(token: String, relativePath: String, caption: String, referenceId: String) {
        require(ChatApi.isSafeToken(token))
        val form = ChatApi.encodeShareForm(token, relativePath, caption, referenceId).toByteArray(Charsets.UTF_8)
        call("POST", ChatApi.sharesUrl(baseUrl), Payload("application/x-www-form-urlencoded", bytes = form), TIMEOUT_MS)
    }

    private suspend fun request(method: String, url: String, body: String?, readTimeoutMs: Int): Response =
        call(method, url, body?.let { Payload("application/json; charset=utf-8", bytes = it.toByteArray(Charsets.UTF_8)) }, readTimeoutMs)

    private suspend fun call(
        method: String, url: String, payload: Payload?, readTimeoutMs: Int, ifNoneMatch: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        suspendCancellableCoroutine { continuation ->
            // Je Anfrage eine eigene Verbindung, damit das Abbrechen des Pollings keine gleichzeitige Sendung trifft.
            val holder = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation { holder.get()?.disconnect() }
            executor.execute {
                try {
                    val response = execute(method, url, payload, readTimeoutMs, holder, ifNoneMatch, headers)
                    if (continuation.isActive) continuation.resume(response)
                } catch (e: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        }

    private fun execute(
        method: String, url: String, payload: Payload?, readTimeoutMs: Int,
        holder: AtomicReference<HttpURLConnection?>, ifNoneMatch: Boolean, headers: Map<String, String>,
    ): Response {
        var target = URL(url)
        val host = target.host
        for (hop in 0..MAX_REDIRECTS) {
            val connection = target.openConnection() as HttpURLConnection
            holder.set(connection)
            try {
                connection.instanceFollowRedirects = false
                setMethod(connection, method)
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Authorization", NewsApi.basicAuth(user, password))
                connection.setRequestProperty("OCS-APIRequest", "true")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (ifNoneMatch) connection.setRequestProperty("If-None-Match", "*")
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                if (payload != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", payload.contentType)
                    val file = payload.file
                    if (file != null) {
                        connection.setFixedLengthStreamingMode(file.length())
                        file.inputStream().use { input -> connection.outputStream.use { input.copyTo(it, 32 * 1024) } }
                    } else {
                        val bytes = payload.bytes ?: ByteArray(0)
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                }
                val code = connection.responseCode
                // Bei Dateisendungen keine Weiterleitung (der Körper wäre nicht erneut sendbar).
                if (code in REDIRECTS && hop < MAX_REDIRECTS && payload?.file == null) {
                    val next = connection.getHeaderField("Location")
                        ?.let { runCatching { URI(target.toString()).resolve(it).toURL() }.getOrNull() }
                    if (next != null && next.protocol == "https" && next.host.equals(host, ignoreCase = true)) {
                        target = next
                        continue
                    }
                }
                if (code == 304) return Response(code, "")
                if (code !in 200..299) throw ChatHttpException(code)
                return Response(code, readBody(connection))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    /** `HttpURLConnection` kennt WebDAV-Methoden wie MKCOL nicht; dann wird das (geschützte) Feld `method` gesetzt. */
    private fun setMethod(connection: HttpURLConnection, method: String) {
        try {
            connection.requestMethod = method
        } catch (e: ProtocolException) {
            try {
                forceMethod(connection, method)
            } catch (t: ReflectiveOperationException) {
                throw IOException("Methode $method nicht unterstützt", t)
            } catch (t: RuntimeException) {
                throw IOException("Methode $method nicht unterstützt", t)
            }
        }
    }

    private fun forceMethod(connection: HttpURLConnection, method: String) {
        val field = HttpURLConnection::class.java.getDeclaredField("method")
        field.isAccessible = true
        field.set(connection, method)
        // Android: HTTPS-Verbindungen leiten an eine innere Verbindung weiter, die das Feld selbst liest.
        var type: Class<*>? = connection.javaClass
        while (type != null) {
            val inner = try {
                type.getDeclaredField("delegate").also { it.isAccessible = true }.get(connection) as? HttpURLConnection
            } catch (e: ReflectiveOperationException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            if (inner != null) {
                field.set(inner, method)
                break
            }
            type = type.superclass
        }
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
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val UPLOAD_TIMEOUT_MS = 120_000
        const val MAX_NAME_ATTEMPTS = 20
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val USER_AGENT = "MiriquidiLauncher/1.0 (FOSS Android launcher; https://github.com/tux4us)"

        /** Gemeinsamer Pool für alle Anfragen; Fäden enden nach Leerlauf. */
        val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "chat-http").apply { isDaemon = true } }
    }
}
