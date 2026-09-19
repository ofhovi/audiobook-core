package audiobook.core.jvm

import audiobook.core.Book
import audiobook.core.ByteSource
import audiobook.core.Mp4Error
import audiobook.core.extractCover
import audiobook.core.parseAudiobook
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** JVM/Android ByteSource. Android also uses this via a ParcelFileDescriptor. */
class FileByteSource(private val file: File) : ByteSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readAt(offset: Long, count: Int): ByteArray {
        if (offset >= size || count <= 0) return ByteArray(0)
        val n = minOf(count.toLong(), size - offset).toInt()
        val buf = ByteArray(n)
        raf.seek(offset)
        var read = 0
        while (read < n) {
            val r = raf.read(buf, read, n - read)
            if (r <= 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    override fun close() = raf.close()
}

const val HASH_WINDOW = 65536

/** CONTRACT.md section 1. Must match the Python reference byte for byte. */
fun contentId(file: File): String {
    val size = file.length()
    val md = MessageDigest.getInstance("SHA-256")
    val le = ByteArray(8)
    for (i in 0 until 8) le[i] = ((size shr (8 * i)) and 0xFF).toByte()
    md.update(le)
    RandomAccessFile(file, "r").use { raf ->
        val head = ByteArray(minOf(HASH_WINDOW.toLong(), size).toInt())
        raf.seek(0); raf.readFully(head); md.update(head)
        val tailStart = maxOf(0L, size - HASH_WINDOW)
        val tail = ByteArray(minOf(HASH_WINDOW.toLong(), size - tailStart).toInt())
        raf.seek(tailStart); raf.readFully(tail); md.update(tail)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// ----------------------------------------------------------------- harness

private fun esc(s: String): String {
    val sb = StringBuilder()
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c.code < 0x20 || c.code > 0x7E -> sb.append("\\u%04x".format(c.code))
        else -> sb.append(c)
    }
    return sb.toString()
}

private fun sha256Hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private fun toJson(b: Book, coverSha256: String?): String {
    val ch = b.chapters.joinToString(",") {
        """{"index":${it.index},"title":"${esc(it.title)}","start_ms":${it.startMs},"end_ms":${it.endMs}}"""
    }
    fun opt(v: String?) = if (v == null) "null" else "\"${esc(v)}\""
    return """{"duration_ms":${b.durationMs},"chapter_source":"${b.chapterSource.name.lowercase()}",""" +
        """"title":${opt(b.title)},"author":${opt(b.author)},"album":${opt(b.album)},""" +
        """"cover_format":${opt(b.coverFormat)},"cover_sha256":${opt(coverSha256)},""" +
        """"chapters":[$ch]}"""
}

fun main(args: Array<String>) {
    for (path in args) {
        val f = File(path)
        print("${f.name}\t")
        try {
            val book = FileByteSource(f).use { parseAudiobook(it) }
            val coverSha256 = FileByteSource(f).use { extractCover(it) }?.let { sha256Hex(it) }
            println(toJson(book, coverSha256))
        } catch (e: Mp4Error) {
            println("""{"error":"${esc(e.message ?: "")}"}""")
        }
    }
}

/** Separate entrypoint so verify.sh can diff content_ids across ports. */
object HashMain {
    @JvmStatic
    fun main(args: Array<String>) {
        for (p in args) println("${File(p).name}\t${contentId(File(p))}")
    }
}
