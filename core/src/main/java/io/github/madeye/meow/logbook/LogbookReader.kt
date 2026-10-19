package io.github.madeye.meow.logbook

import io.github.madeye.meow.core.MeowCore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
enum class LogbookLevel(val wire: String) {
    @SerialName("info") Info("info"),
    @SerialName("warning") Warning("warning"),
    @SerialName("error") Error("error"),
}

/** One logbook entry, as the engine stores it (`meow_common::logbook::Entry`). */
@Serializable
data class LogbookEntry(
    /** Assigned at the first occurrence, increasing across restarts. */
    val id: Long,
    /** Raised on every write of the entry, a folded repeat included. */
    val rev: Long = 0,
    /** First occurrence, RFC 3339. */
    val time: String,
    /** Latest occurrence, once a repeat has been folded in. */
    val last: String? = null,
    /** Occurrences folded into this entry. */
    val count: Int = 1,
    /** e.g. `engine.start`, `proxy-provider.update`, `group.switch`. */
    val kind: String,
    /** Unknown to this app (a newer engine's) reads as info. */
    val level: LogbookLevel = LogbookLevel.Info,
    /** A group, provider or listener name; empty for none. */
    val subject: String = "",
    /** One English sentence; for a folded entry, the latest occurrence's. */
    val message: String,
    val fields: JsonObject = JsonObject(emptyMap()),
)

/** One page of entries, newest first. */
@Serializable
data class LogbookPage(
    val entries: List<LogbookEntry> = emptyList(),
    /** The latest write: passed as `since`, it polls for what comes next. */
    val rev: Long = 0,
    /** More matching entries exist past this page. */
    val more: Boolean = false,
    val error: String? = null,
)

/**
 * Reads the engine's logbook — the persistent record of starts, stops,
 * unclean exits, config and provider failures and group switches — from the
 * database in the engine home, from this process: the engine writes it in
 * `:vpn`, and it stays readable after the VPN stops.
 */
class LogbookReader(private val dir: () -> File) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** See [MeowCore.nativeReadLogbook]; throws [IOException] when the logbook cannot be read. */
    suspend fun read(
        before: Long = 0,
        since: Long = 0,
        limit: Int = 0,
        level: LogbookLevel? = null,
    ): LogbookPage = withContext(Dispatchers.IO) {
        val answer = MeowCore.nativeReadLogbook(dir().absolutePath, before, since, limit, level?.wire.orEmpty())
            ?: throw IOException("no answer from the engine library")
        val page = try {
            json.decodeFromString(LogbookPage.serializer(), answer)
        } catch (e: SerializationException) {
            throw IOException(e.message, e)
        }
        page.error?.let { throw IOException(it) }
        page
    }
}
