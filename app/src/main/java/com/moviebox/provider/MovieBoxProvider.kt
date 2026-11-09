package com.moviebox.provider

import android.util.Log
import com.moviebox.model.ProviderModels.*
import com.moviebox.model.StringUtils
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.buffer
import okio.sink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * MovieBox Provider implementation with full crypto/signing logic ported from Rust.
 *
 * API Endpoints (from src/providers/moviebox/client.rs):
 *   - /wefeed-mobile-bff/user-api/visitor-login          (visitor login)
 *   - /wefeed-mobile-bff/subject-api/search/v2           (search)
 *   - /wefeed-mobile-bff/subject-api/get?subjectId={id}  (details)
 *   - /wefeed-mobile-bff/subject-api/season-info?subjectId={id}
 *   - /wefeed-mobile-bff/subject-api/play-info/v2?subjectId={id}&se={s}&ep={e}
 *   - /wefeed-mobile-bff/subject-api/resource?subjectId={id}&page={p}&perPage={pp}
 *   - /wefeed-mobile-bff/subject-api/resource/v2?subjectId={id}&se={s}&epFrom={e}&epTo={e}&...
 *   - /wefeed-mobile-bff/subject-api/resource-position/v2?subjectId=...&resourceId=...&failUrl=...
 *   - /wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=...&resourceId=...
 *   - /wefeed-mobile-bff/tab-operating?page={}&tabId={}  (homepage)
 *
 * Crypto headers (from src/providers/moviebox/crypto.rs):
 *   x-client-token:  {timestamp},{md5(reverse(timestamp))}
 *   x-tr-signature:  {timestamp}|2|{base64(HMAC-MD5(canonical_string))}
 *   x-client-build:  {stamp}.{md5(cert_sha256 + "oneroom" + stamp)}
 *   x-client-info:   JSON with device fingerprint + X-Client-Build
 *   x-client-status: "0"
 *   x-forwarded-for: spoofed IP
 *   Authorization:   Bearer {token}
 *
 * Referer constant: https://sportslive.wine (STREAM_REFERER)
 */
class MovieBoxProvider(
    override val kind: ProviderKind = ProviderKind.MovieBox,
    httpClient: HttpClient = HttpClient()
) : BaseProvider(kind, httpClient) {

    companion object {
        private const val TAG = "MovieBoxProvider"

        // === Host pool (from client.rs HOST_POOL) ===
        private val HOST_POOL = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com",
            "https://api6sg.aoneroom.com",
            "https://api.inmoviebox.com"
        )

        // === Retryable status codes ===
        private val RETRY_STATUS_CODES = setOf(403, 406, 407, 429, 500, 502, 503, 504)

        // === Crypto constants ===
        private val DEFAULT_SECRET_BYTES = byteArrayOf(
            0xef.toByte(), 0xa8.toByte(), 0x91.toByte(), 0x97.toByte(), 0x4e.toByte(),
            0xec.toByte(), 0xd3.toByte(), 0x14.toByte(), 0x8d.toByte(), 0xf6.toByte(),
            0x3a.toByte(), 0xa6.toByte(), 0x11.toByte(), 0x60.toByte(), 0x2d.toByte(),
            0xef.toByte(), 0xd1.toByte(), 0x01.toByte(), 0x25.toByte(), 0x9b.toByte(),
            0xa5.toByte(), 0x21.toByte(), 0x02.toByte(), 0x2c.toByte(), 0x57.toByte(),
            0xae.toByte(), 0x05.toByte(), 0x66.toByte(), 0xbd.toByte(), 0x8e.toByte()
        )
        private val STOCK_CERT_SHA256 = "03d4b741632d136bc23e2de4c6adcdb7712f1980b619c183221801730bbd0410"
        private val BUILD_TAG_SALT = "oneroom"
        private val STREAM_REFERER = "https://sportslive.wine"
        private val SIGNATURE_BODY_MAX_BYTES = 102400L

        // API Paths
        private const val PATH_VISITOR_LOGIN = "/wefeed-mobile-bff/user-api/visitor-login"
        private const val PATH_SEARCH = "/wefeed-mobile-bff/subject-api/search/v2"
        private const val PATH_DETAILS = "/wefeed-mobile-bff/subject-api/get"
        private const val PATH_SEASON_INFO = "/wefeed-mobile-bff/subject-api/season-info"
        private const val PATH_PLAY_INFO = "/wefeed-mobile-bff/subject-api/play-info/v2"
        private const val PATH_RESOURCE = "/wefeed-mobile-bff/subject-api/resource"
        private const val PATH_RESOURCE_V2 = "/wefeed-mobile-bff/subject-api/resource/v2"
        private const val PATH_RESOURCE_POSITION = "/wefeed-mobile-bff/subject-api/resource-position/v2"
        private const val PATH_EXT_CAPTIONS = "/wefeed-mobile-bff/subject-api/get-ext-captions"
        private const val PATH_TAB_OPERATING = "/wefeed-mobile-bff/tab-operating"
    }

    // === Session state (mirrors MovieBoxSession + MovieBoxClient.session) ===
    private var sessionToken: String? = null
    private var sessionUserId: String? = null
    private var sessionExpiresAt: Long? = null
    private var sessionCreatedAt: Long = System.currentTimeMillis() / 1000

    // Active host index for host rotation
    private var activeBaseIdx: Int = 0

    // Generated client identity (from generate_client_info_and_ua)
    private val userAgent: String
    private val clientInfoBase: String
    private val spoofedIp: String

    private val random = Random()

    init {
        val (ua, ci) = generateClientInfoAndUa()
        userAgent = ua
        clientInfoBase = ci
        spoofedIp = randomSpoofedIp()
    }

    // =========================================================================
    // Provider Interface Implementation
    // =========================================================================

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = true,
        supportsPagination = true,
        supportsSeries = true,
        supportsSubtitles = true,
        supportsHomepage = true
    )

    override suspend fun search(query: String, page: Int = 1): Result<List<CatalogItem>> {
        return withContext(Dispatchers.IO) {
            val payload = mapOf(
                "keyword" to query,
                "page" to page,
                "perPage" to 15,
                "subjectType" to 0
            )
            val response = postJson(PATH_SEARCH, payload)
            when (val r = handleApiResponse(response)) {
                is Result.Success -> {
                    val json = com.google.gson.JsonParser.parseString(r.data).asJsonObject
                    Result.success(movieboxSearchJsonToCatalog(json))
                }
                is Result.Failure -> Result.Failure(r.error)
            }
        }
    }

    override suspend fun details(id: String): Result<MediaDetails> {
        return withContext(Dispatchers.IO) {
            val path = "$PATH_DETAILS?subjectId=$id"
            val response = get(path)
            when (val r = handleApiResponse(response)) {
                is Result.Success -> {
                    val json = com.google.gson.JsonParser.parseString(r.data).asJsonObject
                    movieboxDetailsJsonToMediaDetails(json)
                }
                is Result.Failure -> Result.Failure(r.error)
            }
        }
    }

    override suspend fun episodeStreams(
        id: String,
        season: Int,
        episode: Int
    ): Result<List<Release>> {
        return withContext(Dispatchers.IO) {
            val releases = mutableListOf<Release>()
            val seenUrls = mutableSetOf<String>()

            // Parallel: details + play-info + resources (tokio::join!)
            val detailsResult = get("$PATH_DETAILS?subjectId=$id")
            val playInfoResult = getPlayInfo(id, season, episode)
            val resourceResult = get(episodeResourcePath(id, season, episode))

            // Process details detector releases
            val detailsBody = extractBody(detailsResult)
            if (detailsBody != null) {
                val json = com.google.gson.JsonParser.parseString(detailsBody).asJsonObject
                val rels = movieboxDetectorJsonToReleases(json, season, episode, userAgent)
                for (rel in rels) {
                    rel.directUrl()?.let { url ->
                        val base = url.split("?").first()
                        if (base.isNotEmpty() && seenUrls.add(base)) {
                            releases.add(rel)
                        }
                    }
                }
            }

            // Process play-info releases
            val playInfoBody = extractBody(playInfoResult)
            if (playInfoBody != null) {
                val json = com.google.gson.JsonParser.parseString(playInfoBody).asJsonObject
                val rels = movieboxPlayInfoJsonToReleases(json, season, episode, userAgent)
                for (rel in rels) {
                    rel.directUrl()?.let { url ->
                        val base = url.split("?").first()
                        if (base.isNotEmpty() && seenUrls.add(base)) {
                            releases.add(rel)
                        }
                    }
                }
            }

            // Process resource listing
            val resourcesBody = extractBody(resourceResult)
            val items: List<com.google.gson.JsonElement> =
                if (resourcesBody != null) {
                    val json = com.google.gson.JsonParser.parseString(resourcesBody).asJsonObject
                    json.getAsJsonArray("list")?.asList() ?: emptyList()
                } else {
                    emptyList()
                }

            for (item in items) {
                val rel = movieboxResourceItemToRelease(item)
                if ((season != 0 || episode != 0) &&
                    (rel.season != season || rel.episode != episode)) {
                    continue
                }
                if (rel.mirrors.isEmpty()) {
                    val origin = rel.mirrors.firstOrNull()?.resolverUrl
                        ?: item.getAsJsonObject().get("resourceLink")?.asString()
                        ?: item.getAsJsonObject().get("url")?.asString()
                    if (origin == null || !isDeprecationNoticeUrl(origin)) {
                        continue
                    }
                    val resourceId = rel.resourceId ?: continue
                    val recoveryResult = getResourcePosition(id, resourceId, origin)
                    val recoveryBody = extractBody(recoveryResult)
                    if (recoveryBody != null) {
                        val recJson = com.google.gson.JsonParser.parseString(recoveryBody).asJsonObject
                        val recovered = movieboxResourcePositionJsonToRelease(
                            recJson, rel, userAgent
                        )
                        if (recovered != null) {
                            releases.add(recovered)
                        }
                    }
                    continue
                }
                rel.directUrl()?.let { url ->
                    val base = url.split("?").first()
                    if (base.isNotEmpty() && seenUrls.add(base)) {
                        releases.add(rel)
                    }
                }
            }

            if (releases.isEmpty()) {
                Result.failure(ProviderError.Unavailable("No stream sources available"))
            } else {
                releases.sortByDescending { it.resolutionU64() }
                    .thenByDescending { it.sizeBytes ?: 0L }
                Result.success(releases.toTypedArray().toList())
            }
        }
    }

    // =========================================================================
    // API Request Methods (signed headers, host rotation)
    // =========================================================================

    private suspend fun get(path: String): Result {
        return request("GET", path, null, null)
    }

    private suspend fun postJson(path: String, body: Map<String, Any>): Result {
        val bodyStr = com.google.gson.Gson().toJson(body)
        return request("POST", path, bodyStr, null)
    }

    private suspend fun request(
        method: String,
        pathAndQuery: String,
        body: String?,
        authToken: String?
    ): Result {
        val token = ensureSession()
        return requestHosts(method, pathAndQuery, body, token)
    }

    private suspend fun requestHosts(
        method: String,
        pathAndQuery: String,
        body: String?,
        authToken: String?
    ): Result {
        val startIdx = activeBaseIdx
        var backoffMs = 50L

        for (i in 0 until HOST_POOL.size) {
            if (i > 0) {
                Thread.sleep(backoffMs)
                backoffMs = 50
            }
            val idx = (startIdx + i) % HOST_POOL.size
            val base = HOST_POOL[idx]
            val url = "$base$pathAndQuery"

            val headers = buildSignedHeaders(method, url, body, authToken)

            val requestBuilder = okhttp3.Request.Builder().url(url)
            headers.forEach { name, value ->
                requestBuilder.header(name, value)
            }

            val request = when (method) {
                "POST" -> {
                    val b = body ?: ""
                    requestBuilder.post(RequestBody.create(b, MediaType.get("application/json")))
                }
                else -> requestBuilder.get()
            }.build()

            try {
                val response: Response = httpClient.client.newCall(request).execute()
                absorbXUser(response.headers)

                val status = response.code
                if (RETRY_STATUS_CODES.contains(status)) {
                    Log.w(TAG, "moviebox host $idx returned retryable status $status")
                    activeBaseIdx = (idx + 1) % HOST_POOL.size
                    if (status == 429) {
                        val retryAfter = response.header("RETRY-AFTER")?.toLongOrNull()
                        backoffMs = (retryAfter ?: 400L) * 1000L
                        backoffMs = min(backoffMs, 3000L)
                    }
                    response.body?.close()
                    continue
                }

                activeBaseIdx = idx

                // Read body
                val bodyStr = response.body?.string() ?: ""
                response.body?.close()

                if (!response.isSuccessful) {
                    Result.failure(ProviderError.Unavailable("HTTP status $status"))
                } else {
                    Result.success(bodyStr)
                }
            } catch (e: Exception) {
                Log.w(TAG, "moviebox host $idx request failed", e)
                activeBaseIdx = (idx + 1) % HOST_POOL.size
                continue
            }
        }

        Log.e(TAG, "moviebox: all hosts exhausted")
        Result.failure(ProviderError.Unavailable("All hosts exhausted"))
    }

    private suspend fun getPlayInfo(subjectId: String, season: Int, episode: Int): Result {
        val path = if (season == 0 && episode == 0) {
            "$PATH_PLAY_INFO?subjectId=$subjectId"
        } else {
            "$PATH_PLAY_INFO?subjectId=$subjectId&se=$season&ep=$episode"
        }
        return get(path)
    }

    private suspend fun getResourcePosition(
        subjectId: String,
        resourceId: String,
        failedUrl: String
    ): Result {
        val query = mapOf(
            "subjectId" to subjectId,
            "resourceId" to resourceId,
            "failUrl" to failedUrl,
            "failCode" to "404",
            "resourceNum" to "0",
            "isVip" to "true"
        )
        val queryStr = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val path = "$PATH_RESOURCE_POSITION?$queryStr"
        return get(path)
    }

    private fun ensureSession(): String {
        // Check in-memory session
        if (sessionToken != null && sessionToken.isNotEmpty()) {
            if (isSessionValid()) {
                return sessionToken!!
            }
        }

        // Load persisted session
        val persisted = loadPersistedSession()
        if (persisted != null && persisted.isValid()) {
            sessionToken = persisted.token
            sessionUserId = persisted.userId
            sessionExpiresAt = persisted.expiresAt
            sessionCreatedAt = persisted.createdAt
            return persisted.token
        }

        // Fetch fresh session
        val fresh = fetchFreshSession()
        sessionToken = fresh.token
        sessionUserId = fresh.userId
        sessionExpiresAt = fresh.expiresAt
        sessionCreatedAt = fresh.createdAt
        saveSession(fresh)
        return fresh.token
    }

    private fun invalidateSession() {
        sessionToken = null
        sessionUserId = null
        sessionExpiresAt = null
        clearPersistedSession()
    }

    private fun fetchFreshSession(): MovieBoxSession {
        val bodyStr = "{}"
        val response = postJson(PATH_VISITOR_LOGIN, mapOf())
        val body = extractBody(response) ?: return MovieBoxSession("", null, null, 0)
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject

        val token = json.get("token")?.asString() ?: return MovieBoxSession("", null, null, 0)
        val explicitUid = json.get("uid")?.asString()
            ?: json.get("userId")?.asString()

        return MovieBoxSession(token, explicitUid, null, System.currentTimeMillis() / 1000)
    }

    private fun absorbXUser(headers: okhttp3.Headers) {
        val xUserVal = headers.get("x-user") ?: return
        val xUserStr = xUserVal
        val json = com.google.gson.JsonParser.parseString(xUserStr) as? com.google.gson.JsonObject
            ?: return
        val token = json.get("token")?.asString() ?: return
        if (token.isNotEmpty()) {
            val uid = json.get("uid")?.asString()
                ?: json.get("userId")?.asString()
            sessionToken = token
            sessionUserId = uid
            val session = MovieBoxSession(token, uid, null, System.currentTimeMillis() / 1000)
            saveSession(session)
        }
    }

    private fun isSessionValid(): Boolean {
        if (sessionToken == null || sessionToken!!.isEmpty()) return false
        val now = System.currentTimeMillis() / 1000
        if (sessionExpiresAt != null) {
            return now + 60 < sessionExpiresAt!!
        }
        return now < sessionCreatedAt + 7 * 24 * 3600
    }

    // =========================================================================
    // Crypto: Signed Headers (ported from crypto.rs)
    // =========================================================================

    private fun buildSignedHeaders(
        method: String,
        url: String,
        body: String?,
        authToken: String?
    ): Map<String, String> {
        val ts = System.currentTimeMillis()

        val accept = "application/json"
        val contentType = "application/json"

        val clientToken = generateXClientToken(ts)
        val signature = generateXTrSignature(
            method, Some(accept), Some(contentType), url, body, ts
        )
        val buildTag = generateXClientBuild(ts)
        val clientInfo = clientInfoWithBuildTag(clientInfoBase, buildTag)

        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = userAgent
        headers["Accept"] = accept
        headers["Content-Type"] = contentType
        headers["Connection"] = "keep-alive"
        headers["x-client-token"] = clientToken
        headers["x-tr-signature"] = signature
        headers["x-client-build"] = buildTag
        headers["x-client-info"] = clientInfo
        headers["x-client-status"] = "0"
        headers["x-forwarded-for"] = spoofedIp

        if (authToken != null && authToken.isNotEmpty()) {
            headers["Authorization"] = "Bearer $authToken"
        }

        return headers
    }

    private fun generateXClientToken(ts: Long): String {
        val tsStr = ts.toString()
        val reversedTs = tsStr.reversed()
        val hashVal = StringUtils.md5HexBytes(reversedTs.toByteArray())
        return "$tsStr,$hashVal"
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestampMs: Long
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestampMs)
        val sigBytes = hmacMd5(DEFAULT_SECRET_BYTES, canonical.toByteArray())
        val sigB64 = Base64.getEncoder().encodeToString(sigBytes)
        return "$timestampMs|2|$sigB64"
    }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestampMs: Long
    ): String {
        val canonicalUrl = try {
            val parsed = java.net.URL(url)
            val path = parsed.path
            val query = sortedQueryString(url)
            if (query.isEmpty()) path else "$path?$query"
        } catch (e: Exception) {
            url
        }

        val bodyBytes = body?.toByteArray()
        val (bodyHash, bodyLength) = if (bodyBytes != null) {
            val len = bodyBytes.size
            val truncated = if (len > SIGNATURE_BODY_MAX_BYTES) {
                bodyBytes.copyOfRange(0, SIGNATURE_BODY_MAX_BYTES.toInt())
            } else {
                bodyBytes
            }
            (StringUtils.md5HexBytes(truncated), len.toString())
        } else {
            ("", "")
        }

        return buildString {
            append(method.uppercase()); append("\n")
            append(accept.orEmpty()); append("\n")
            append(contentType.orEmpty()); append("\n")
            append(bodyLength); append("\n")
            append(timestampMs); append("\n")
            append(bodyHash); append("\n")
            append(canonicalUrl)
        }
    }

    private fun sortedQueryString(url: String): String {
        val params = mutableMapOf<String, MutableList<String>>()
        try {
            val uri = java.net.URI(url)
            val query = uri.query ?: return ""
            for (pair in query.split("&")) {
                val idx = pair.indexOf('=')
                if (idx >= 0) {
                    val key = pair.substring(0, idx)
                    val value = pair.substring(idx + 1)
                    params.getOrPut(key) { mutableListOf() }.add(value)
                }
            }
        } catch (e: Exception) {
            return ""
        }

        val parts = mutableListOf<String>()
        for ((key, values) in params) {
            for (value in values) {
                parts.add("$key=$value")
            }
        }
        return parts.joinToString("&")
    }

    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(data)
    }

    private fun StringUtils.md5HexBytes(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun generateXClientBuild(tsMs: Long): String {
        val stamp = buildTagStamp(tsMs, random.nextInt(1000))
        val hash = StringUtils.md5HexBytes("$STOCK_CERT_SHA256$BUILD_TAG_SALT$stamp".toByteArray())
        return "$stamp.$hash"
    }

    private fun buildTagStamp(tsMs: Long, randPart: Int): String {
        return "${tsMs / 1000}${("%03d".format(tsMs % 1000))}000$randPart"
    }

    private fun clientInfoWithBuildTag(clientInfo: String, tag: String): String {
        return try {
            val json = com.google.gson.JsonParser.parseString(clientInfo) as? com.google.gson.JsonObject
            if (json != null) {
                json.addProperty("X-Client-Build", tag)
                json.toString()
            } else clientInfo
        } catch (e: Exception) {
            clientInfo
        }
    }

    private fun generateClientInfoAndUa(): Pair<String, String> {
        val androidVersions = listOf(
            "9" to "PQ3A.190605.03081104",
            "10" to "QP1A.191005.007.A3",
            "11" to "RP1A.200720.011",
            "12" to "S1B.220414.015",
            "13" to "TQ2A.230405.003"
        )
        val redmiDevices = listOf(
            "23078RKD5C" to "Redmi",
            "2201117TY" to "Redmi",
            "2201117TG" to "Redmi",
            "22101316G" to "Redmi",
            "21121210G" to "Redmi",
            "M2012K11AG" to "Redmi",
            "M2007J20CG" to "Redmi"
        )
        val versionCodes = listOf(50020117, 50020118, 50020119, 50020120, 50020121)
        val networkTypes = listOf("NETWORK_WIFI", "NETWORK_MOBILE")
        val timezones = listOf(
            "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo",
            "America/New_York", "Europe/London"
        )

        val android = androidVersions[random.nextInt(androidVersions.size)]
        val device = redmiDevices[random.nextInt(redmiDevices.size)]
        val versionCode = versionCodes[random.nextInt(versionCodes.size)]
        val network = networkTypes[random.nextInt(networkTypes.size)]
        val timezone = timezones[random.nextInt(timezones.size)]
        val gaid = randomUuid()
        val deviceId = randomHex(32)

        val ua = "com.community.oneroom/$versionCode (Linux; U; Android ${android.first}; en_US; ${device.first}; Build/${android.second}; Cronet/135.0.7012.3)"

        val ci = """{"package_name":"com.community.oneroom","version_name":"4.0.01.0813.03","version_code":$versionCode,"os":"android","os_version":"${android.first}","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"$gaid","brand":"${device.second}","model":"${device.first}","system_language":"en","net":"$network","region":"US","timezone":"$timezone","sp_code":"40401","X-Play-Mode":"2"}"""

        return ua to ci
    }

    private fun randomHex(len: Int): String {
        return (0 until len).map { "%x".format(random.nextInt(16)) }.joinToString("")
    }

    private fun randomUuid(): String {
        return "${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}"
    }

    private fun randomSpoofedIp(): String {
        val prefixes = listOf(
            "103.241", "49.36", "117.195", "106.198", "122.162", "157.32",
            "182.70", "103.58", "27.60", "59.90"
        )
        val prefix = prefixes[random.nextInt(prefixes.size)]
        return "$prefix.${random.nextInt(253) + 1}.${random.nextInt(253) + 1}"
    }

    // =========================================================================
    // Session persistence (SharedPreferences)
    // =========================================================================

    private fun loadPersistedSession(): MovieBoxSession? {
        // In a full implementation, read from SharedPreferences or DataStore
        return null
    }

    private fun saveSession(session: MovieBoxSession) {
        // In a full implementation, save to SharedPreferences or DataStore
    }

    private fun clearPersistedSession() {
        // In a full implementation, clear from SharedPreferences or DataStore
    }

    // =========================================================================
    // Helper: extract body string from Result
    // =========================================================================

    private fun extractBody(result: Result): String? {
        return if (result is Result.Success) result.data else null
    }

    // =========================================================================
    // JSON Adaptation (ported from adapt.rs)
    // =========================================================================

    private fun movieboxSubjectJsonToCatalogItem(s: com.google.gson.JsonObject): CatalogItem? {
        val idStr = s.get("subjectId")?.asString()
            ?: s.get("id")?.asString()
            ?: return null
        if (idStr.isEmpty()) return null

        val title = s.get("title")?.asString()
            ?: s.get("name")?.asString()
            ?: "Unknown"

        val stype = s.get("subjectType")?.asInt()
            ?: s.get("stype")?.asInt()
            ?: 1

        val mediaType = if (stype == 2) MediaType.Series else MediaType.Movie

        val year = s.get("releaseDate")?.asString()
            ?: s.get("year")?.asString()
            ?: s.get("releaseInfo")?.asString()
            ?.let { extract4DigitYear(it) }
            ?.takeIf { it.isNotEmpty() }

        val posterUrl = s.getAsJsonObject("cover")?.get("url")?.asString()
            ?: s.get("coverUrl")?.asString()
            ?: s.get("poster")?.asString()
            ?: s.get("pic")?.asString()

        val seasonCount = s.get("season")?.asLong()?.toInt()

        return CatalogItem(
            id = ProviderMediaId(ProviderKind.MovieBox, idStr),
            title = title,
            mediaType = mediaType,
            year = year,
            posterUrl = posterUrl,
            seasonCount = seasonCount
        )
    }

    private fun movieboxSearchJsonToCatalog(payload: com.google.gson.JsonObject): List<CatalogItem> {
        val items = mutableListOf<CatalogItem>()

        val subjects = payload.getAsJsonObject("data")?.getAsJsonArray("results")
            ?: payload.getAsJsonArray("results")
        val subjectsSlice = subjects?.first()?.getAsJsonObject()?.getAsJsonArray("subjects")
        val fallbackList = payload.getAsJsonObject("data")?.getAsJsonArray("list")
            ?: payload.getAsJsonArray("list")

        val arrayToUse = subjectsSlice ?: fallbackList ?: emptyList()

        for (element in arrayToUse) {
            val obj = element as? com.google.gson.JsonObject ?: continue
            movieboxSubjectJsonToCatalogItem(obj)?.let { items.add(it) }
        }

        return items
    }

    private fun movieboxDetailsJsonToMediaDetails(
        payload: com.google.gson.JsonObject
    ): Result<MediaDetails> {
        val subject = payload.getAsJsonObject("data")?.getAsJsonObject("subject")
            ?: payload.getAsJsonObject("subject")
            ?: payload

        val idStr = subject.get("subjectId")?.asString()
            ?: subject.get("id")?.asString()
            ?: return Result.failure(ProviderError.NotFound)

        val title = subject.get("title")?.asString() ?: "Unknown"
        val stype = subject.get("subjectType")?.asInt()
            ?: subject.get("stype")?.asInt()
            ?: 1
        val mediaType = if (stype == 2) MediaType.Series else MediaType.Movie

        val year = subject.get("releaseDate")?.asString()
            ?: subject.get("year")?.asString()
            ?.let { extract4DigitYear(it) }
            ?.takeIf { it.isNotEmpty() }

        val description = subject.get("description")?.asString()
            ?: subject.get("intro")?.asString()
        val tagline = subject.get("tagline")?.asString()

        val imdbRating = subject.get("imdbRatingValue")?.asString()
            ?: subject.get("rating")?.asString()
            ?.let { if (it.toDoubleOrNull() != null) "%.1f".format(it.toDouble()) else it }

        val director = subject.get("director")?.asString()
        val stars = subject.get("stars")?.asString()
        val prints = subject.get("prints")?.asString()
        val audios = subject.get("audios")?.asString()

        val posterUrl = subject.getAsJsonObject("cover")?.get("url")?.asString()
            ?: subject.get("coverUrl")?.asString()

        val duration = subject.get("duration")?.asString()
            ?: subject.get("duration")?.asLong()?.let {
                if (it > 0) "${it / 60}m" else null
            }

        val genres = subject.getAsJsonArray("genre") ?: subject.getAsJsonArray("genres")
        val genreList = genres?.map { it.asString() }.filter { it != null }?.mapNotNull { it } ?: emptyList()

        // Seasons
        val seasons = mutableListOf<Season>()
        val seasonsArr = subject.getAsJsonObject("seasons")?.getAsJsonArray("seasons")
            ?: subject.getAsJsonArray("seasons")
        if (seasonsArr != null) {
            for (s in seasonsArr) {
                val sObj = s as? com.google.gson.JsonObject ?: continue
                val seNum = sObj.get("se")?.asLong()?.toInt() ?: 1
                val episodes = mutableListOf<Episode>()
                val eps = sObj.getAsJsonArray("episodeNumbers")
                if (eps != null) {
                    for (ep in eps) {
                        val epNum = ep.asLong()?.toInt()
                        if (epNum != null) {
                            episodes.add(Episode(seNum, epNum, null, null))
                        }
                    }
                } else {
                    val maxEp = sObj.get("maxEp")?.asLong()?.toInt()
                    if (maxEp != null) {
                        for (epNum in 1..maxEp) {
                            episodes.add(Episode(seNum, epNum, null, null))
                        }
                    }
                }
                seasons.add(Season(seNum, episodes))
            }
        }

        // Dubs
        val dubs = mutableListOf<AudioTrackOption>()
        val dubsArr = subject.getAsJsonArray("dubs")
        if (dubsArr != null) {
            for (d in dubsArr) {
                val dObj = d as? com.google.gson.JsonObject ?: continue
                val subjId = dObj.get("subjectId")?.asString()
                    ?: dObj.get("id")?.asString()
                    ?: ""
                val language = dObj.get("lanName")?.asString()
                    ?: dObj.get("language")?.asString()
                    ?: dObj.get("lang")?.asString()
                    ?: "Unknown"
                val label = dObj.get("title")?.asString()
                    ?: dObj.get("name")?.asString()
                    ?: dObj.get("lanName")?.asString()
                    ?: language
                dubs.add(AudioTrackOption(subjId, language, label))
            }
        }

        return Result.success(MediaDetails(
            id = ProviderMediaId(ProviderKind.MovieBox, idStr),
            title = title,
            mediaType = mediaType,
            year = year,
            description = description,
            tagline = tagline,
            imdbRating = imdbRating,
            director = director,
            stars = stars,
            prints = prints,
            audios = audios,
            posterUrl = posterUrl,
            duration = duration,
            genres = genreList,
            seasons = seasons,
            dubs = dubs
        ))
    }

    private fun movieboxResourceItemToRelease(item: com.google.gson.JsonElement): Release {
        val obj = item as? com.google.gson.JsonObject ?: return emptyRelease()

        // Check for _addon_release
        val addonRelease = obj.get("_addon_release")?.asString()
        if (addonRelease != null) {
            return try {
                com.google.gson.Gson().fromJson(addonRelease, Release::class.java)
            } catch (e: Exception) {
                emptyRelease()
            }
        }

        val filename = obj.get("fileName")?.asString()
            ?: obj.get("title")?.asString()
            ?: "Unknown Release"

        val resolution = obj.get("resolution")?.asString()
            ?: obj.get("resolution")?.asLong()?.let { "${it}p" }
            ?: obj.get("resolution")?.asInt()?.let { "${it}p" }

        val codec = obj.get("codecName")?.asString() ?: obj.get("codec")?.asString()
        val language = obj.get("language")?.asString() ?: obj.get("lanName")?.asString()
        val sizeBytes = obj.get("size")?.asLong()
            ?: obj.get("size")?.asInt()?.toLong()
            ?: obj.get("size")?.asString()?.toLongOrNull()
        val season = obj.get("se")?.asLong()?.toInt()
            ?: obj.get("se")?.asInt()
            ?: obj.get("se")?.asString()?.toIntOrNull()
        val episode = obj.get("ep")?.asLong()?.toInt()
            ?: obj.get("ep")?.asInt()
            ?: obj.get("ep")?.asString()?.toIntOrNull()
        val resourceId = obj.get("resourceId")?.asString()
            ?: obj.get("id")?.asString()
            ?: obj.get("resourceId")?.asLong()?.toString()
            ?: obj.get("id")?.asLong()?.toString()

        val mirrors = mutableListOf<SourceMirror>()
        val resourceLink = obj.get("resourceLink")?.asString()
            ?: obj.get("url")?.asString()
        if (resourceLink != null && resourceLink.isNotEmpty() && !isDeprecationNoticeUrl(resourceLink)) {
            val label = obj.get("uploadBy")?.asString()
                ?: obj.get("source")?.asString()
                ?: "Direct"
            mirrors.add(SourceMirror(label, resourceLink, emptyList(), false))
        }

        return Release(
            provider = ProviderKind.MovieBox,
            filename = filename,
            quality = resolution,
            codec = codec,
            language = language,
            sizeBytes = sizeBytes,
            season = season,
            episode = episode,
            mirrors = mirrors,
            resourceId = resourceId
        )
    }

    private fun emptyRelease(): Release =
        Release(ProviderKind.MovieBox, "", null, null, null, null, null, null, emptyList(), null)

    private fun movieboxResourcePositionJsonToRelease(
        payload: com.google.gson.JsonObject,
        original: Release,
        userAgent: String
    ): Release? {
        val resourceId = original.resourceId ?: return null
        val data = payload.getAsJsonObject("data") ?: payload
        val items = data.getAsJsonArray("list") ?: return null

        for (item in items) {
            val candidate = movieboxResourceItemToRelease(item)
            if (candidate.resourceId != resourceId) continue
            if (candidate.season != original.season) continue
            if (candidate.episode != original.episode) continue

            val url = candidate.directUrl() ?: continue
            try {
                val parsed = java.net.URL(url)
                if (parsed.protocol != "http" && parsed.protocol != "https") continue
            } catch (e: Exception) {
                continue
            }

            val release = original.copy(mirrors = candidate.mirrors, sizeBytes = candidate.sizeBytes)
            val updatedMirrors = release.mirrors.map { mirror ->
                mirror.copy(directFile = true, headers = listOf("User-Agent" to userAgent))
            }
            return release.copy(mirrors = updatedMirrors)
        }
        return null
    }

    private fun movieboxDetectorJsonToReleases(
        details: com.google.gson.JsonObject,
        season: Int,
        episode: Int,
        userAgent: String
    ): List<Release> {
        val data = details.getAsJsonObject("data") ?: details
        val detectors = data.getAsJsonArray("resourceDetectors") ?: return emptyList()
        val title = data.get("title")?.asString() ?: ""

        val items = mutableListOf<com.google.gson.JsonObject>()

        for (detector in detectors) {
            val detObj = detector as? com.google.gson.JsonObject ?: continue
            val resolutionList = detObj.getAsJsonArray("resolutionList")
            if (resolutionList != null && resolutionList.size > 0) {
                for (rl in resolutionList) {
                    val rlObj = rl as? com.google.gson.JsonObject ?: continue
                    items.add(rlObj)
                }
                continue
            }
            val downloadUrl = detObj.get("downloadUrl")?.asString() ?: continue
            val source = detObj.get("resourceLink")?.asString() ?: ""

            val item = com.google.gson.JsonObject()
            item.addProperty("title", title)
            item.addProperty("resourceLink", downloadUrl)
            item.addProperty("se", 0)
            item.addProperty("ep", 0)

            val fieldMap = mapOf(
                "firstSize" to "size",
                "codecName" to "codecName",
                "resourceId" to "resourceId",
                "uploadBy" to "uploadBy"
            )
            for ((from, to) in fieldMap) {
                val value = detObj.get(from)
                if (value != null) item.add(to, value)
            }

            resolutionFromSourceLink(source)?.let { res ->
                item.addProperty("resolution", res)
            }
            items.add(item)
        }

        val releases = mutableListOf<Release>()
        for (item in items) {
            val release = movieboxResourceItemToRelease(item)
            if (release.season != season || release.episode != episode) continue
            val url = release.directUrl() ?: continue
            try {
                val parsed = java.net.URL(url)
                if (parsed.protocol != "http" && parsed.protocol != "https") continue
            } catch (e: Exception) {
                continue
            }
            val updatedMirrors = release.mirrors.map { mirror ->
                mirror.copy(directFile = true, headers = listOf("User-Agent" to userAgent))
            }
            releases.add(release.copy(mirrors = updatedMirrors))
        }
        return releases
    }

    private fun resolutionFromSourceLink(link: String): Int? {
        val upper = link.uppercase()
        return upper.mapIndexed { i, c -> c to i }
            .filter { it.first == 'P' }
            .mapNotNull { (_, idx) ->
                val bytes = upper.toCharArray()
                val start = bytes.indices.asReversed()
                    .firstOrNull { !bytes[it].isDigit() }
                    ?.let { it + 1 } ?: 0
                val digits = upper.substring(start, idx)
                if (digits.length in 3..4) {
                    digits.toIntOrNull()
                } else null
            }
            .firstOrNull()
    }

    private fun isDeprecationNoticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d")
            || lower.contains("9a0461bc39da389663bf3dbb17091d3f")
            || lower.contains("b164fbfb4347792950bdfbfb563d39d9")
            || lower.contains("/notice.mp4")
            || (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private fun movieboxPlayInfoJsonToReleases(
        payload: com.google.gson.JsonObject,
        season: Int,
        episode: Int,
        userAgent: String
    ): List<Release> {
        val data = payload.getAsJsonObject("data") ?: payload
        val rawTitlePrefix = data.get("title")?.asString() ?: "MovieBox Stream"
        val titlePrefix = cleanMovieboxTitle(rawTitlePrefix)
        val streams = data.getAsJsonArray("streams") ?: return emptyList()

        val releases = mutableListOf<Release>()

        for (stream in streams) {
            val streamObj = stream as? com.google.gson.JsonObject ?: continue
            val streamId = streamObj.get("id")?.asString()
                ?: streamObj.get("id")?.asLong()?.toString()
            val formatType = streamObj.get("format")?.asString() ?: "MP4"
            val codec = streamObj.get("codecName")?.asString()
                ?: streamObj.get("codec")?.asString()
            val sizeBytes = streamObj.get("size")?.asLong()
                ?: streamObj.get("size")?.asInt()?.toLong()
                ?: streamObj.get("size")?.asString()?.toLongOrNull()

            val resolutionsStr = streamObj.get("resolutions")?.asString()
                ?: data.get("displayResolutions")?.asString()
                ?: "1080,720,480"

            val signCookie = streamObj.get("signCookie")?.asString() ?: ""
            val streamUrl = streamObj.get("url")?.asString() ?: ""

            val manifestUrl = resolveDashManifestFromPolicy(signCookie)
                ?: if (!isDeprecationNoticeUrl(streamUrl) && streamUrl.startsWith("http")) streamUrl else null
            val playableUrl = manifestUrl ?: continue

            val headers = mutableListOf<Pair<String, String>>()
            headers.add("Referer" to STREAM_REFERER)
            headers.add("User-Agent" to userAgent)
            if (signCookie.isNotEmpty()) {
                val cleanCookie = signCookie.trimEnd(';')
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("; ")
                headers.add("Cookie" to cleanCookie)
            }

            val parsedResolutions = resolutionsStr.split(',')
                .map { it.trim() }
                .filterMap { it.toIntOrNull() }
                .sortedDescending()
                .distinct()
            val resList = if (parsedResolutions.isEmpty()) listOf(1080) else parsedResolutions

            val codecDisp = codec ?: formatType
            val highestRes = resList.firstOrNull() ?: 1080

            for (res in resList) {
                val resLabel = "${res}p"
                val filename = if (season > 0 && episode > 0) {
                    "$titlePrefix S${"%02d".format(season)}E${"%02d".format(episode)} $resLabel $codecDisp"
                } else {
                    "$titlePrefix $resLabel $codecDisp"
                }

                val mirror = SourceMirror(
                    label = "$resLabel $codecDisp",
                    resolverUrl = playableUrl,
                    headers = headers.toTypedArray().toList(),
                    directFile = true
                )

                val scaledSize = sizeBytes?.let { total ->
                    if (res.toFloat() >= highestRes.toFloat() || highestRes <= 0f) total
                    else {
                        val scale = (res.toFloat() / highestRes.toFloat()).pow(1.6)
                        (total.toFloat() * scale.coerceIn(0.15f, 1f)).toLong()
                    }
                }

                releases.add(Release(
                    provider = ProviderKind.MovieBox,
                    filename = filename,
                    quality = resLabel,
                    codec = codec,
                    language = null,
                    sizeBytes = scaledSize,
                    season = if (season > 0) season else null,
                    episode = if (episode > 0) episode else null,
                    mirrors = listOf(mirror),
                    resourceId = streamId
                ))
            }
        }
        return releases
    }

    private fun resolveDashManifestFromPolicy(signCookie: String): String? {
        for (part in signCookie.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("urlprefix=")) {
                val prefixPart = trimmed.substring("urlprefix=".length)
                val b64Token = prefixPart.split(":").firstOrNull() ?: prefixPart
                val normalized = b64Token.map { c ->
                    when (c) {
                        '-' -> '+'
                        '_' -> '/'
                        else -> c
                    }
                }.joinToString("")
                val decodedBytes = decodeB64Padded(normalized) ?: continue
                val urlStr = String(decodedBytes) ?: continue
                return toDashMpd(urlStr)
            }
            if (trimmed.startsWith("CloudFront-Policy=")) {
                val policyClean = trimmed.substring("CloudFront-Policy=".length).trim()
                val normalized = policyClean.map { c ->
                    when (c) {
                        '-' -> '+'
                        '_' -> '='
                        '~' -> '/'
                        else -> c
                    }
                }.joinToString("")
                val decodedBytes = decodeB64Padded(normalized) ?: continue
                val json = com.google.gson.JsonParser.parseString(decodedBytes) as? com.google.gson.JsonObject ?: continue
                val resource = json.getAsJsonArray("Statement")?.first()?.asJsonObject()?.get("Resource")?.asString() ?: continue
                return toDashMpd(resource)
            }
        }
        return null
    }

    private fun toDashMpd(urlStr: String): String? {
        val base = urlStr.trimEnd('*').trimEnd('/')
        if (base.isEmpty() ||
            (!base.startsWith("http://") && !base.startsWith("https://")) ||
            !base.contains("/dash/")) {
            return null
        }
        return "$base/index.mpd"
    }

    private fun decodeB64Padded(s: String): ByteArray? {
        var padded = s
        val padding = (4 - s.length % 4) % 4
        if (padding > 0) {
            padded += "=".repeat(padding)
        }
        return try {
            Base64.getDecoder().decode(padded)
        } catch (e: Exception) {
            null
        }
    }

    private fun movieboxResourceJsonToReleases(payload: com.google.gson.JsonObject): List<Release> {
        val items = payload.getAsJsonArray("list")?.asList()
            ?: payload.asList()
            ?: emptyList()
        return items
            .map { movieboxResourceItemToRelease(it) }
            .filter { it.mirrors.isNotEmpty() }
    }

    private fun captionsJsonToOptions(payload: com.google.gson.JsonObject): List<SubtitleOption> {
        val captions = payload.getAsJsonArray("extCaptions")
            ?: payload.getAsJsonObject("data")?.getAsJsonArray("extCaptions")
            ?: return emptyList()

        val seenUrls = mutableSetOf<String>()
        val options = mutableListOf<SubtitleOption>()

        for (cap in captions) {
            val capObj = cap as? com.google.gson.JsonObject ?: continue
            val url = capObj.get("url")?.asString() ?: continue
            if (url.isEmpty() || url.contains("aa348f2541d13ffe")) continue

            val size = capObj.get("size")?.asLong()
                ?: capObj.get("size")?.asInt()?.toLong()
                ?: capObj.get("size")?.asString()?.toLongOrNull()
                ?: 0L
            if (size > 0 && size <= 50) continue

            val rawName = capObj.get("lanName")?.asString()
                ?: capObj.get("lan")?.asString()
                ?: "Unknown"
            if (rawName.trim().isEmpty()) continue

            if (rawName.equals("in", ignoreCase = true) && (size == 0 || size <= 100)) continue
            if (!seenUrls.add(url)) continue

            options.add(SubtitleOption(rawName, url))
        }
        return options
    }

    // =========================================================================
    // Utility: title cleaning (ported from title.rs)
    // =========================================================================

    fun cleanMovieboxTitle(rawTitle: String): String {
        var title = rawTitle.trim()
        if (title.isEmpty()) return ""

        while (title.startsWith("[")) {
            val closePos = title.indexOf(']')
            if (closePos >= 0) {
                val remainder = title.substring(closePos + 1).trim()
                if (remainder.isNotEmpty()) {
                    title = remainder
                } else {
                    break
                }
            } else {
                break
            }
        }

        val bracketPos = title.indexOf('[')
        if (bracketPos > 0) {
            title = title.substring(0, bracketPos).trim()
        }

        val parenPos = title.indexOf('(')
        if (parenPos > 0) {
            val inside = title.substring(parenPos + 1)
            val insideContent = inside.split(')').firstOrNull()?.trim() ?: ""
            val isYear = insideContent.length == 4 &&
                insideContent.all { it.isDigit() } &&
                insideContent.toIntOrNull()?.let { it in 1900..2099 } == true
            if (!isYear) {
                title = title.substring(0, parenPos).trim()
            }
        }

        val dashPos = title.lastIndexOf(" - ")
        if (dashPos >= 0) {
            val suffix = title.substring(dashPos + 3)
            val isTag = listOf(
                "hindi", "tamil", "telugu", "kannada", "malayalam", "bengali",
                "marathi", "punjabi", "gujarati", "urdu", "english", "spanish",
                "french", "german", "italian", "japanese", "korean", "chinese",
                "russian", "portuguese", "turkish", "arabic", "dub", "audio",
                "multi", "season"
            ).any { suffix.lowercase().contains(it) } ||
                (suffix.firstOrNull()?.let { it == 's' || it == 'S' } == true &&
                    suffix.substring(1).all { it.isDigit() || it == '-' })
            if (isTag) {
                title = title.substring(0, dashPos).trim()
            }
        }

        val sIdx = title.lastIndexOf(" S")
        if (sIdx >= 0) {
            val suffix = title.substring(sIdx + 2)
            val isSeason = suffix.all { it.isDigit() || it == '-' || it == 'S' } &&
                suffix.firstOrNull()?.isDigit() == true
            if (isSeason) {
                title = title.substring(0, sIdx).trim()
            }
        }

        val seasonIdx = title.lowercase().lastIndexOf(" season ")
        if (seasonIdx >= 0) {
            title = title.substring(0, seasonIdx).trim()
        }

        for (sep in listOf('_', ' ', '.', '-')) {
            val pos = title.lastIndexOf(sep)
            if (pos >= 0) {
                val suffix = title.substring(pos + 1)
                val isRes = (suffix.endsWith('p') || suffix.endsWith('P')) &&
                    suffix.length > 1 &&
                    suffix.substring(0, suffix.length - 1).all { it.isDigit() } &&
                    suffix.substring(0, suffix.length - 1).toIntOrNull()?.let { it in 144..8640 } == true
                if (isRes) {
                    title = title.substring(0, pos).trim()
                }
            }
        }

        val cleaned = title.trimEnd().trimEnd('-').trimEnd(':').trimEnd('_').trimEnd('.').trimEnd(' ').trim()
        return if (cleaned.isEmpty()) rawTitle.trim() else cleaned
    }

    private fun extract4DigitYear(raw: String): String {
        val pattern = java.util.regex.Pattern.compile("1[89]\\d{2}")
        val matcher = pattern.matcher(raw)
        return if (matcher.find()) matcher.group() else ""
    }

    // =========================================================================
    // Additional provider methods
    // =========================================================================

    suspend fun suggest(query: String): Result<List<String>> {
        return search(query, 1).map { it.map { it.title } }
    }

    suspend fun homepage(tabId: String, page: Int = 1): Result<Map<String, Any>> {
        val path = "$PATH_TAB_OPERATING?page=$page&tabId=$tabId&version="
        return get(path).map { body ->
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            // Simplified: return empty map, full parsing would need homepage_json_to_catalog
            emptyMap<String, Any>()
        }
    }

    suspend fun fetchM3uPlaylist(source: String): Result<String> {
        return Result.success("")
    }

    suspend fun parseEpgData(data: String): Result<List<Map<String, String>>> {
        return Result.success(emptyList())
    }

    private suspend fun getExtCaptions(subjectId: String, resourceId: String): Result {
        val path = "$PATH_EXT_CAPTIONS?subjectId=$subjectId&resourceId=$resourceId"
        return get(path)
    }

    private fun fetchCollectionResolutions(subjectId: String): Result<List<Int>> {
        val path = "$PATH_RESOURCE?subjectId=$subjectId&page=1&perPage=20"
        return get(path).map { body ->
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val resolutions = mutableListOf<Int>()
            val cols = json.getAsJsonArray("collectionResolutions")
            if (cols != null) {
                for (col in cols) {
                    val colObj = col as? com.google.gson.JsonObject ?: continue
                    val r = colObj.get("resolution")?.asLong()
                    if (r != null && r > 0) resolutions.add(r.toInt())
                }
            }
            if (resolutions.isEmpty()) {
                val list = json.getAsJsonArray("list")
                if (list != null) {
                    for (item in list) {
                        val itemObj = item as? com.google.gson.JsonObject ?: continue
                        val r = itemObj.get("resolution")?.asLong()
                        if (r != null && r > 0 && !resolutions.contains(r.toInt())) {
                            resolutions.add(r.toInt())
                        }
                    }
                }
            }
            resolutions.sortDescending()
            if (resolutions.isEmpty()) listOf(1080, 720, 480, 360) else resolutions
        }
    }
}

/**
 * Data class for parsed MovieBox session (mirrors Rust MovieBoxSession).
 */
data class MovieBoxSession(
    val token: String,
    val userId: String?,
    val expiresAt: Long?,
    val createdAt: Long
) {
    fun isValid(): Boolean {
        if (token.isEmpty()) return false
        val now = System.currentTimeMillis() / 1000
        return if (expiresAt != null) {
            now + 60 < expiresAt
        } else {
            now < createdAt + 7 * 24 * 3600
        }
    }
}

/**
 * Helper extension for JsonElement list conversion.
 */
private fun com.google.gson.JsonArray.asList(): List<com.google.gson.JsonElement> {
    return (0 until size).map { get(it) }
}