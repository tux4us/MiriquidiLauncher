// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

/** Art eines Gesprächs in Nextcloud Talk (Feld `type`). */
object ChatRoomType {
    const val ONE_TO_ONE = 1
    const val GROUP = 2
    const val PUBLIC = 3
    const val CHANGELOG = 4
    const val FORMER_ONE_TO_ONE = 5
    const val NOTE_TO_SELF = 6
}

/** Ein Gespräch (Raum) aus der Raumliste. [lastActivity] in Sekunden seit 1970. */
data class ChatRoom(
    val token: String,
    val type: Int,
    val name: String,
    val unread: Int,
    val unreadMention: Boolean,
    val lastActivity: Long,
    /** Vorschau der letzten Nachricht mit Autor, leer ohne Nachricht. */
    val lastPreview: String,
    val favorite: Boolean,
    val readOnly: Boolean,
    /** Id der letzten Nachricht, 0 ohne Nachricht. */
    val lastMessageId: Long = 0L,
    /** Benachrichtigungsstufe des Gesprächs in Talk: 0 Standard, 1 immer, 2 nur Erwähnungen, 3 nie. */
    val notificationLevel: Int = 0,
) {
    /** Bei Einzelgesprächen steht kein Absendername in den Nachrichten. */
    val isGroup: Boolean get() = type != ChatRoomType.ONE_TO_ONE && type != ChatRoomType.NOTE_TO_SELF
}

enum class ChatMessageKind { TEXT, SYSTEM, DELETED }

/** Angehängte Datei einer Nachricht; [link] ist `null`, wenn keine HTTPS-Adresse geliefert wurde. */
data class ChatAttachment(val name: String, val link: String?)

/** Auszug der Nachricht, auf die geantwortet wird. */
data class ChatReply(val id: Long, val author: String, val text: String)

/**
 * Eine Nachricht. [text] ist bereits aufbereitet: Platzhalter wie `{mention-user1}` und `{file}` sind ersetzt.
 * [timestamp] in Sekunden seit 1970.
 */
data class ChatMessage(
    val id: Long,
    val actorType: String,
    val actorId: String,
    val actorName: String,
    val timestamp: Long,
    val text: String,
    val kind: ChatMessageKind,
    val reply: ChatReply? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val referenceId: String = "",
) {
    /** `true`, wenn die Nachricht von dem Konto mit der Benutzer-Id [ownId] stammt. */
    fun isOwn(ownId: String): Boolean = ownId.isNotEmpty() && actorType == "users" && actorId == ownId
}

enum class OutgoingState { SENDING, FAILED }

/** Eine noch nicht vom Server bestätigte Nachricht. */
data class Outgoing(
    val localId: String,
    /** Kennung, unter der der Server die Nachricht wieder meldet (Feld `referenceId`). */
    val referenceId: String,
    val token: String,
    val text: String,
    val replyToId: Long?,
    val state: OutgoingState = OutgoingState.SENDING,
    /** Anzeigename der Datei, wenn die Sendung ein Anhang ist; [text] ist dann die Bildunterschrift. */
    val attachmentName: String? = null,
    /** Kopie der Datei im Cache bis zur Bestätigung (für erneutes Senden). */
    val filePath: String? = null,
    /** Name der Datei auf dem Server, sobald sie hochgeladen ist (ein erneuter Versuch lädt nicht noch einmal hoch). */
    val remoteName: String? = null,
)

/** Gespeicherte Zugangsdaten; [userId] ist die Benutzer-Id des Servers (kann vom Anmeldenamen abweichen). */
data class ChatCredentials(val baseUrl: String, val user: String, val password: String, val userId: String)

/** Teil eines Nachrichtentextes: [url] ist gesetzt, wenn der Teil ein anklickbarer HTTPS-Link ist. */
data class ChatTextSegment(val text: String, val url: String? = null)
