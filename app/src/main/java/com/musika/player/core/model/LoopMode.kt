package com.musika.player.core.model

import kotlinx.serialization.Serializable

/**
 * 3-State Loop playback state machine (Spotify parity).
 * - NO_LOOP: stops when queue reaches the end (unless TRUE_RANDOM is active).
 * - QUEUE_LOOP: repeats the active queue or context indefinitely.
 * - FILE_LOOP: loops the current single playing file continuously.
 */
@Serializable
enum class LoopMode {
    NO_LOOP,
    QUEUE_LOOP,
    FILE_LOOP;

    fun next(): LoopMode = when (this) {
        NO_LOOP -> QUEUE_LOOP
        QUEUE_LOOP -> FILE_LOOP
        FILE_LOOP -> NO_LOOP
    }
}
