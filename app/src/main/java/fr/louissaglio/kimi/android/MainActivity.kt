package fr.louissaglio.kimi.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import fr.louissaglio.kimi.android.navigation.KimiNavHost
import fr.louissaglio.kimi.android.ui.chat.CompactionSummaryStore
import fr.louissaglio.kimi.android.ui.chat.ToolContentStore
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** The row-tap → detail-screen payload hand-off (ticket 119): the NavHost's
     * EditDiff navigation stages the payload here, the screen consumes it. */
    @Inject
    lateinit var toolContentStore: ToolContentStore

    /** The compaction divider's summary hand-off (ticket 130), same pattern. */
    @Inject
    lateinit var compactionSummaryStore: CompactionSummaryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KimiTheme {
                KimiNavHost(toolContentStore, compactionSummaryStore)
            }
        }
    }
}
