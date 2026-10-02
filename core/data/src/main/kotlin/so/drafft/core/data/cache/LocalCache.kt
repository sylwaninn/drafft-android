package so.drafft.core.data.cache

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

// Each account has its own folder with one file per kind, written atomically.

/**
 * The last known state of each account, on this phone: shown at launch before the network answers,
 * then replaced by the server's (stale-while-revalidate). A cache only: the server is the truth, and
 * anything here can be dropped at any time.
 *
 * One folder per account, named after its id: two people on one phone never see each other's data,
 * and signing out or deleting the account removes the whole folder.
 *
 * Entries are the server's own payloads (JSON), by kind: decoding them again goes through the same
 * code as a fresh read, so the cache never has a shape of its own to keep in step.
 *
 * Reads and writes are plain file operations: callers keep writes off the main thread.
 */
class LocalCache(
    val account: UUID,
    private val directory: File,
) {
    /** What's kept. Raw values are stored: never rename one. */
    enum class Kind(val rawValue: String) {
        /** The account's own profile row, with its sports, prompts and media. */
        PROFILE("profile"),

        /** Matches (`my_matches`: the conversation list). */
        MATCHES("matches"),

        /** Upcoming and past sessions. */
        SESSIONS("sessions"),

        /** The last batch of Discover cards. */
        DECK("deck"),

        /** Who liked the account (`liked_me`). */
        LIKES("likes"),
    }

    /** A payload and when it was saved. */
    class Entry(val data: ByteArray, val savedAt: Instant) {
        override fun equals(other: Any?): Boolean = other is Entry && other.savedAt == savedAt && other.data.contentEquals(data)
        override fun hashCode(): Int = data.contentHashCode() * 31 + savedAt.hashCode()
        override fun toString(): String = "Entry(${data.size} bytes, $savedAt)"
    }

    private val folder = fileURL(account, directory)
    private val lock = Any()

    init {
        // Throws if the folder can't be made; callers carry on without a cache then.
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Can't create $folder")
    }

    // Reading and writing

    fun entry(kind: Kind): Entry? = synchronized(lock) {
        runCatching {
            DataInputStream(file(kind).inputStream().buffered()).use { input ->
                val seconds = input.readLong()
                val nanos = input.readInt()
                Entry(input.readBytes(), Instant.ofEpochSecond(seconds, nanos.toLong()))
            }
        }.getOrNull()
    }

    fun save(data: ByteArray, kind: Kind, at: Instant = Instant.now()) {
        synchronized(lock) {
            runCatching {
                val bytes = ByteArrayOutputStream(data.size + 12).also { out ->
                    DataOutputStream(out).use {
                        it.writeLong(at.epochSecond)
                        it.writeInt(at.nano)
                        it.write(data)
                    }
                }.toByteArray()
                folder.mkdirs()
                val temp = File(folder, "${kind.rawValue}.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(file(kind))) {
                    file(kind).delete()
                    temp.renameTo(file(kind))
                }
            }
        }
    }

    fun remove(kind: Kind) {
        synchronized(lock) { file(kind).delete() }
    }

    private fun file(kind: Kind) = File(folder, "${kind.rawValue}.bin")

    companion object {
        /** One folder per account. The id is lowercased so a key never depends on how it was printed. */
        fun fileURL(account: UUID, directory: File): File = File(directory, account.toString().lowercase())

        /** Removes an account's cache from the phone (sign-out, account deletion). */
        fun erase(account: UUID, directory: File) {
            fileURL(account, directory).deleteRecursively()
        }

        /** Removes every account's cache (used when the signed-in account can't be told). */
        fun eraseAll(directory: File) {
            directory.deleteRecursively()
        }
    }
}
