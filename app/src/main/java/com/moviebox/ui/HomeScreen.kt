package com.moviebox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    onSelectMovie: (String) -> Unit,
    onSelectSeries: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSettings: () -> Unit,
    onTvMode: () -> Unit,
    state: AppStateUi,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MovieBox") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { onTvMode() }) {
                        Text("TV", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { onSettings() }) {
                        Text("⚙️", style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                IconButton(onClick = { /* stream */ }) {
                    Text("S", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { /* tv */ }) {
                    Text("T", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { /* help */ }) {
                    Text("?", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { /* quit */ }) {
                    Text("q", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MovieBoxSearchField(
                placeholder = "Search movies, series & anime…",
                onTextChange = { onSearch(it) },
                isBasicTerminal = state.isBasicTerminal,
                searchQuery = state.searchQuery,
            )

            // Content area
            if (state.isTvMode) {
                Text("Live TV content area", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("Movies content area", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun MovieBoxSearchField(
    placeholder: String,
    onTextChange: (String) -> Unit,
    isBasicTerminal: Boolean,
    searchQuery: String?,
) {
    var text by remember { mutableStateOf(searchQuery ?: "") }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onTextChange(it)
        },
        placeholder = { Text(placeholder) },
        label = { Text("Search") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontSize = FontSize(14.sp)),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(),
        trailingIcon = {
            IconButton(onClick = { text = "" }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                )
            }
        },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        supportingText = {
            if (isBasicTerminal) Text("Basic terminal mode", style = MaterialTheme.typography.bodySmall)
        },
    )
}

/**
 * Simple state interface for the home screen used by Compose UI.
 */
data class AppStateUi(
    val isTvMode: Boolean = false,
    val isBasicTerminal: Boolean = true,
    val hasActiveModal: Boolean = false,
    val streamingEnabled: Boolean = true,
    val tvEnabled: Boolean = true,
    val activeSearchRequests: Int = 0,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val areaWidth: Int = 1080,
)
