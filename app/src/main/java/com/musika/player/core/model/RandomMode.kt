package com.musika.player.core.model

import kotlinx.serialization.Serializable

/**
 * 3-State Random playback state machine.
 * - OFF: sequential playback in natural track/context order.
 * - SHUFFLE_POOL: standard non-repeating shuffle pool across the active context.
 * - TRUE_RANDOM: memoryless random selection on every track change, ignoring loop limits
 *                while still draining the Priority Queue ("Play Next") first.
 */
@Serializable
enum class RandomMode {
    OFF,
    SHUFFLE_POOL,
    TRUE_RANDOM;

    fun next(): RandomMode = when (this) {
        OFF -> SHUFFLE_POOL
        SHUFFLE_POOL -> TRUE_RANDOM
        TRUE_RANDOM -> OFF
    }
}
