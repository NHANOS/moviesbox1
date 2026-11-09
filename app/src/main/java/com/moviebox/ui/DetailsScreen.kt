package com.moviebox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.model.MediaDetails
import com.moviebox.model.Release
import com.moviebox.model.Season
import com.moviebox.model.Episode

@Composable
fun DetailsScreen(
    details: MediaDetails,
    releases: List<Release>,
    onPlay: (Release) -> Unit,
    onDownload: (Release) -> Unit,
    onBack: () -> Unit,
    state: AppStateUi,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            DetailsHeader(
                details = details,
                onBack = onBack,
                state = state,
            )
        }

        if (details.genres.isNotEmpty()) {
            item {
                DetailsGenres(genres = details.genres)
            }
        }

        if (details.description != null) {
            item {
                DetailsDescription(description = details.description!!)
            }
        }

        if (details.seasons.isNotEmpty()) {
            item {
                DetailsSeasons(
                    seasons = details.seasons,
                    onSeasonSelect = { season, episode ->
                        // Handle season/episode selection
                    },
                    selectedSeason = 1,
                    selectedEpisode = 1,
                )
            }
        }

        if (releases.isNotEmpty()) {
            item {
                DetailsReleases(
                    releases = releases,
                    onPlay = onPlay,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@Composable
fun DetailsHeader(
    details: MediaDetails,
    onBack: () -> Unit,
    state: AppStateUi,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spaceBetween,
        ) {
            Text(
                text = details.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Back button
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.headlineSmall)
            }
        }

        // Poster area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🖼️ ${details.title}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Metadata row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (details.year != null) {
                MetadataChip(label = "Year", value = details.year!!)
            }
            if (details.duration != null) {
                MetadataChip(label = "Duration", value = details.duration!!)
            }
            if (details.imdbRating != null) {
                MetadataChip(label = "IMDb", value = details.imdbRating!!)
            }
            MetadataChip(
                label = "Type",
                value = when (details.mediaType) {
                    com.moviebox.model.MediaType.Movie -> "Movie"
                    com.moviebox.model.MediaType.Series -> "Series"
                }
            )
        }
    }
}

@Composable
fun MetadataChip(label: String, value: String) {
    Box(
        modifier = Modifier
            .padding(8.dp, 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun DetailsGenres(genres: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { genre ->
            Box(
                modifier = Modifier
                    .padding(8.dp, 4.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(
                    text = genre,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
fun DetailsDescription(description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
fun DetailsSeasons(
    seasons: List<Season>,
    onSeasonSelect: (Season, Episode) -> Unit,
    selectedSeason: Int,
    selectedEpisode: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Seasons",
                style = MaterialTheme.typography.titleMedium,
            )

            seasons.forEach { season ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Season ${season.number}",
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        season.episodes.forEach { episode ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(4.dp)
                                    .background(
                                        if (season.number == selectedSeason && episode.number == selectedEpisode) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "E${episode.number}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (episode.title != null) {
                                    Text(
                                        text = episode.title!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailsReleases(
    releases: List<Release>,
    onPlay: (Release) -> Unit,
    onDownload: (Release) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Available Streams",
                style = MaterialTheme.typography.titleMedium,
            )

            releases.forEach { release ->
                ReleaseRow(
                    release = release,
                    onPlay = { onPlay(release) },
                    onDownload = { onDownload(release) },
                )
            }
        }
    }
}

@Composable
fun ReleaseRow(
    release: Release,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Quality badge
        Box(
            modifier = Modifier
                .width(64.dp)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = release.quality ?: "Auto",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        // File info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = release.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                release.codec?.let { codec ->
                    Text(text = codec, style = MaterialTheme.typography.labelSmall)
                }
                release.language?.let { lang ->
                    Text(text = lang, style = MaterialTheme.typography.labelSmall)
                }
                release.sizeBytes?.let { size ->
                    Text(text = formatFileSize(size), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Action buttons
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Button(
                onClick = onPlay,
                modifier = Modifier.fillMaxHeight(),
            ) {
                Text("Play", style = MaterialTheme.typography.labelMedium)
            }

            androidx.compose.material3.TextButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxHeight(),
            ) {
                Text("⬇", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Format file size for display.
 */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }
}