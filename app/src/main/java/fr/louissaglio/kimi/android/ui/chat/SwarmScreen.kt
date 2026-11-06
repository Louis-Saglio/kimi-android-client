package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.TaskControl
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.navigation.Swarm
import fr.louissaglio.kimi.android.ui.common.EmptyState
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One `AgentSwarm` call's roster (ticket 134): the member list of a single swarm,
 * addressed by the call's toolCallId. Everything renders live from the
 * [SubagentStreamStore] — the one-to-many link registry names the members, their
 * records feed the rows — so this ViewModel holds no socket and no fetch of its
 * own; the pageOpened/pageClosed pair keeps the store fed exactly like the
 * sub-agent page does (ticket 064's keep-alive, which also rebuilds the roster
 * from the snapshot after process death).
 */
@HiltViewModel
class SwarmViewModel internal constructor(
    private val subagentStore: SubagentStreamStore,
    private val chatRepository: ChatRepository,
    private val routeArgs: Swarm,
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route (ticket 083). Tests call the primary constructor with
    // the route object directly (toRoute needs a real android.os.Bundle).
    @Inject
    constructor(
        subagentStore: SubagentStreamStore,
        chatRepository: ChatRepository,
        savedStateHandle: SavedStateHandle,
    ) : this(subagentStore, chatRepository, savedStateHandle.toRoute<Swarm>())

    private val sessionId: String = routeArgs.sessionId
    private val toolCallId: String = routeArgs.toolCallId

    /** [description] is the swarm call's own label (its `description` arg), null
     * until the call's args reached the store; the top bar falls back to the bare
     * title then. [members] are sorted by swarmIndex, nulls in arrival order.
     * [stoppingMemberIds] latches the ids whose stop call succeeded or is in
     * flight (ticket 137): the row's Stop disables on tap and stays down — the
     * wire (the ended task state, outcome killed, else the settled call's result) flips the
     * member terminal, which hides the button. A failure removes the latch. */
    data class UiState(
        val sessionId: String,
        val toolCallId: String,
        val description: String? = null,
        val members: List<SubagentStreamStore.SubagentState> = emptyList(),
        val stoppingMemberIds: Set<String> = emptySet(),
    )

    /** One-shot snackbars (the ChatViewModel.Effect pattern). */
    sealed interface Effect {
        val message: String

        /** The stop call failed: the row's Stop re-enables, the member keeps its
         * true state. */
        data class MemberStopFailed(override val message: String) : Effect
    }

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState(sessionId = sessionId, toolCallId = toolCallId))

    init {
        viewModelScope.launch {
            subagentStore.swarmMembers(sessionId, toolCallId).collect { members ->
                uiState.update { it.copy(members = members) }
            }
        }
        viewModelScope.launch {
            subagentStore.swarmDescription(sessionId, toolCallId).collect { description ->
                uiState.update { it.copy(description = description) }
            }
        }
    }

    /** Wired to ON_START. */
    fun onScreenStarted() {
        subagentStore.pageOpened(sessionId)
    }

    /** Wired to ON_STOP. */
    fun onScreenStopped() {
        subagentStore.pageClosed(sessionId)
    }

    /** Stops one running member with the flat `task.stop` control (ticket 179 —
     * server 2.5.0 dropped REST `:cancel`), the same repository call the jobs sheet
     * uses, so error surfacing stays consistent. No confirmation (web parity). The
     * button disables on tap and stays down on success — NO optimistic status flip:
     * the row settles when the member's ended task state (outcome `killed`, ticket
     * 135's pin) or the settled call's result lands. An already-ended member just
     * replies with its state (code 0). An error reply or a dropped socket re-enables
     * the button and snackbars. */
    fun onStopMember(memberId: String) {
        val member = uiState.value.members.firstOrNull { it.id == memberId } ?: return
        if (!member.stoppable || member.id in uiState.value.stoppingMemberIds) return
        val taskId = member.taskId ?: return
        uiState.update { it.copy(stoppingMemberIds = it.stoppingMemberIds + memberId) }
        viewModelScope.launch {
            chatRepository.sendTaskControl(sessionId, TaskControl.Stop, taskId)
                .onFailure { error ->
                    uiState.update { it.copy(stoppingMemberIds = it.stoppingMemberIds - memberId) }
                    _effects.send(Effect.MemberStopFailed(error.toUserMessage()))
                }
        }
    }
}

@Composable
fun SwarmScreen(
    onNavigateBack: () -> Unit,
    /** A member row opens its own streaming sub-agent page (ticket 045's screen). */
    onOpenSubagent: (String) -> Unit = {},
    viewModel: SwarmViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect -> snackbarHostState.showSnackbar(effect.message) }
    }
    // Registers the page while visible (ON_START/ON_STOP pair), exactly like the
    // sub-agent page: the registration keeps the store fed for as long as a page
    // of the session is open — by the chat's socket gate when it exists, by the
    // store's own keep-alive collection otherwise (064).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
    SwarmContent(
        uiState,
        onNavigateBack,
        onOpenSubagent,
        onStopMember = viewModel::onStopMember,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwarmContent(
    uiState: SwarmViewModel.UiState,
    onNavigateBack: () -> Unit,
    onOpenSubagent: (String) -> Unit = {},
    /** A running member row's trailing Stop (ticket 137), reported by member id. */
    onStopMember: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Swarm",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        uiState.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                text = description,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.members.isEmpty()) {
                // Deep cold start before the roster replay lands: nothing is known
                // about the swarm's members yet.
                EmptyState("No members known yet")
            } else {
                val progress = SwarmProgress.of(uiState.members, expectedTotal = null, toolRunning = false)
                Column(Modifier.fillMaxSize()) {
                    // The summary strip (ticket 135): the same aggregate input as the
                    // chat's swarm card — the present phases' counts, plus the shared
                    // model line when every member runs the same model/effort.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("swarmSummaryStrip"),
                    ) {
                        Text(
                            text = progress.countsLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        progress.modelLine?.let { modelLine ->
                            Text(
                                text = modelLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(uiState.members, key = { it.id }) { member ->
                            SwarmMemberRow(
                                member,
                                stopping = member.id in uiState.stoppingMemberIds,
                                onClick = { onOpenSubagent(member.id) },
                                onStop = { onStopMember(member.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One member of the roster: its live status chip, its label (the record's
 * description, else the subagent type, else the bare id on a deep cold start), the
 * one-line activity under the label (ticket 136 — the last assistant text while
 * working, the suspend cause while suspended, the result/error's first line once
 * terminal) and the open chevron; the whole row is the tap target. A ghost row
 * (a backfilled `not_started` member, ticket 136) has no sub-agent page to open:
 * no chevron, not clickable. A stoppable member ([SubagentStreamStore.SubagentState.stoppable]
 * — running, task id known, no ghost) carries a trailing Stop button (ticket 137,
 * the jobs sheet's close-icon idiom, no confirmation), disabled while its stop
 * call is in flight ([stopping]). */
@Composable
private fun SwarmMemberRow(
    member: SubagentStreamStore.SubagentState,
    stopping: Boolean,
    onClick: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // M3: the whole row is the tap target, at least 48dp tall.
            .heightIn(min = 48.dp)
            .clickable(enabled = !member.ghost, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SubagentStatusChip(member.status)
        Column(Modifier.weight(1f)) {
            Text(
                text = member.description.ifBlank { member.name }.ifBlank { member.id },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            member.activityLine?.let { activity ->
                Text(
                    text = activity,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (member.stoppable) {
            IconButton(
                onClick = onStop,
                enabled = !stopping,
                modifier = Modifier.testTag("stopMember:${member.id}"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Stop",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!member.ghost) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open sub-agent",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
