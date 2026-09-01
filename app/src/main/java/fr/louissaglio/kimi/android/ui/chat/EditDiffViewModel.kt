package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.EditDiffs
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.navigation.EditDiff
import javax.inject.Inject

/**
 * The Edit call's diff screen (ticket 119): a static page — the payload arrives
 * through [ToolContentStore] (staged by the navigation callback, consumed here on
 * init) and the diff model is derived once. No StateFlow: nothing on this screen
 * changes after construction.
 *
 * A store miss ([payload] null) is the process-death case: the entry died with the
 * process that staged it. The screen renders its missing-content state with back
 * navigation; the chat cold-restarts from its snapshot and re-tapping the row
 * re-stages the payload.
 */
@HiltViewModel
class EditDiffViewModel internal constructor(
    toolContentStore: ToolContentStore,
    routeArgs: EditDiff,
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route (ticket 083). Local unit and instrumentation tests call
    // the primary constructor with the route object directly — toRoute's decode path
    // needs a real android.os.Bundle (the JobDetailViewModel pattern).
    @Inject
    constructor(
        toolContentStore: ToolContentStore,
        savedStateHandle: SavedStateHandle,
    ) : this(toolContentStore, savedStateHandle.toRoute<EditDiff>())

    val toolCallId: String = routeArgs.toolCallId

    /** The staged Edit payload; null when the store lost the entry. A non-Edit
     * payload under the id (a later viewer ticket's hand-off reaching this screen)
     * is equally a miss. */
    val payload: ToolPayload.Edit? = toolContentStore.consume(routeArgs.toolCallId)?.payload as? ToolPayload.Edit

    /** The unified line-diff model; null together with [payload]. */
    val diff: EditDiffs.Model? = payload?.let { EditDiffs.of(it.oldString, it.newString) }
}
