package audiobook.core

/**
 * Progress sync: merge logic and the sync document shape from CONTRACT.md
 * sections 3-4.
 *
 * commonMain-safe like Mp4Chapters.kt: no java.*, no platform clock. Callers
 * pass in "now" rather than this module reading a system clock, which also
 * makes the merge logic deterministic to test.
 *
 * Unlike the MP4 parser, this has no Python reference and no cross-check.
 * There's no independent oracle to diff it against (ffprobe plays that role
 * for chapters), and every Kotlin target compiles this same source, so
 * there's no second port to diverge from. Correctness here rests on tests
 * against CONTRACT.md's rules directly.
 */

const val SYNC_SCHEMA_VERSION = 1

data class ProgressRecord(
    val contentId: String,
    val positionMs: Long,
    val speed: Double,
    val updatedAt: Long,
    val deviceId: String,
    val finished: Boolean,
)

data class Bookmark(
    val contentId: String,
    val positionMs: Long,
    val note: String?,
    val createdAt: Long,
)

data class SyncDocument(
    val schema: Int,
    val deviceId: String,
    val writtenAt: Long,
    val progress: List<ProgressRecord>,
    val bookmarks: List<Bookmark>,
)

/** Thrown when a sync document declares a schema newer than this client understands. */
class UnsupportedSchemaError(val remoteSchema: Int) : Exception(
    "sync document is schema $remoteSchema, this app only understands up to $SYNC_SCHEMA_VERSION; update required"
)

sealed class ProgressMerge {
    data class Resolved(val winner: ProgressRecord) : ProgressMerge()

    /**
     * Positions diverge by more than the guard window on different devices.
     * CONTRACT.md section 3: never resolve this silently, clock skew would
     * otherwise delete real listening progress. [local] is kept as the
     * merged value until the caller has the user pick.
     */
    data class NeedsUserChoice(val local: ProgressRecord, val remote: ProgressRecord) : ProgressMerge()
}

private const val DIVERGENCE_GUARD_MS = 120_000L

/** CONTRACT.md section 3's conflict resolution rule for one content_id. */
fun mergeProgress(local: ProgressRecord?, remote: ProgressRecord?): ProgressMerge {
    if (local == null && remote == null) throw IllegalArgumentException("mergeProgress: both records null")
    if (local == null) return ProgressMerge.Resolved(remote!!)
    if (remote == null) return ProgressMerge.Resolved(local)
    require(local.contentId == remote.contentId) { "mergeProgress: content_id mismatch" }

    val diverges = kotlin.math.abs(remote.positionMs - local.positionMs) > DIVERGENCE_GUARD_MS
    if (diverges && remote.deviceId != local.deviceId) {
        return ProgressMerge.NeedsUserChoice(local, remote)
    }
    val winner = when {
        local.updatedAt > remote.updatedAt -> local
        remote.updatedAt > local.updatedAt -> remote
        local.positionMs >= remote.positionMs -> local
        else -> remote
    }
    return ProgressMerge.Resolved(winner)
}

/**
 * CONTRACT.md section 4: union keyed on (content_id, position_ms), add-only
 * (v1 has no deletion tombstones). A duplicate key by coincidence keeps
 * whichever was created first; the contract doesn't specify, so this is an
 * arbitrary but deterministic tie-break.
 */
fun mergeBookmarks(local: List<Bookmark>, remote: List<Bookmark>): List<Bookmark> {
    val byKey = LinkedHashMap<Pair<String, Long>, Bookmark>()
    for (b in local + remote) {
        val key = b.contentId to b.positionMs
        val existing = byKey[key]
        if (existing == null || b.createdAt < existing.createdAt) byKey[key] = b
    }
    return byKey.values.toList()
}

data class SyncMergeResult(
    val document: SyncDocument,
    val conflicts: List<ProgressMerge.NeedsUserChoice>,
)

/**
 * Merges two sync documents. Throws [UnsupportedSchemaError] rather than
 * merging (and thus rather than ever writing back) if [remote] is a schema
 * newer than this client understands, per CONTRACT.md section 4.
 */
fun mergeSyncDocuments(
    local: SyncDocument,
    remote: SyncDocument,
    thisDeviceId: String,
    nowMs: Long,
): SyncMergeResult {
    if (remote.schema > SYNC_SCHEMA_VERSION) throw UnsupportedSchemaError(remote.schema)

    val localByBook = local.progress.associateBy { it.contentId }
    val remoteByBook = remote.progress.associateBy { it.contentId }
    val mergedProgress = mutableListOf<ProgressRecord>()
    val conflicts = mutableListOf<ProgressMerge.NeedsUserChoice>()

    for (contentId in (localByBook.keys + remoteByBook.keys)) {
        when (val result = mergeProgress(localByBook[contentId], remoteByBook[contentId])) {
            is ProgressMerge.Resolved -> mergedProgress += result.winner
            is ProgressMerge.NeedsUserChoice -> {
                conflicts += result
                mergedProgress += result.local
            }
        }
    }

    return SyncMergeResult(
        SyncDocument(
            schema = SYNC_SCHEMA_VERSION,
            deviceId = thisDeviceId,
            writtenAt = nowMs,
            progress = mergedProgress,
            bookmarks = mergeBookmarks(local.bookmarks, remote.bookmarks),
        ),
        conflicts,
    )
}

/**
 * Where the sync document (CONTRACT.md section 4) actually lives. Drive,
 * WebDAV, or local disk are all just implementations of this; none exist
 * yet. Raw JSON in and out, not [SyncDocument], so a backend never needs to
 * know this module's serialization.
 */
interface SyncBackend {
    /** Null if this account has never written a sync document (first run). */
    fun pull(): String?
    fun push(json: String)
}

// ------------------------------------------------------------------- JSON

private fun jsonEscape(s: String): String {
    val sb = StringBuilder()
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c == '\r' -> sb.append("\\r")
        c == '\t' -> sb.append("\\t")
        c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
        else -> sb.append(c)
    }
    return sb.toString()
}

private fun opt(v: String?): String = if (v == null) "null" else "\"${jsonEscape(v)}\""

fun encodeSyncDocument(doc: SyncDocument): String {
    val progress = doc.progress.joinToString(",") { p ->
        """{"content_id":"${jsonEscape(p.contentId)}","position_ms":${p.positionMs},""" +
            """"speed":${p.speed},"updated_at":${p.updatedAt},""" +
            """"device_id":"${jsonEscape(p.deviceId)}","finished":${p.finished}}"""
    }
    val bookmarks = doc.bookmarks.joinToString(",") { b ->
        """{"content_id":"${jsonEscape(b.contentId)}","position_ms":${b.positionMs},""" +
            """"note":${opt(b.note)},"created_at":${b.createdAt}}"""
    }
    return """{"schema":${doc.schema},"device_id":"${jsonEscape(doc.deviceId)}",""" +
        """"written_at":${doc.writtenAt},"progress":[$progress],"bookmarks":[$bookmarks]}"""
}

/**
 * Minimal parser for exactly the sync document shape above; not a general
 * JSON parser. Throws [Mp4Error]'s sibling-in-spirit for anything else: a
 * plain [IllegalArgumentException], since a malformed sync document is a
 * data problem, not a file-format problem.
 */
fun decodeSyncDocument(json: String): SyncDocument {
    val p = JsonCursor(json)
    p.expect('{')
    var schema: Int? = null
    var deviceId: String? = null
    var writtenAt: Long? = null
    var progress: List<ProgressRecord> = emptyList()
    var bookmarks: List<Bookmark> = emptyList()
    p.objectFields { key ->
        when (key) {
            "schema" -> schema = p.readNumber().toInt()
            "device_id" -> deviceId = p.readString()
            "written_at" -> writtenAt = p.readNumber().toLong()
            "progress" -> progress = p.readArray { readProgressRecord(p) }
            "bookmarks" -> bookmarks = p.readArray { readBookmark(p) }
            else -> p.skipValue()
        }
    }
    return SyncDocument(
        schema = requireNotNull(schema) { "sync document missing schema" },
        deviceId = requireNotNull(deviceId) { "sync document missing device_id" },
        writtenAt = requireNotNull(writtenAt) { "sync document missing written_at" },
        progress = progress,
        bookmarks = bookmarks,
    )
}

private fun readProgressRecord(p: JsonCursor): ProgressRecord {
    p.expect('{')
    var contentId: String? = null
    var positionMs: Long? = null
    var speed: Double? = null
    var updatedAt: Long? = null
    var deviceId: String? = null
    var finished = false
    p.objectFields { key ->
        when (key) {
            "content_id" -> contentId = p.readString()
            "position_ms" -> positionMs = p.readNumber().toLong()
            "speed" -> speed = p.readNumber()
            "updated_at" -> updatedAt = p.readNumber().toLong()
            "device_id" -> deviceId = p.readString()
            "finished" -> finished = p.readBoolean()
            else -> p.skipValue()
        }
    }
    return ProgressRecord(
        contentId = requireNotNull(contentId) { "progress record missing content_id" },
        positionMs = requireNotNull(positionMs) { "progress record missing position_ms" },
        speed = requireNotNull(speed) { "progress record missing speed" },
        updatedAt = requireNotNull(updatedAt) { "progress record missing updated_at" },
        deviceId = requireNotNull(deviceId) { "progress record missing device_id" },
        finished = finished,
    )
}

private fun readBookmark(p: JsonCursor): Bookmark {
    p.expect('{')
    var contentId: String? = null
    var positionMs: Long? = null
    var note: String? = null
    var createdAt: Long? = null
    p.objectFields { key ->
        when (key) {
            "content_id" -> contentId = p.readString()
            "position_ms" -> positionMs = p.readNumber().toLong()
            "note" -> note = p.readStringOrNull()
            "created_at" -> createdAt = p.readNumber().toLong()
            else -> p.skipValue()
        }
    }
    return Bookmark(
        contentId = requireNotNull(contentId) { "bookmark missing content_id" },
        positionMs = requireNotNull(positionMs) { "bookmark missing position_ms" },
        note = note,
        createdAt = requireNotNull(createdAt) { "bookmark missing created_at" },
    )
}

/** Hand-rolled, single-pass, only handles the shapes this file writes. */
private class JsonCursor(private val s: String) {
    var i = 0

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    fun expect(c: Char) {
        skipWs()
        require(i < s.length && s[i] == c) { "expected '$c' at $i" }
        i++
    }

    private fun peek(): Char {
        skipWs()
        return s[i]
    }

    /** Calls [onField] with each key in the object at the cursor; consumes the closing brace. */
    fun objectFields(onField: (String) -> Unit) {
        skipWs()
        if (peek() == '}') {
            i++
            return
        }
        while (true) {
            skipWs()
            val key = readString()
            expect(':')
            onField(key)
            skipWs()
            when (s[i]) {
                ',' -> {
                    i++
                }
                '}' -> {
                    i++
                    return
                }
                else -> throw IllegalArgumentException("expected ',' or '}' at $i")
            }
        }
    }

    fun <T> readArray(readElement: () -> T): List<T> {
        expect('[')
        val out = mutableListOf<T>()
        skipWs()
        if (peek() == ']') {
            i++
            return out
        }
        while (true) {
            out += readElement()
            skipWs()
            when (s[i]) {
                ',' -> {
                    i++
                }
                ']' -> {
                    i++
                    return out
                }
                else -> throw IllegalArgumentException("expected ',' or ']' at $i")
            }
        }
    }

    fun readString(): String {
        skipWs()
        require(s[i] == '"') { "expected string at $i" }
        i++
        val sb = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i + 1, i + 5)
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("bad escape at $i")
                }
                i++
            } else {
                sb.append(s[i])
                i++
            }
        }
        i++
        return sb.toString()
    }

    fun readStringOrNull(): String? {
        skipWs()
        return if (peek() == 'n') {
            require(s.substring(i, i + 4) == "null") { "expected null at $i" }
            i += 4
            null
        } else {
            readString()
        }
    }

    fun readNumber(): Double {
        skipWs()
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return s.substring(start, i).toDouble()
    }

    fun readBoolean(): Boolean {
        skipWs()
        return when {
            s.startsWith("true", i) -> {
                i += 4
                true
            }
            s.startsWith("false", i) -> {
                i += 5
                false
            }
            else -> throw IllegalArgumentException("expected boolean at $i")
        }
    }

    fun skipValue() {
        skipWs()
        when (s[i]) {
            '"' -> readString()
            '{' -> {
                i++
                objectFields { skipValue() }
            }
            '[' -> readArray { skipValue() }
            't', 'f' -> readBoolean()
            'n' -> {
                i += 4
            }
            else -> readNumber()
        }
    }
}
