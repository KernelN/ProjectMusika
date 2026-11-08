package com.musika.player.data.metadata

import com.musika.player.core.model.Track
import com.musika.player.data.local.entity.WebMetadataEntity

/**
 * Multi-Step Local File Write Confirmation State Machine (Specification #7).
 * Enforces a single dialog flow with multiple "Are you sure?" confirmation steps
 * supporting per-track or per-album confirmation with a before/after diff before writing
 * approved tags into local files.
 */
enum class ConfirmationScope {
    PER_TRACK,
    PER_ALBUM
}

enum class WriteConfirmationStep {
    STEP_1_REVIEW_DIFF,
    STEP_2_FIRST_ARE_YOU_SURE,
    STEP_3_FINAL_ARE_YOU_SURE,
    COMPLETED_READY_TO_WRITE
}

data class TrackMetadataDiff(
    val filePath: String,
    val beforeTitle: String,
    val afterTitle: String,
    val beforeArtist: String,
    val afterArtist: String,
    val beforeAlbum: String,
    val afterAlbum: String,
    val beforeYear: Int?,
    val afterYear: Int?,
    val beforeTrackNumber: Int,
    val afterTrackNumber: Int,
    val beforeDiscNumber: Int,
    val afterDiscNumber: Int
) {
    val hasChanges: Boolean
        get() = beforeTitle != afterTitle ||
                beforeArtist != afterArtist ||
                beforeAlbum != afterAlbum ||
                beforeYear != afterYear ||
                beforeTrackNumber != afterTrackNumber ||
                beforeDiscNumber != afterDiscNumber

    companion object {
        fun from(track: Track, candidate: WebMetadataEntity): TrackMetadataDiff {
            return TrackMetadataDiff(
                filePath = track.filePath,
                beforeTitle = track.title,
                afterTitle = candidate.candidateTitle ?: track.title,
                beforeArtist = track.artist,
                afterArtist = candidate.candidateArtist ?: track.artist,
                beforeAlbum = track.album,
                afterAlbum = candidate.candidateAlbum ?: track.album,
                beforeYear = track.year,
                afterYear = candidate.candidateYear ?: track.year,
                beforeTrackNumber = track.trackNumber,
                afterTrackNumber = candidate.candidateTrackNumber ?: track.trackNumber,
                beforeDiscNumber = track.discNumber,
                afterDiscNumber = candidate.candidateDiscNumber ?: track.discNumber
            )
        }
    }
}

data class MetadataWriteDialogState(
    val scope: ConfirmationScope,
    val targetLabel: String,
    val diffs: List<TrackMetadataDiff>,
    val currentStep: WriteConfirmationStep = WriteConfirmationStep.STEP_1_REVIEW_DIFF
) {
    val isWriteAuthorized: Boolean
        get() = currentStep == WriteConfirmationStep.COMPLETED_READY_TO_WRITE && diffs.isNotEmpty()

    val stepNumber: Int
        get() = when (currentStep) {
            WriteConfirmationStep.STEP_1_REVIEW_DIFF -> 1
            WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE -> 2
            WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE -> 3
            WriteConfirmationStep.COMPLETED_READY_TO_WRITE -> 3
        }

    val totalSteps: Int = 3

    val promptTitle: String
        get() = when (currentStep) {
            WriteConfirmationStep.STEP_1_REVIEW_DIFF ->
                "Step 1 of 3: Review Web Match Diff ($targetLabel)"
            WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE ->
                "Step 2 of 3: Are you sure you want to modify ${diffs.size} local file(s)?"
            WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE ->
                "Step 3 of 3: Final Confirmation — Are you sure? Local tags will be overwritten."
            WriteConfirmationStep.COMPLETED_READY_TO_WRITE ->
                "Confirmed — Writing Tags"
        }

    /**
     * Advances to the next confirmation step in the single dialog flow.
     * Local file write is strictly blocked until all confirmation steps are completed.
     */
    fun confirmCurrentStep(): MetadataWriteDialogState {
        val nextStep = when (currentStep) {
            WriteConfirmationStep.STEP_1_REVIEW_DIFF -> WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE
            WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE -> WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE
            WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE -> WriteConfirmationStep.COMPLETED_READY_TO_WRITE
            WriteConfirmationStep.COMPLETED_READY_TO_WRITE -> WriteConfirmationStep.COMPLETED_READY_TO_WRITE
        }
        return copy(currentStep = nextStep)
    }

    fun stepBack(): MetadataWriteDialogState {
        val prevStep = when (currentStep) {
            WriteConfirmationStep.STEP_1_REVIEW_DIFF -> WriteConfirmationStep.STEP_1_REVIEW_DIFF
            WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE -> WriteConfirmationStep.STEP_1_REVIEW_DIFF
            WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE -> WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE
            WriteConfirmationStep.COMPLETED_READY_TO_WRITE -> WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE
        }
        return copy(currentStep = prevStep)
    }
}
