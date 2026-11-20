package com.intj.layover

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.intj.layover.model.LayoverUiState
import com.intj.layover.navigation.LayoverNavGraph
import com.intj.layover.ui.theme.LayoverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LayoverTheme {
                var uiState by remember { mutableStateOf(LayoverUiState()) }
                LayoverNavGraph(
                    uiState = uiState,
                    onUpdateUiState = { newState -> uiState = newState },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
