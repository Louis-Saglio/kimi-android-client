package fr.louissaglio.kimi.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import fr.louissaglio.kimi.android.navigation.KimiNavHost
import fr.louissaglio.kimi.android.ui.theme.KimiTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KimiTheme {
                KimiNavHost()
            }
        }
    }
}
