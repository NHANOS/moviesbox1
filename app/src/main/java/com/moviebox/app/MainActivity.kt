package com.moviebox.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moviebox.storage.ConfigStore
import com.moviebox.storage.HistoryStore
import com.moviebox.storage.FavoritesStore
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appCoroutineScope = kotlinx.coroutines.MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MovieBox", "MainActivity.onCreate called")

        // Initialize storage stores
        ConfigStore.init(this)
        HistoryStore.init(this)
        FavoritesStore.init(this)

        setContent {
            com.moviebox.ui.MovieBoxComposeApp()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MovieBox", "MainActivity.onResume called")
    }

    override fun onPause() {
        super.onPause()
        Log.d("MovieBox", "MainActivity.onPause called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MovieBox", "MainActivity.onDestroy called")
        appCoroutineScope.cancel()
    }
}