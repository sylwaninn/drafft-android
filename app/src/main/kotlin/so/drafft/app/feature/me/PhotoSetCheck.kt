package so.drafft.app.feature.me

// Port of Drafft/Features/Me/PhotoSetCheck.swift.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.verification.FaceCheck
import so.drafft.core.model.L
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.medium

/**
 * Where a profile's photos stand, the same at sign-up and in Edit profile. They go on (continue, save)
 * only once moderation has judged every photo and the first one is approved with a face (checked here,
 * then again on the server, which also refuses to delete the last one): a photo being checked, refused
 * or waiting for a person never opens or keeps a profile. Refused or waiting photos after the first may
 * stay (a second look can be asked from their tile): the server never shows them.
 */
/**
 * A failed sign-up or save, in words (the Swift `ProfileSync.failure`). The first photo still with the team
 * answers `portrait_required`: said as that, not as a photo to change.
 */
fun profileSaveFailure(error: Throwable, photos: PhotoSetCheck): String {
    if (error is ProfileSync.SyncError.Refused && error.code == "portrait_required" && photos == PhotoSetCheck.FIRST_IN_REVIEW) {
        photos.reason?.let { return it }
    }
    if (error is ProfileSync.SyncError) return error.message
    return ServerMessage.failure(error)
}

enum class PhotoSetCheck {
    EMPTY, CHECKING, READY, FIRST_REFUSED, FIRST_IN_REVIEW, NO_FACE, TOO_SMALL, FAILED;

    /** Something the person has to change (not just wait for). */
    val needsAction: Boolean
        get() = when (this) {
            FIRST_REFUSED, NO_FACE, TOO_SMALL, FAILED -> true
            EMPTY, CHECKING, READY, FIRST_IN_REVIEW -> false
        }

    /** One line for the validate button's footer; null once ready. */
    val reason: String?
        get() = when (this) {
            EMPTY -> L("Add at least one photo.")
            // Only the photo being checked says so (its tile's loader): no line.
            CHECKING, READY -> null
            FIRST_IN_REVIEW -> L("Our team is checking your first photo. Put another one first, or wait.")
            FIRST_REFUSED, NO_FACE, TOO_SMALL -> L("Put a clear photo of your face first.")
            FAILED -> L("A photo couldn't be sent. Tap it to see why, then try again.")
        }

    companion object {
        /** [face]: the face check of the first photo ([face]), null while it runs. */
        fun of(photos: List<String>, face: FaceCheck.Result?, moderation: PhotoModeration): PhotoSetCheck {
            val first = photos.firstOrNull()
            if (first.isNullOrEmpty()) return EMPTY
            // A photo read back from the server with no state was approved (`PhotoModeration.isShown`).
            val states = photos.map { moderation.state(it) ?: if (it.startsWith("/")) null else PhotoModeration.State.Approved }
            return when {
                states[0] == PhotoModeration.State.Refused -> FIRST_REFUSED
                face == FaceCheck.Result.NO_FACE -> NO_FACE
                face == FaceCheck.Result.TOO_SMALL -> TOO_SMALL
                states.any { it is PhotoModeration.State.Failed } -> FAILED
                face == null || states.any { it?.isJudged != true } -> CHECKING
                states[0] == PhotoModeration.State.InReview -> FIRST_IN_REVIEW
                else -> READY
            }
        }

        /**
         * The face on a profile's first photo. A photo already on the server was checked there, with its
         * verdict (the server picks the portrait by it): its word is taken. Picked photos are checked here.
         */
        suspend fun face(path: String?, moderation: PhotoModeration): FaceCheck.Result? {
            if (path.isNullOrEmpty()) return null
            if (!path.startsWith("/")) return if (moderation.isFaceless(path)) FaceCheck.Result.NO_FACE else FaceCheck.Result.FACE
            return FaceCheck.check(path)
        }
    }
}

/** Under the photo grid: what the first photo still needs, or that the photos are being checked. */
@Composable
fun PhotoSetHint(check: PhotoSetCheck) {
    when (check) {
        PhotoSetCheck.EMPTY -> HintLine(L("Your first photo needs to show your face clearly."))
        // All good, or a photo still being checked: its tile's loader says it, nothing more here.
        PhotoSetCheck.CHECKING, PhotoSetCheck.READY -> Unit
        PhotoSetCheck.FIRST_REFUSED -> HintLine(L("Your first photo wasn't approved. Put another one first."), error = true)
        PhotoSetCheck.FIRST_IN_REVIEW -> HintLine(L("Our team is checking your first photo. Put another one first, or wait."))
        PhotoSetCheck.NO_FACE -> HintLine(L("We can't see a face on your first photo. Put a clear photo of you first."), error = true)
        PhotoSetCheck.TOO_SMALL -> HintLine(L("Your face is too small on your first photo. Use a closer one first."), error = true)
        PhotoSetCheck.FAILED -> HintLine(L("A photo couldn't be sent. Tap it to see why, then try again."), error = true)
    }
}

@Composable
private fun HintLine(text: String, error: Boolean = false) {
    if (error) {
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.xs), verticalAlignment = Alignment.Top) {
            DrafftIcon("danger-circle", Modifier.padding(top = 1.dp), size = (13f * 1.2f).dp, tint = DS.palette.negative)
            Text(branded(text), style = TextStyles.footnote.medium, color = DS.palette.negative)
        }
    } else {
        // Body grey: mute is under 4.5:1 on the sage page.
        Text(branded(text), style = TextStyles.footnote, color = DS.palette.body)
    }
}

/** On the Photos row: how many photos moderation refused (tap the row, then a photo, to see why). */
@Composable
fun RefusedCountChip(count: Int, modifier: Modifier = Modifier) {
    val label = if (count == 1) L("1 refused") else L("%d refused", count)
    Row(
        modifier
            .height(24.dp)
            .background(DS.palette.negative, CircleShape)
            .padding(horizontal = 8.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrafftIcon("forbidden-circle", size = (TextStyles.caption2.fontSize.value * 1.2f).dp, tint = Color.White)
        Text(label, style = TextStyles.caption.bold, color = Color.White, maxLines = 1, softWrap = false)
    }
}
