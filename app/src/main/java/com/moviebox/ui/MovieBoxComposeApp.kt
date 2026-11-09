package com.moviebox.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.moviebox.storage.ConfigStore
import com.moviebox.storage.HistoryStore
import com.moviebox.storage.FavoritesStore

@Composable
fun MovieBoxComposeApp() {
    val context = LocalContext.current

    // Initialize singleton stores
    DisposableEffect(Unit) {
        ConfigStore.instance?.apply { init(context) }
        HistoryStore.instance?.apply { init(context) }
        FavoritesStore.instance?.apply { init(context) }
        cleanup { /* cleanup on composition drop */ }
    }

    val navController = rememberNavController()

    MovieBoxScaffold(darkTheme = true) {
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        ) {
            composable("home") {
                HomeScreen(
                    onSelectMovie = { movieId ->
                        // Navigate to details or handle movie selection
                    },
                    onSelectSeries = { seriesId ->
                        // Navigate to series details
                    },
                    onSearch = { query ->
                        // Handle search
                    },
                    onSettings = { /* open settings */ },
                    onTvMode = { /* toggle TV mode */ },
                    state = remember { AppStateUi() },
                )
            }

            composable("details/{id}") { backStackEntry ->
                val id = backStackEntry.args?.getString("id") ?: ""
                val details = remember { MediaDetails(
                    id = com.moviebox.model.ProviderMediaId(
                        com.moviebox.model.ProviderKind.MovieBox,
                        id
                    ),
                    title = "Unknown Title",
                    mediaType = com.moviebox.model.MediaType.Movie,
                    year = null,
                    description = null,
                    tagline = null,
                    imdbRating = null,
                    director = null,
                    stars = null,
                    prints = null,
                    audios = null,
                    posterUrl = null,
                    duration = null,
                    genres = emptyList(),
                    seasons = emptyList(),
                    dubs = emptyList()
                )}
                val releases = remember { emptyList<com.moviebox.model.Release>() }

                DetailsScreen(
                    details = details,
                    releases = releases,
                    onPlay = { release ->
                        // Handle playback
                    },
                    onDownload = { release ->
                        // Handle download
                    },
                    onBack = { navController.popBackStack() },
                    state = remember { AppStateUi() },
                )
            }
        }
    }
}