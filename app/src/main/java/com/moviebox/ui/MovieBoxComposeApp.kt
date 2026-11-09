package com.moviebox.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.moviebox.ui.theme.MovieBoxScaffold

@Composable
fun MovieBoxComposeApp() {
    // Stores MainActivity.onCreate() में पहले ही init हो चुके हैं
    val navController = rememberNavController()

    MovieBoxScaffold(darkTheme = true) {
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
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
                val id = backStackEntry.arguments?.getString("id") ?: ""
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