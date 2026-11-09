package com.moviebox.network

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.moviebox.model.ProviderError
import com.moviebox.model.ProviderKind
import com.moviebox.model.ProviderMediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OKHttp-based HTTP client for the MovieBox Android application.
 * Provides request/response handling with proper headers, timeouts, and error handling.
 */
class HttpClient {

    private val client: OkHttpClient
    private val logger: (String) -> Unit = { Log.d("MovieBox.Network", it) }

    init {
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Perform a GET request and return the response body as a string.
     */
    suspend fun get(url: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code ${response.code} for $url")
                }
                response.body?.string() ?: throw IOException("Empty response body")
            } finally {
                response.body?.close()
            }
        }
    }

    /**
     * Perform a GET request with custom headers.
     */
    suspend fun getWithHeaders(url: String, headers: (String, String)): String {
        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (name, value) ->
                requestBuilder.header(name, value)
            }
            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code ${response.code} for $url")
                }
                response.body?.string() ?: throw IOException("Empty response body")
            } finally {
                response.body?.close()
            }
        }
    }

    /**
     * Perform a POST request with form data.
     */
    suspend fun post(url: String, body: String): String {
        return withContext(Dispatchers.IO) {
            val requestBody = RequestBody.create(body, MediaType.parse("application/json"))
            val request = Request.Builder().url(url).post(requestBody).build()
            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code ${response.code} for POST $url")
                }
                response.body?.string() ?: throw IOException("Empty response body")
            } finally {
                response.body?.close()
            }
        }
    }

    /**
     * Perform a GET request with range header for partial content/streaming support.
     */
    suspend fun getWithRange(url: String, range: String): Response {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Range", range).build()
            client.newCall(request).execute()
        }
    }

    /**
     * Perform a HEAD request to probe URL availability.
     */
    suspend fun head(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).head().build()
            val response: okhttp3.Response = client.newCall(request).execute()
            return response.isSuccessful
        }
    }

    /**
     * Extract host authority from a URL (scheme + host + port).
     */
    fun extractHostAuthority(url: String): String? {
        try {
            val parsed = HttpUrl.parse(url)
            return if (parsed != null) {
                "${parsed.scheme()}://${parsed.host()}${if (parsed.port() != -1) { ":${parsed.port()}" } else { "" }}"
            } else null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Check if URL is HTTP/HTTPS.
     */
    fun isHttpUrl(url: String): Boolean {
        return url.trim().startsWith("http://") || url.trim().startsWith("https://")
    }

    /**
     * Cancel ongoing operations and release resources.
     */
    fun cancel() {
        client.dispatcher().cancelAll()
    }
}

/**
 * LiveData wrapper for HTTP request results.
 */
class HttpResultLiveData<T>(private val onResult: (T?, ProviderError?) -> Unit) : LiveData<T>() {

    private var currentResult: T? = null
    private var currentError: ProviderError? = null

    fun setResult(result: T?) {
        currentResult = result
        postValue(result)
    }

    fun setError(error: ProviderError?) {
        currentError = error
        postValue(currentResult)
    }

    override fun activeObservers(): Int {
        return super.activeObservers()
    }
}

/**
 * Scope function for IO operations with error handling.
 */
fun <T> CoroutineScope.ioRequest(
    block: suspend () -> T,
    onError: (ProviderError) -> Unit = { Log.e("MovieBox.Network", it.message) }
): T {
    return withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            onError(ProviderError.Network(e.message ?: "Network error"))
            throw e
        } catch (e: Exception) {
            onError(ProviderError.Network(e.message ?: "Unknown error"))
            throw e
        }
    }
}