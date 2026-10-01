package so.drafft.core.data.media

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Android's form of Nuke's data loading queue (Drafft/Services/Media/Images.swift): Coil has no request
// priority and OkHttp frees a download's slot as soon as its headers arrive, so the photo downloads go
// through this queue instead, from the image client's interceptor (`installImages` in core:ui).

/**
 * The photo downloads running at once ([limit]: `Images.downloads`, fewer on a limited connection), the
 * highest priority first, then the oldest: the card in play finishes first instead of sharing the line
 * with everything fetched ahead. A download holds its slot until its body is read or closed.
 */
class PhotoDownloads(limit: Int = Images.downloads) {
    private val lock = ReentrantLock()
    private val turn = lock.newCondition()
    private var running = 0
    private var next = 0L
    private val waiting = mutableListOf<Ticket>()

    private class Ticket(val rank: Int, val order: Long)

    var limit: Int = limit
        set(value) = lock.withLock {
            field = maxOf(1, value)
            turn.signalAll()
        }

    /** Downloads waiting for a slot. */
    val waitingCount: Int get() = lock.withLock { waiting.size }

    /**
     * Blocks until a download of priority [rank] (`Images.Priority.ordinal`) may start; false when
     * [cancelled] says so first.
     */
    fun acquire(rank: Int, cancelled: () -> Boolean): Boolean = lock.withLock {
        val ticket = Ticket(rank, next++)
        waiting += ticket
        try {
            while (running >= limit || first() !== ticket) {
                if (cancelled()) return false
                turn.await(50, TimeUnit.MILLISECONDS)
            }
            running += 1
            true
        } finally {
            waiting.remove(ticket)
            turn.signalAll()
        }
    }

    /** A download acquired with [acquire] is over. */
    fun release() = lock.withLock {
        running = maxOf(0, running - 1)
        turn.signalAll()
    }

    private fun first(): Ticket? = waiting.maxWithOrNull(compareBy<Ticket> { it.rank }.thenByDescending { it.order })

    companion object {
        val shared = PhotoDownloads()
    }
}
