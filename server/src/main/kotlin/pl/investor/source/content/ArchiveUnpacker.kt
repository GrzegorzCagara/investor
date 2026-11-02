package pl.investor.source.content

import com.github.junrar.Archive
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem
import net.sf.sevenzipjbinding.util.ByteArrayStream
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

data class ArchiveLimits(
    val maxArchiveBytes: Int = 25 * 1024 * 1024,
    val maxEntries: Int = 200,
    val maxDocuments: Int = 10,
    val maxDocumentBytes: Long = MAX_ATTACHMENT_BYTES.toLong(),
    val maxTotalBytes: Long = 40L * 1024 * 1024,
)

class ArchivedDocument(val name: String, val format: AttachmentFormat, val bytes: ByteArray)

class ArchiveLimitExceededException(message: String) : IOException(message)

object SevenZipNative {

    private val log = LoggerFactory.getLogger(SevenZipNative::class.java)

    val isAvailable: Boolean by lazy {
        try {
            if (!SevenZip.isInitializedSuccessfully()) SevenZip.initSevenZipFromPlatformJAR(File(System.getProperty("java.io.tmpdir")))
            true
        } catch (e: Exception) {
            unavailable(e)
        } catch (e: LinkageError) {
            unavailable(e)
        }
    }

    private fun unavailable(cause: Throwable): Boolean {
        log.error("7-Zip native library could not be initialised, RAR5 attachments stay unreadable", cause)
        return false
    }
}

class ArchiveUnpacker(private val limits: ArchiveLimits = ArchiveLimits()) {

    private val log = LoggerFactory.getLogger(ArchiveUnpacker::class.java)

    fun <T : Any> readDocuments(archive: ByteArray, format: AttachmentFormat, read: (ArchivedDocument) -> T?): List<T> {
        if (archive.size > limits.maxArchiveBytes) throw ArchiveLimitExceededException("archive larger than ${limits.maxArchiveBytes} bytes")
        val budget = UnpackBudget(limits)
        val readAll: (Sequence<ArchivedDocument>) -> List<T> = { documents -> documents.take(limits.maxDocuments).mapNotNull(read).toList() }
        return when (format) {
            AttachmentFormat.ZIP -> zipDocuments(archive, budget, readAll)
            AttachmentFormat.RAR -> if (archive.isRar5()) rar5Documents(archive, budget, readAll) else rar4Documents(archive, budget, readAll)
            AttachmentFormat.PDF, AttachmentFormat.XHTML -> error("$format is not an archive")
        }
    }

    private fun <T> zipDocuments(archive: ByteArray, budget: UnpackBudget, readAll: (Sequence<ArchivedDocument>) -> T): T =
        ZipInputStream(ByteArrayInputStream(archive), Charsets.ISO_8859_1).use { zip ->
            generateSequence { zip.nextEntryUnlessEncrypted() }
                .onEach { budget.countEntry() }
                .filterNot { it.isDirectory }
                .mapNotNull { entry ->
                    documentFormat(entry.name)?.let { format ->
                        ArchivedDocument(entry.name, format, budget.collect(entry.size) { zip.copyTo(it) })
                    }
                }
                .let(readAll)
        }

    private fun ZipInputStream.nextEntryUnlessEncrypted() =
        try {
            nextEntry
        } catch (e: ZipException) {
            if (ENCRYPTED_ZIP_ENTRY !in e.message.orEmpty()) throw e
            log.warn("Encrypted ZIP entry, the remaining entries are skipped")
            null
        }

    private fun ByteArray.isRar5() = size >= RAR5_SIGNATURE.size && RAR5_SIGNATURE.indices.all { this[it] == RAR5_SIGNATURE[it] }

    private fun <T> rar5Documents(archive: ByteArray, budget: UnpackBudget, readAll: (Sequence<ArchivedDocument>) -> T): T {
        if (!SevenZipNative.isAvailable) throw IOException("7-Zip native library is unavailable, RAR5 cannot be read")
        return SevenZip.openInArchive(ArchiveFormat.RAR5, ByteArrayStream(archive, false)).use { rar ->
            rar.simpleInterface.archiveItems
                .also { budget.countEntries(it.size) }
                .asSequence()
                .filterNot { it.isFolder || it.isEncrypted }
                .mapNotNull { item ->
                    val name = item.path
                    documentFormat(name)?.let { format ->
                        ArchivedDocument(name, format, budget.collect(item.size ?: UNKNOWN_SIZE) { out -> item.extractTo(out) })
                    }
                }
                .let(readAll)
        }
    }

    private fun ISimpleInArchiveItem.extractTo(out: OutputStream) {
        val result = extractSlow { data ->
            try {
                out.write(data)
            } catch (e: ArchiveLimitExceededException) {
                throw SevenZipException(e.message, e)
            }
            data.size
        }
        if (result != ExtractOperationResult.OK) throw IOException("RAR5 entry $path not extracted: $result")
    }

    private fun <T> rar4Documents(archive: ByteArray, budget: UnpackBudget, readAll: (Sequence<ArchivedDocument>) -> T): T =
        Archive(ByteArrayInputStream(archive)).use { rar ->
            rar.fileHeaders
                .also { budget.countEntries(it.size) }
                .asSequence()
                .filterNot { it.isDirectory || it.isEncrypted }
                .mapNotNull { header ->
                    val name = header.fileName
                    documentFormat(name)?.let { format ->
                        ArchivedDocument(name, format, budget.collect(header.fullUnpackSize) { rar.extractFile(header, it) })
                    }
                }
                .let(readAll)
        }

    private fun documentFormat(name: String): AttachmentFormat? = when {
        PDF_ENTRY.containsMatchIn(name) -> AttachmentFormat.PDF
        MARKUP_ENTRY.containsMatchIn(name) -> AttachmentFormat.XHTML
        else -> null
    }

    private class UnpackBudget(private val limits: ArchiveLimits) {
        private var entries = 0
        private var totalBytes = 0L

        fun countEntry() = countEntries(1)

        fun countEntries(count: Int) {
            entries += count
            if (entries > limits.maxEntries) throw ArchiveLimitExceededException("more than ${limits.maxEntries} entries")
        }

        fun collect(declaredBytes: Long, write: (OutputStream) -> Unit): ByteArray {
            val remaining = minOf(limits.maxDocumentBytes, limits.maxTotalBytes - totalBytes)
            if (declaredBytes > remaining) throw ArchiveLimitExceededException("entry declares $declaredBytes bytes")
            val out = BoundedOutputStream(remaining, initialSize = declaredBytes.takeIf { it > 0 }?.toInt() ?: DEFAULT_BUFFER)
            write(out)
            totalBytes += out.size()
            return out.bytes()
        }
    }

    private class BoundedOutputStream(private val limit: Long, initialSize: Int) : ByteArrayOutputStream(initialSize) {
        override fun write(b: Int) {
            ensureRoom(1)
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            ensureRoom(len)
            super.write(b, off, len)
        }

        fun bytes(): ByteArray = if (count == buf.size) buf else buf.copyOf(count)

        private fun ensureRoom(bytes: Int) {
            if (count.toLong() + bytes > limit) throw ArchiveLimitExceededException("unpacked entry exceeds $limit bytes")
        }
    }

    private companion object {
        const val ENCRYPTED_ZIP_ENTRY = "encrypted"
        const val UNKNOWN_SIZE = -1L
        const val DEFAULT_BUFFER = 64 * 1024
        val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)
        val PDF_ENTRY = Regex("\\.pdf$", RegexOption.IGNORE_CASE)
        val MARKUP_ENTRY = Regex("\\.x?html?$", RegexOption.IGNORE_CASE)
    }
}
