package app.sable.playback

/**
 * Listening history (Home): a song counts once it has played 30 s, or half of it when shorter; once per time it plays.
 * "Each time it plays" includes the same queue entry starting over (repeat one, the wrap of repeat all, Previous, the
 * scrubber back to 0:00): [restarted] makes it countable again. Main thread only.
 */
class PlayCounter {
    private var counted: String? = null  // the queue entry already counted for this time it plays

    /** Called every few seconds while something is current; true when this play should be recorded now. */
    fun tick(entryId: String, playing: Boolean, positionMs: Long, durationMs: Long): Boolean {
        if (!playing || counted == entryId || durationMs <= 0 || positionMs < minOf(30_000L, durationMs / 2)) return false
        counted = entryId
        return true
    }

    /** The player moved to an item (another one, or the same one again by repeat): it can count. */
    fun newItem() { counted = null }

    /** The entry plays again from its start (the same signal the learning window uses, MATH-1.31). */
    fun restarted(entryId: String) { if (counted == entryId) counted = null }
}
