package com.blek.blexsy.player

import com.blek.blexsy.model.QueueTier
import com.blek.blexsy.model.Track
import java.util.UUID

data class JumpOrderPlan(
    val newOrderIndices: List<Int>,
    val newTargetIndex: Int,
    val isPureSeek: Boolean
)

/**
 * Pure queue rules shared by the player and unit tests.
 *
 * Keeping this logic out of [com.blek.blexsy.AndroidBlexsyAudioPlayer] makes the
 * tricky parts (dedupe, radio refill thresholds, retry backoff) testable without
 * an Android runtime.
 */
object QueuePolicy {
    /** Radio keeps this many tracks ahead of the current one. */
    const val RADIO_QUEUE_TARGET = 20

    /** Start fetching more radio tracks once only this many remain ahead. */
    const val RADIO_REFILL_THRESHOLD = 5

    /** Upper bound for restoring a persisted queue on cold start. */
    const val MAX_RESTORED_QUEUE = 100

    /** Remember enough played ids to avoid immediate repeats without unbounded memory. */
    const val MAX_SEEN_RADIO_IDS = 300

    /** Backend caps exclude_ids at 200; keep the URL well below proxy limits. */
    const val MAX_RADIO_EXCLUDES = 200

    private const val RETRY_BASE_MS = 5_000L
    private const val RETRY_MAX_MS = 60_000L

    // ponytail: In-memory three-tier queue rules without bounded windowing (QueueWindow); upgrade to window paging if playlist exceeds 500 items.
    fun asQueueEntry(track: Track, tier: QueueTier = track.queueTier): Track = track.copy(
        queueTier = tier,
        queueEntryId = track.queueEntryId ?: UUID.randomUUID().toString()
    )

    fun findUserQueueInsertionIndex(
        queue: List<Track>,
        currentIndex: Int,
        isNext: Boolean
    ): Int {
        if (queue.isEmpty()) return 0
        val start = (currentIndex + 1).coerceIn(0, queue.size)
        if (isNext) return start
        for (i in start until queue.size) {
            if (queue[i].queueTier != QueueTier.USER_QUEUE) return i
        }
        return queue.size
    }

    fun computeJumpOrder(
        tiers: List<QueueTier>,
        currentIndex: Int,
        targetIndex: Int
    ): JumpOrderPlan? {
        if (currentIndex !in tiers.indices || targetIndex !in tiers.indices) return null
        if (targetIndex <= currentIndex) {
            return JumpOrderPlan(
                newOrderIndices = tiers.indices.toList(),
                newTargetIndex = targetIndex,
                isPureSeek = true
            )
        }
        return when (tiers[targetIndex]) {
            QueueTier.CONTEXT -> {
                val crossedUserQueue = (currentIndex + 1 until targetIndex).filter { tiers[it] == QueueTier.USER_QUEUE }
                if (crossedUserQueue.isEmpty()) {
                    JumpOrderPlan(
                        newOrderIndices = tiers.indices.toList(),
                        newTargetIndex = targetIndex,
                        isPureSeek = true
                    )
                } else {
                    val history = (0..currentIndex).toList()
                    val bypassedContext = (currentIndex + 1 until targetIndex).filter { tiers[it] == QueueTier.CONTEXT }
                    val target = listOf(targetIndex)
                    val followingUserQueue = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.USER_QUEUE }
                    val upcomingUserQueue = crossedUserQueue + followingUserQueue
                    val followingContext = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.CONTEXT }
                    val followingAutoplay = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.AUTOPLAY }

                    val order = history + bypassedContext + target + upcomingUserQueue + followingContext + followingAutoplay
                    val newTarget = history.size + bypassedContext.size
                    JumpOrderPlan(order, newTarget, isPureSeek = false)
                }
            }
            QueueTier.USER_QUEUE -> {
                val history = (0..currentIndex).toList()
                val target = listOf(targetIndex)
                val subsequentUserQueue = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.USER_QUEUE }
                val upcomingContext = (currentIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.CONTEXT }
                val upcomingAutoplay = (currentIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.AUTOPLAY }

                val order = history + target + subsequentUserQueue + upcomingContext + upcomingAutoplay
                JumpOrderPlan(order, history.size, isPureSeek = false)
            }
            QueueTier.AUTOPLAY -> {
                val history = (0..currentIndex).toList()
                val target = listOf(targetIndex)
                val preservedUserQueue = (currentIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.USER_QUEUE }
                val subsequentAutoplay = (targetIndex + 1 until tiers.size).filter { tiers[it] == QueueTier.AUTOPLAY }

                val order = history + target + preservedUserQueue + subsequentAutoplay
                JumpOrderPlan(order, history.size, isPureSeek = false)
            }
        }
    }

    fun clearUserQueue(queue: List<Track>, currentIndex: Int): List<Track> {
        if (queue.isEmpty()) return emptyList()
        val keepIndices = (0..currentIndex.coerceAtMost(queue.lastIndex)).toMutableList()
        for (i in (currentIndex + 1) until queue.size) {
            if (queue[i].queueTier != QueueTier.USER_QUEUE) {
                keepIndices.add(i)
            }
        }
        return keepIndices.map { queue[it] }
    }

    fun consumePlayedUserQueue(queue: List<Track>, currentIndex: Int): Pair<List<Track>, Int> {
        if (currentIndex !in queue.indices || currentIndex == 0) return queue to currentIndex
        if (queue[currentIndex].queueTier != QueueTier.CONTEXT) return queue to currentIndex
        val keepIndices = mutableListOf<Int>()
        var newCurrentIndex = currentIndex
        for (i in 0 until currentIndex) {
            if (queue[i].queueTier != QueueTier.USER_QUEUE) {
                keepIndices.add(i)
            } else {
                newCurrentIndex--
            }
        }
        for (i in currentIndex until queue.size) {
            keepIndices.add(i)
        }
        return keepIndices.map { queue[it] } to newCurrentIndex
    }

    /**
     * Normalise a context queue: start at the selected track, keep the rest in
     * order, and drop blank/duplicate ids. If the track is missing it is
     * prepended so playback always has a valid head.
     */
    fun normalizeQueue(track: Track, requested: List<Track>): List<Track> {
        val raw = if (requested.isEmpty()) listOf(track) else requested
        val start = raw.indexOfFirst { it.id == track.id }
        val ordered = if (start != -1) raw.drop(start) else listOf(track) + raw
        val seen = HashSet<String>()
        return ordered.filter { it.id.isNotBlank() && seen.add(it.id) }
            .map { asQueueEntry(it, it.queueTier) }
    }

    /**
     * Append [incoming] to [existing] without duplicates and without exceeding
     * [maxSize]. Existing items keep their order and are never dropped, which is
     * important because index 0 is usually the currently playing track.
     */
    fun mergeUnique(
        existing: List<Track>,
        incoming: List<Track>,
        maxSize: Int = Int.MAX_VALUE
    ): List<Track> {
        if (maxSize <= 0) return existing
        val seen = existing.map { it.id }.toHashSet()
        val merged = existing.toMutableList()
        for (track in incoming) {
            if (merged.size >= maxSize) break
            if (track.id.isBlank() || !seen.add(track.id)) continue
            merged.add(track)
        }
        return merged
    }

    /**
     * Radio should refill when the *upcoming* part of the queue is short, not
     * when the total list is short. Played items are trimmed by the player, so
     * both usually match, but keeping this rule explicit avoids the old bug
     * where a queue that reached 10 items never refilled again.
     */
    fun shouldRefillRadio(
        queueSize: Int,
        currentIndex: Int,
        target: Int = RADIO_QUEUE_TARGET,
        threshold: Int = RADIO_REFILL_THRESHOLD
    ): Boolean {
        if (queueSize <= 0 || queueSize >= target) return false
        val remaining = if (currentIndex in 0 until queueSize) {
            queueSize - 1 - currentIndex
        } else {
            queueSize
        }
        return remaining <= threshold
    }

    /** Keep the seen-set bounded (oldest ids are evicted first). */
    fun trimSeen(seen: MutableSet<String>, maxSize: Int = MAX_SEEN_RADIO_IDS) {
        if (seen.size <= maxSize) return
        val iterator = seen.iterator()
        while (seen.size > maxSize && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /** Exponential backoff with a hard cap, so a rate-limited backend is not hammered. */
    fun radioRetryDelayMs(attempt: Int): Long {
        var delay = RETRY_BASE_MS
        repeat((attempt.coerceAtLeast(1)) - 1) {
            delay = (delay * 2).coerceAtMost(RETRY_MAX_MS)
        }
        return delay.coerceAtMost(RETRY_MAX_MS)
    }
}
