package com.example.livephotostitch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.livephotostitch.data.StitchProjectState
import com.example.livephotostitch.ui.screens.EditScreen
import com.example.livephotostitch.ui.screens.HomeScreen
import com.example.livephotostitch.ui.screens.PhotoSelectionScreen
import com.example.livephotostitch.ui.screens.PreviewScreen
import com.example.livephotostitch.ui.screens.TemplateSelectionScreen
import com.example.livephotostitch.ui.theme.LivePhotoStitchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LivePhotoStitchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LivePhotoStitchApp()
                }
            }
        }
    }
}

@Composable
fun LivePhotoStitchApp() {
    val navController = rememberNavController()

    // 重置状态
    androidx.compose.runtime.LaunchedEffect(Unit) {
        StitchProjectState.reset()
    }

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onStartClick = { navController.navigate("photo_selection") }
            )
        }
        composable("photo_selection") {
            PhotoSelectionScreen(
                onNextClick = { navController.navigate("template_selection") },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable("template_selection") {
            TemplateSelectionScreen(
                onNextClick = { navController.navigate("edit") },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable("edit") {
            EditScreen(
                initialEffects = StitchProjectState.effects,
                onEffectsChanged = { effects ->
                    StitchProjectState.effects = effects
                },
                onNextClick = { navController.navigate("preview") },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable("preview") {
            PreviewScreen(
                onBackClick = { navController.popBackStack() },
                onHomeClick = {
                    navController.popBackStack("home", inclusive = false)
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    LivePhotoStitchTheme {
        LivePhotoStitchApp()
    }
}
