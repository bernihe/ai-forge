package com.bernie.aiforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bernie.aiforge.presentation.navigation.AppNavigation
import com.bernie.aiforge.presentation.theme.AiForgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiForgeTheme {
                AppNavigation()
            }
        }
    }
}
