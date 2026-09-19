package audiobook.core

/**
 * MP4/M4B chapter parser. Port of the verified Python reference.
 *
 * commonMain-safe: no java.*, no okio, no Charsets. All file access goes through
 * [ByteSource], which each platform implements (RandomAccessFile on JVM,
 * FileHandle on Native, whatever you like).
 *
 * Must produce identical output to mp4_audiobook.py for the same input. See
 * CONTRACT.md section 2 for the guarantees this upholds.
 */

interface ByteSource {
    val size: Long

    /** Reads up to [count] bytes at [offset]. May return fewer near EOF. */
    fun readAt(offset: Long, count: Int): ByteArray
}

class Mp4Error(message: String) : Exception(message)

data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

enum class ChapterSource { QUICKTIME, NERO, NONE }

data class Book(
    val durationMs: Long,
    val chapters: List<Chapter>,
    val chapterSource: ChapterSource,
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val album: String? = null,
    val tags: Map<String, String> = emptyMap(),
    val coverFormat: String? = null, // "jpeg" | "png" | null
)

// ---------------------------------------------------------------- big endian

private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

private fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

private fun ByteArray.u32(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or
        (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

private fun ByteArray.u64(i: Int): Long {
    var v = 0L
    for (k in 0 until 8) v = (v shl 8) or u8(i + k).toLong()
    return v
}

private fun ByteArray.fourCC(i: Int): String {
    val sb = StringBuilder(4)
    for (k in 0 until 4) sb.append(u8(i + k).toChar())
    return sb.toString()
}

// -------------------------------------------------------------- atom walking

private val CONTAINERS = setOf(
    "moov", "trak", "mdia", "minf", "stbl", "udta",
    "meta", "ilst", "edts", "tref", "mvex", "moof", "traf",
)

private class Atom(
    val type: String,
    val offset: Long,
    val size: Long,
    val body: Long,
    val children: MutableList<Atom> = mutableListOf(),
) {
    val bodySize: Int get() = (offset + size - body).toInt()

    fun find(vararg path: String): Atom? {
        var node: Atom = this
        for (want in path) {
            node = node.children.firstOrNull { it.type == want } ?: return null
        }
        return node
    }

    fun findAll(want: String): List<Atom> = children.filter { it.type == want }
}

private const val MAX_DEPTH = 12

private fun parseAtoms(src: ByteSource, start: Long, end: Long, depth: Int = 0): List<Atom> {
    if (depth > MAX_DEPTH) return emptyList()
    val out = mutableListOf<Atom>()
    var pos = start
    while (pos + 8 <= end) {
        val hdr = src.readAt(pos, 16)
        if (hdr.size < 8) break
        var size = hdr.u32(0)
        val type = hdr.fourCC(4)
        var header = 8L
        when {
            size == 1L -> {
                if (hdr.size < 16) break
                size = hdr.u64(8)
                header = 16L
            }
            size == 0L -> size = end - pos
        }
        if (size < header || pos + size > end) break // truncated or bogus
        val atom = Atom(type, pos, size, pos + header)
        if (type in CONTAINERS) {
            var body = atom.body
            if (type == "meta") {
                // Usually a full box (4-byte version/flags), but some writers
                // emit it bare. Sniff for the leading zero word.
                val probe = src.readAt(body, 4)
                if (probe.size == 4 && probe.u32(0) == 0L) body += 4
            }
            atom.children.addAll(parseAtoms(src, body, pos + size, depth + 1))
        }
        out.add(atom)
        pos += size
    }
    return out
}

private fun readBody(src: ByteSource, atom: Atom): ByteArray =
    src.readAt(atom.body, atom.bodySize)

// ----------------------------------------------------------------- sample map

private class Sample(val offset: Long, val size: Int)

private fun sampleOffsets(src: ByteSource, stbl: Atom): List<Sample> {
    val stsz = stbl.find("stsz") ?: return emptyList()
    val stsc = stbl.find("stsc") ?: return emptyList()
    val stco = stbl.find("stco") ?: stbl.find("co64") ?: return emptyList()

    var b = readBody(src, stsz)
    if (b.size < 12) throw Mp4Error("stsz truncated")
    val uniform = b.u32(4)
    val count = b.u32(8).toInt()
    if (count < 0 || count > 2_000_000) throw Mp4Error("stsz sample count implausible")
    val sizes = IntArray(count)
    if (uniform != 0L) {
        sizes.fill(uniform.toInt())
    } else {
        if (b.size < 12 + 4 * count) throw Mp4Error("stsz truncated")
        for (i in 0 until count) sizes[i] = b.u32(12 + 4 * i).toInt()
    }

    b = readBody(src, stsc)
    if (b.size < 8) throw Mp4Error("stsc truncated")
    val runCount = b.u32(4).toInt()
    if (runCount < 0 || 8 + 12 * runCount > b.size) throw Mp4Error("stsc truncated")
    val firstChunk = IntArray(runCount)
    val samplesPerChunk = IntArray(runCount)
    for (i in 0 until runCount) {
        firstChunk[i] = b.u32(8 + 12 * i).toInt()
        samplesPerChunk[i] = b.u32(12 + 12 * i).toInt()
    }

    b = readBody(src, stco)
    if (b.size < 8) throw Mp4Error("stco truncated")
    val chunkCount = b.u32(4).toInt()
    val wide = stco.type == "co64"
    val step = if (wide) 8 else 4
    if (chunkCount < 0 || 8 + step * chunkCount > b.size) throw Mp4Error("stco truncated")
    val chunks = LongArray(chunkCount)
    for (i in 0 until chunkCount) {
        chunks[i] = if (wide) b.u64(8 + 8 * i) else b.u32(8 + 4 * i)
    }

    // Expand stsc runs into a samples-per-chunk value for every chunk.
    val perChunk = IntArray(chunkCount)
    var filled = 0
    for (i in 0 until runCount) {
        val last = if (i + 1 < runCount) firstChunk[i + 1] - 1 else chunkCount
        val span = last - firstChunk[i] + 1
        for (k in 0 until span) {
            if (filled >= chunkCount) break
            perChunk[filled++] = samplesPerChunk[i]
        }
    }
    val tail = if (runCount > 0) samplesPerChunk[runCount - 1] else 1
    while (filled < chunkCount) perChunk[filled++] = tail

    val out = mutableListOf<Sample>()
    var s = 0
    for (ci in 0 until chunkCount) {
        var off = chunks[ci]
        for (k in 0 until perChunk[ci]) {
            if (s >= count) return out
            out.add(Sample(off, sizes[s]))
            off += sizes[s]
            s++
        }
    }
    return out
}

private fun sampleDurations(src: ByteSource, stbl: Atom): LongArray {
    val stts = stbl.find("stts") ?: return LongArray(0)
    val b = readBody(src, stts)
    if (b.size < 8) return LongArray(0)
    val n = b.u32(4).toInt()
    if (n < 0 || 8 + 8 * n > b.size) throw Mp4Error("stts truncated")
    val out = mutableListOf<Long>()
    for (i in 0 until n) {
        val c = b.u32(8 + 8 * i)
        val delta = b.u32(12 + 8 * i)
        if (c > 1_000_000) throw Mp4Error("stts entry count implausible")
        for (k in 0 until c.toInt()) out.add(delta)
    }
    return out.toLongArray()
}

/** Returns timescale to duration, from an mdhd or mvhd body. */
private fun timescaleAndDuration(b: ByteArray): Pair<Long, Long> {
    if (b.isEmpty()) return 1000L to 0L
    return if (b.u8(0) == 1) {
        if (b.size < 28) 1000L to 0L else b.u32(20).let { ts ->
            (if (ts == 0L) 1000L else ts) to b.u64(24)
        }
    } else {
        if (b.size < 20) 1000L to 0L else b.u32(12).let { ts ->
            (if (ts == 0L) 1000L else ts) to b.u32(16)
        }
    }
}

// ------------------------------------------------------------ text decoding

/** cp1252 differs from latin-1 only in 0x80..0x9F. */
private val CP1252_HIGH = intArrayOf(
    0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
    0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
    0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
    0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
)

private fun isValidUtf8(b: ByteArray): Boolean {
    var i = 0
    while (i < b.size) {
        val c = b.u8(i)
        val need = when {
            c < 0x80 -> 0
            c in 0xC2..0xDF -> 1
            c in 0xE0..0xEF -> 2
            c in 0xF0..0xF4 -> 3
            else -> return false
        }
        if (i + need >= b.size) return false
        for (k in 1..need) {
            if (b.u8(i + k) !in 0x80..0xBF) return false
        }
        i += need + 1
    }
    return true
}

private fun decodeUtf16(b: ByteArray, bigEndian: Boolean): String {
    val chars = CharArray((b.size - 2) / 2)
    var j = 0
    var i = 2
    while (i + 1 < b.size) {
        val hi = b.u8(i)
        val lo = b.u8(i + 1)
        chars[j++] = (if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar()
        i += 2
    }
    return chars.concatToString(0, j)
}

private fun decodeCp1252(b: ByteArray): String {
    val chars = CharArray(b.size)
    for (i in b.indices) {
        val c = b.u8(i)
        chars[i] = if (c in 0x80..0x9F) CP1252_HIGH[c - 0x80].toChar() else c.toChar()
    }
    return chars.concatToString()
}

private fun String.stripNul(): String = trim('\u0000')

/** QuickTime text sample: u16 length, text bytes, then optional atoms ('encd'). */
private fun decodeTextSample(raw: ByteArray): String {
    if (raw.size < 2) return ""
    val n = raw.u16(0)
    val end = minOf(2 + n, raw.size)
    if (end <= 2) return ""
    val body = raw.copyOfRange(2, end)
    if (body.size >= 2) {
        val b0 = body.u8(0)
        val b1 = body.u8(1)
        if (b0 == 0xFE && b1 == 0xFF) return decodeUtf16(body, true).stripNul()
        if (b0 == 0xFF && b1 == 0xFE) return decodeUtf16(body, false).stripNul()
    }
    return if (isValidUtf8(body)) body.decodeToString().stripNul()
    else decodeCp1252(body).stripNul()
}

// -------------------------------------------------------- chapter extraction

private fun quicktimeChapters(src: ByteSource, moov: Atom): List<Chapter>? {
    val wanted = mutableSetOf<Long>()
    for (t in moov.findAll("trak")) {
        val chap = t.find("tref", "chap") ?: continue
        val raw = readBody(src, chap)
        var i = 0
        while (i + 4 <= raw.size) {
            wanted.add(raw.u32(i))
            i += 4
        }
    }
    if (wanted.isEmpty()) return null

    for (t in moov.findAll("trak")) {
        val tkhd = t.find("tkhd") ?: continue
        val mdhd = t.find("mdia", "mdhd") ?: continue
        val stbl = t.find("mdia", "minf", "stbl") ?: continue
        val tk = readBody(src, tkhd)
        if (tk.size < 24) continue
        val trackId = if (tk.u8(0) == 1) tk.u32(20) else tk.u32(12)
        if (trackId !in wanted) continue

        val (timescale, _) = timescaleAndDuration(readBody(src, mdhd))
        val samples = sampleOffsets(src, stbl)
        if (samples.isEmpty()) continue
        val durations = sampleDurations(src, stbl)

        val chapters = mutableListOf<Chapter>()
        var units = 0L
        for ((i, s) in samples.withIndex()) {
            if (s.size <= 0 || s.size > (1 shl 20)) continue
            val title = decodeTextSample(src.readAt(s.offset, s.size))
            val startMs = units * 1000 / timescale
            units += if (i < durations.size) durations[i] else 0L
            val endMs = units * 1000 / timescale
            chapters.add(Chapter(chapters.size, title, startMs, endMs))
        }
        if (chapters.isNotEmpty()) return chapters
    }
    return null
}

private fun neroChapters(src: ByteSource, moov: Atom): List<Chapter>? {
    val chpl = moov.find("udta", "chpl") ?: return null
    val b = readBody(src, chpl)
    if (b.size < 9) return null
    // v1: version(1) flags(3) reserved(4) count(1). v0: version(1) flags(3) count(1).
    var p = if (b.u8(0) == 1) 9 else 5
    val count = b.u8(p - 1)
    val out = mutableListOf<Chapter>()
    for (k in 0 until count) {
        if (p + 9 > b.size) break
        val ts = b.u64(p)
        p += 8
        val n = b.u8(p)
        p += 1
        if (p + n > b.size) break
        val title = b.copyOfRange(p, p + n).decodeToString()
        p += n
        // chpl timestamps are in 100-nanosecond units.
        out.add(Chapter(out.size, title, ts / 10_000, 0))
    }
    return out.ifEmpty { null }
}

// ------------------------------------------------------------------ metadata

private val ILST_KEYS = mapOf(
    "\u00A9nam" to "title",
    "\u00A9ART" to "author",
    "\u00A9alb" to "album",
    "\u00A9wrt" to "narrator",
    "aART" to "album_artist",
    "\u00A9gen" to "genre",
    "\u00A9day" to "year",
    "\u00A9cmt" to "comment",
)

private fun ilstTags(src: ByteSource, moov: Atom): Map<String, String> {
    val ilst = moov.find("udta", "meta", "ilst") ?: return emptyMap()
    val tags = mutableMapOf<String, String>()
    for (item in ilst.children) {
        val key = ILST_KEYS[item.type] ?: continue
        val kids = parseAtoms(src, item.body, item.offset + item.size)
        val data = kids.firstOrNull { it.type == "data" } ?: continue
        if (data.bodySize <= 8) continue
        val raw = src.readAt(data.body + 8, data.bodySize - 8)
        val value = raw.decodeToString().stripNul()
        if (value.isNotEmpty()) tags[key] = value
    }
    return tags
}

private fun coverDataAtom(src: ByteSource, moov: Atom): Atom? {
    val ilst = moov.find("udta", "meta", "ilst") ?: return null
    val covr = ilst.children.firstOrNull { it.type == "covr" } ?: return null
    var data = covr.find("data")
    if (data == null) {
        val kids = parseAtoms(src, covr.body, covr.offset + covr.size)
        data = kids.firstOrNull { it.type == "data" }
    }
    if (data == null || data.bodySize <= 8) return null
    return data
}

private fun sniffCoverFormat(typeIndicator: Long, payloadHead: ByteArray): String? {
    // 13/14 are the well-known iTunes type indicators for JPEG/PNG. Some
    // writers leave the indicator at 0 ("implicit"), so fall back to magic
    // bytes rather than reject the cover outright.
    if (typeIndicator == 13L) return "jpeg"
    if (typeIndicator == 14L) return "png"
    if (payloadHead.size >= 3 && payloadHead.u8(0) == 0xFF && payloadHead.u8(1) == 0xD8 && payloadHead.u8(2) == 0xFF) {
        return "jpeg"
    }
    if (payloadHead.size >= 8 &&
        payloadHead.u8(0) == 0x89 && payloadHead.u8(1) == 0x50 && payloadHead.u8(2) == 0x4E && payloadHead.u8(3) == 0x47 &&
        payloadHead.u8(4) == 0x0D && payloadHead.u8(5) == 0x0A && payloadHead.u8(6) == 0x1A && payloadHead.u8(7) == 0x0A
    ) {
        return "png"
    }
    return null
}

/** Cheap: only reads enough of the data atom to identify the format. */
private fun coverFormat(src: ByteSource, moov: Atom): String? {
    val data = coverDataAtom(src, moov) ?: return null
    val header = src.readAt(data.body, 16) // type indicator(4) + locale(4) + a peek at the payload
    if (header.size < 8) return null
    return sniffCoverFormat(header.u32(0), header.copyOfRange(8, header.size))
}

/**
 * The actual cover image bytes, fetched on demand. Not part of [Book] /
 * [parseAudiobook]'s result: that keeps the parse result small and
 * JSON-friendly (the cross-check harness diffs it directly) and avoids
 * reading a whole embedded image just to list a library.
 */
fun extractCover(src: ByteSource): ByteArray? {
    val size = src.size
    if (size < 8) return null
    val top = parseAtoms(src, 0, size)
    val moov = top.firstOrNull { it.type == "moov" } ?: return null
    val data = coverDataAtom(src, moov) ?: return null
    return src.readAt(data.body + 8, data.bodySize - 8)
}

// -------------------------------------------------------------- normalization

private fun normalize(chapters: List<Chapter>, durationMs: Long): List<Chapter> {
    if (chapters.isEmpty()) return listOf(Chapter(0, "Chapter 1", 0, durationMs))
    val sorted = chapters.sortedBy { it.startMs }.map {
        var s = if (it.startMs < 0) 0 else it.startMs
        if (durationMs > 0 && s > durationMs) s = durationMs
        Chapter(it.index, it.title, s, it.endMs)
    }.toMutableList()

    // Derive end from the next start; the last one runs to the file end.
    for (i in sorted.indices) {
        val c = sorted[i]
        val next = if (i + 1 < sorted.size) sorted[i + 1].startMs
        else if (durationMs > 0) durationMs else c.endMs
        var e = c.endMs
        if (e <= c.startMs || (next > 0 && e > next)) e = next
        if (durationMs > 0 && e > durationMs) e = durationMs
        sorted[i] = Chapter(c.index, c.title, c.startMs, e)
    }

    var kept = sorted.filter { it.endMs > it.startMs }
    if (kept.isEmpty()) kept = listOf(sorted.first())

    return kept.mapIndexed { i, c ->
        val title = c.title.trim().ifEmpty { "Chapter ${i + 1}" }
        Chapter(i, title, c.startMs, c.endMs)
    }
}

// -------------------------------------------------------------------- public

/**
 * @Throws is a no-op on JVM but required for Kotlin/Native: without it, a
 * thrown Mp4Error crashes Swift/Obj-C callers instead of being catchable via
 * `try`. iOS's SwiftUI layer relies on this.
 */
@Throws(Mp4Error::class)
fun parseAudiobook(src: ByteSource): Book {
    val size = src.size
    if (size < 8) throw Mp4Error("file too small to be MP4")

    val top = parseAtoms(src, 0, size)
    if (top.none { it.type == "ftyp" } && top.none { it.type == "moov" }) {
        throw Mp4Error("not an MP4 container")
    }
    val moov = top.firstOrNull { it.type == "moov" }
        ?: throw Mp4Error("no moov atom (fragmented or streaming-only file?)")

    val mvhd = moov.find("mvhd")
    var durationMs = 0L
    if (mvhd != null) {
        val (ts, dur) = timescaleAndDuration(readBody(src, mvhd))
        durationMs = dur * 1000 / ts
    }

    var source = ChapterSource.QUICKTIME
    var chapters = quicktimeChapters(src, moov)
    if (chapters == null) {
        chapters = neroChapters(src, moov)
        source = ChapterSource.NERO
    }
    if (chapters == null) {
        chapters = listOf(Chapter(0, "", 0, durationMs))
        source = ChapterSource.NONE
    }

    val tags = ilstTags(src, moov)
    return Book(
        durationMs = durationMs,
        chapters = normalize(chapters, durationMs),
        chapterSource = source,
        title = tags["title"],
        author = tags["author"],
        narrator = tags["narrator"],
        album = tags["album"],
        tags = tags,
        coverFormat = coverFormat(src, moov),
    )
}
