package io.github.meko123456.ridetogether.realtime.rtdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One message from the Realtime Database's REST streaming API: a request made with
 * `Accept: text/event-stream` gets the data at a location, then every change to it, as
 * server-sent events.
 */
internal sealed interface RtdbStreamEvent {
    /** Replace the data at [path], relative to the location being listened to. Null data deletes it. */
    data class Put(val path: String, val data: JsonElement) : RtdbStreamEvent

    /** For each child of [data], replace the child of [path] with that name. */
    data class Patch(val path: String, val data: JsonObject) : RtdbStreamEvent

    /** Sent while nothing changes. Proof the connection is alive, and nothing else. */
    data object KeepAlive : RtdbStreamEvent

    /** The rules stopped allowing this read. The server closes the stream after sending it. */
    data object Cancel : RtdbStreamEvent

    /** The credential expired. The stream is closed, and listening again needs a fresh one. */
    data object AuthRevoked : RtdbStreamEvent

    companion object {
        /** The stream event an SSE event stands for, or null for one this client does not know. */
        fun from(event: ServerSentEvent): RtdbStreamEvent? = when (event.name) {
            "put", "patch" -> {
                val body = runCatching { Json.parseToJsonElement(event.data).jsonObject }.getOrNull()
                val path = body?.get("path")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                val data = body?.get("data") ?: JsonNull
                when {
                    path == null -> null
                    event.name == "put" -> Put(path, data)
                    else -> (data as? JsonObject)?.let { Patch(path, it) }
                }
            }
            "keep-alive" -> KeepAlive
            "cancel" -> Cancel
            "auth_revoked" -> AuthRevoked
            else -> null
        }
    }
}

/** One event as the server-sent-events format frames it: a name, and its data lines joined. */
internal data class ServerSentEvent(val name: String, val data: String)

/**
 * Turns the lines of a `text/event-stream` body into events, one line at a time, so it can sit on
 * a network stream that never ends. Follows the parts of the format the database uses: `event:`
 * and `data:` fields, a blank line ending an event, and lines starting with ':' as comments.
 */
internal class ServerSentEventParser {
    private var name: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line, without its line ending. Returns the event this line completed, if any. */
    fun feed(line: String): ServerSentEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> name = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun flush(): ServerSentEvent? {
        val event = if (hasData || name != null) ServerSentEvent(name ?: "message", data.toString()) else null
        name = null
        data.clear()
        hasData = false
        return event
    }
}

/**
 * The data at one listened-to location, kept up to date by applying [RtdbStreamEvent]s.
 *
 * Follows the database's own idea of absence: a node with no children does not exist, so deleting
 * the last child of an object deletes the object too, and the whole location reads as null.
 */
internal class RtdbTree {
    var root: JsonElement? = null
        private set

    fun apply(event: RtdbStreamEvent) {
        when (event) {
            is RtdbStreamEvent.Put -> root = setAt(root, segments(event.path), event.data)
            is RtdbStreamEvent.Patch -> {
                val base = segments(event.path)
                for ((key, value) in event.data) root = setAt(root, base + segments(key), value)
            }
            RtdbStreamEvent.KeepAlive, RtdbStreamEvent.Cancel, RtdbStreamEvent.AuthRevoked -> Unit
        }
    }

    private fun segments(path: String): List<String> = path.split('/').filter(String::isNotEmpty)

    private fun setAt(node: JsonElement?, path: List<String>, value: JsonElement): JsonElement? {
        if (path.isEmpty()) return normalise(value)
        val children = (node as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val child = setAt(children[path.first()], path.drop(1), value)
        if (child == null) children.remove(path.first()) else children[path.first()] = child
        return if (children.isEmpty()) null else JsonObject(children)
    }

    /** Null and empty objects are absence, all the way down. */
    private fun normalise(value: JsonElement): JsonElement? = when (value) {
        is JsonNull -> null
        is JsonObject -> value.mapNotNull { (k, v) -> normalise(v)?.let { k to it } }
            .takeIf { it.isNotEmpty() }?.let { JsonObject(it.toMap()) }
        else -> value
    }
}
