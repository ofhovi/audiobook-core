package audiobook.core.jvm

import audiobook.core.Bookmark
import audiobook.core.ProgressMerge
import audiobook.core.ProgressRecord
import audiobook.core.SyncDocument
import audiobook.core.UnsupportedSchemaError
import audiobook.core.decodeSyncDocument
import audiobook.core.encodeSyncDocument
import audiobook.core.mergeBookmarks
import audiobook.core.mergeProgress
import audiobook.core.mergeSyncDocuments

/**
 * Assertions against CONTRACT.md sections 3-4. No cross-check needed here
 * (see Sync.kt's header), just this against the spec. Wired into
 * scripts/verify.sh.
 */
private var failures = 0

private fun check(name: String, condition: Boolean) {
    if (condition) {
        println("    ok: $name")
    } else {
        println("    FAIL: $name")
        failures++
    }
}

private fun record(pos: Long, updatedAt: Long, deviceId: String = "dev-a", finished: Boolean = false) =
    ProgressRecord("book-1", pos, 1.0, updatedAt, deviceId, finished)

fun main() {
    // Higher updated_at wins outright.
    run {
        val local = record(pos = 1000, updatedAt = 100)
        val remote = record(pos = 2000, updatedAt = 200)
        val result = mergeProgress(local, remote)
        check("higher updated_at wins", result is ProgressMerge.Resolved && result.winner === remote)
    }

    // Equal updated_at: higher position_ms wins.
    run {
        val local = record(pos = 5000, updatedAt = 100)
        val remote = record(pos = 3000, updatedAt = 100)
        val result = mergeProgress(local, remote)
        check(
            "tied updated_at picks higher position_ms",
            result is ProgressMerge.Resolved && result.winner === local
        )
    }

    // Small divergence (<= 120s) on different devices: still auto-resolves by updated_at.
    run {
        val local = record(pos = 10_000, updatedAt = 100, deviceId = "dev-a")
        val remote = record(pos = 100_000, updatedAt = 200, deviceId = "dev-b") // 90s apart
        val result = mergeProgress(local, remote)
        check(
            "divergence within guard auto-resolves",
            result is ProgressMerge.Resolved && result.winner === remote
        )
    }

    // Large divergence (> 120s) on different devices: needs the user.
    run {
        val local = record(pos = 10_000, updatedAt = 100, deviceId = "dev-a")
        val remote = record(pos = 500_000, updatedAt = 200, deviceId = "dev-b") // 490s apart
        val result = mergeProgress(local, remote)
        check(
            "large divergence + different device needs user choice",
            result is ProgressMerge.NeedsUserChoice
        )
    }

    // Large divergence but SAME device (e.g. a seek): auto-resolves, no prompt.
    run {
        val local = record(pos = 10_000, updatedAt = 100, deviceId = "dev-a")
        val remote = record(pos = 500_000, updatedAt = 200, deviceId = "dev-a")
        val result = mergeProgress(local, remote)
        check(
            "large divergence on the same device does not prompt",
            result is ProgressMerge.Resolved && result.winner === remote
        )
    }

    // One side missing (new device, or a book the other device doesn't have yet).
    run {
        val remote = record(pos = 42, updatedAt = 100)
        val result = mergeProgress(null, remote)
        check("missing local resolves to remote", result is ProgressMerge.Resolved && result.winner === remote)
    }

    // Bookmarks: union, add-only, keyed on (content_id, position_ms).
    run {
        val local = listOf(
            Bookmark("book-1", 1000, "local note", createdAt = 10),
            Bookmark("book-1", 2000, "only on local", createdAt = 20),
        )
        val remote = listOf(
            Bookmark("book-1", 1000, "remote note (later)", createdAt = 15),
            Bookmark("book-1", 3000, "only on remote", createdAt = 30),
        )
        val merged = mergeBookmarks(local, remote)
        check("bookmark union has all distinct positions", merged.size == 3)
        check(
            "duplicate key keeps the earlier-created one",
            merged.first { it.positionMs == 1000L }.note == "local note"
        )
    }

    // Schema newer than we understand: refuse rather than merge.
    run {
        val local = SyncDocument(1, "dev-a", 0, emptyList(), emptyList())
        val remote = SyncDocument(2, "dev-b", 0, emptyList(), emptyList())
        val threw = try {
            mergeSyncDocuments(local, remote, "dev-a", nowMs = 0)
            false
        } catch (e: UnsupportedSchemaError) {
            true
        }
        check("newer schema refuses to merge", threw)
    }

    // Full document merge combines progress per content_id and bookmarks.
    run {
        val local = SyncDocument(
            schema = 1,
            deviceId = "dev-a",
            writtenAt = 0,
            progress = listOf(record(pos = 1000, updatedAt = 100)),
            bookmarks = listOf(Bookmark("book-1", 500, null, createdAt = 5)),
        )
        val remote = SyncDocument(
            schema = 1,
            deviceId = "dev-b",
            writtenAt = 0,
            progress = listOf(
                record(pos = 2000, updatedAt = 200),
                ProgressRecord("book-2", 300, 1.0, 50, "dev-b", false),
            ),
            bookmarks = emptyList(),
        )
        val result = mergeSyncDocuments(local, remote, "dev-a", nowMs = 999)
        check("merged document has both books", result.document.progress.size == 2)
        check("merged document keeps the winning record for book-1",
            result.document.progress.first { it.contentId == "book-1" }.positionMs == 2000L)
        check("merged document carries the local-only bookmark", result.document.bookmarks.size == 1)
        check("merged document is stamped with this device and schema",
            result.document.deviceId == "dev-a" && result.document.schema == 1 && result.document.writtenAt == 999L)
        check("no conflicts for a routine merge", result.conflicts.isEmpty())
    }

    // JSON round-trips exactly.
    run {
        val doc = SyncDocument(
            schema = 1,
            deviceId = "b7c2-device",
            writtenAt = 1758231041123,
            progress = listOf(
                ProgressRecord("a3f...", 4530000, 1.25, 1758231041123, "b7c2-device", false),
            ),
            bookmarks = listOf(
                Bookmark("a3f...", 120000, "good bit \"quoted\"", 1758231041123),
                Bookmark("a3f...", 999, null, 1),
            ),
        )
        val roundTripped = decodeSyncDocument(encodeSyncDocument(doc))
        check("sync document round-trips through JSON", roundTripped == doc)
    }

    if (failures > 0) {
        println("$failures sync test(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("all sync tests passed")
}
