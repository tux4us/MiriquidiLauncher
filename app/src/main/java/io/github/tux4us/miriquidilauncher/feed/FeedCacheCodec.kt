// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/** Lesen und Schreiben des lokalen Zwischenspeichers (JSON). Ein beschädigter Speicher ergibt `null`. */
object FeedCacheCodec {
    private const val VERSION = 1

    fun encode(snapshot: FeedSnapshot): String = buildString {
        append("{\"v\":").append(VERSION)
        append(",\"fetched\":").append(snapshot.fetchedAtMillis)
        append(",\"feeds\":[")
        snapshot.feeds.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("{\"id\":").append(f.id).append(",\"title\":"); string(f.title)
            append(",\"link\":"); nullableString(f.link); append('}')
        }
        append("],\"items\":[")
        snapshot.items.forEachIndexed { i, item ->
            if (i > 0) append(',')
            append("{\"id\":").append(item.id).append(",\"feedId\":").append(item.feedId)
            append(",\"title\":"); string(item.title)
            append(",\"author\":"); nullableString(item.author)
            append(",\"url\":"); nullableString(item.url)
            append(",\"body\":"); string(item.body)
            append(",\"pub\":").append(item.publishedSeconds)
            append(",\"unread\":").append(item.unread)
            append(",\"starred\":").append(item.starred).append('}')
        }
        append("],\"pending\":[")
        snapshot.pendingRead.sorted().forEachIndexed { i, id ->
            if (i > 0) append(',')
            append(id)
        }
        append("]}")
    }

    fun decode(text: String): FeedSnapshot? = try {
        val root = MiniJson.parse(text).asObject()
        if (root == null || root["v"].asDouble()?.toInt() != VERSION) {
            null
        } else {
            FeedSnapshot(
                feeds = root["feeds"].asArray().orEmpty().mapNotNull { entry ->
                    val o = entry.asObject() ?: return@mapNotNull null
                    FeedInfo(o["id"].asDouble()?.toLong() ?: return@mapNotNull null, o["title"].asString().orEmpty(), o["link"].asString())
                },
                items = root["items"].asArray().orEmpty().mapNotNull { entry ->
                    val o = entry.asObject() ?: return@mapNotNull null
                    FeedItem(
                        id = o["id"].asDouble()?.toLong() ?: return@mapNotNull null,
                        feedId = o["feedId"].asDouble()?.toLong() ?: 0L,
                        title = o["title"].asString().orEmpty(),
                        author = o["author"].asString(),
                        url = o["url"].asString(),
                        body = o["body"].asString().orEmpty(),
                        publishedSeconds = o["pub"].asDouble()?.toLong() ?: 0L,
                        unread = o["unread"] as? Boolean ?: false,
                        starred = o["starred"] as? Boolean ?: false,
                    )
                },
                fetchedAtMillis = root["fetched"].asDouble()?.toLong() ?: 0L,
                pendingRead = root["pending"].asArray().orEmpty().mapNotNull { it.asDouble()?.toLong() }.toSet(),
            )
        }
    } catch (ignored: MiniJson.JsonException) {
        null
    }

    private fun StringBuilder.nullableString(value: String?) {
        if (value == null) append("null") else string(value)
    }

    private fun StringBuilder.string(value: String) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
