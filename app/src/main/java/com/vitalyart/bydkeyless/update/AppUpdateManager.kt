package com.vitalyart.bydkeyless.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.vitalyart.bydkeyless.BuildConfig
import com.vitalyart.bydkeyless.storage.SecureSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {
    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            if (left == right) continue
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            return when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        if (prerelease.isNotEmpty()) append('-').append(prerelease.joinToString("."))
    }

    companion object {
        private val pattern = Regex("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")

        fun parse(value: String): SemanticVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            return runCatching {
                SemanticVersion(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                    match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList(),
                )
            }.getOrNull()
        }
    }
}

data class AppRelease(
    val version: SemanticVersion,
    val tag: String,
    val name: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
    val sha256: String,
    val prerelease: Boolean,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version.toString())
        put("tag", tag)
        put("name", name)
        put("pageUrl", pageUrl)
        put("apkName", apkName)
        put("apkUrl", apkUrl)
        put("checksumUrl", checksumUrl)
        put("sha256", sha256)
        put("prerelease", prerelease)
    }.toString()

    companion object {
        fun fromJson(value: String?): AppRelease? = runCatching {
            val json = JSONObject(value ?: return null)
            AppRelease(
                version = SemanticVersion.parse(json.getString("version")) ?: return null,
                tag = json.getString("tag"),
                name = json.getString("name"),
                pageUrl = json.getString("pageUrl"),
                apkName = json.getString("apkName"),
                apkUrl = json.getString("apkUrl"),
                checksumUrl = json.getString("checksumUrl"),
                sha256 = json.getString("sha256"),
                prerelease = json.getBoolean("prerelease"),
            )
        }.getOrNull()
    }
}

sealed interface ReleaseFetchResult {
    data class Found(val release: AppRelease?, val etag: String?, val rawJson: String) : ReleaseFetchResult
    data object NotModified : ReleaseFetchResult
}

class GitHubReleaseRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val releasesUrl: String = RELEASES_URL,
    private val acceptedDownloadPrefix: String = RELEASE_DOWNLOAD_PREFIX,
) {
    suspend fun fetchLatest(
        currentVersion: SemanticVersion,
        includePrereleases: Boolean,
        etag: String? = null,
    ): ReleaseFetchResult {
        val request = Request.Builder().url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "BYD-Keyless/${BuildConfig.VERSION_NAME}")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (it.code == 304) return ReleaseFetchResult.NotModified
            check(it.isSuccessful) { "GitHub returned HTTP ${it.code}" }
            val raw = it.body?.string().orEmpty()
            val selected = try {
                selectRelease(JSONArray(raw), currentVersion, includePrereleases, acceptedDownloadPrefix)
            } catch (failure: Exception) {
                throw InvalidReleaseException(failure)
            }
            val release = selected?.let { candidate ->
                try { candidate.withChecksum(loadChecksum(candidate)) }
                catch (failure: Exception) { throw InvalidReleaseException(failure) }
            }
            return ReleaseFetchResult.Found(release, it.header("ETag"), raw)
        }
    }

    private suspend fun loadChecksum(release: AppRelease): String {
        val request = Request.Builder().url(release.checksumUrl)
            .header("User-Agent", "BYD-Keyless/${BuildConfig.VERSION_NAME}").build()
        return client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "Checksum returned HTTP ${response.code}" }
            parseChecksum(response.body?.string().orEmpty(), release.apkName)
                ?: error("Release checksum is missing")
        }
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/VitalyArt/byd-keyless/releases?per_page=100"

        fun selectRelease(
            array: JSONArray,
            current: SemanticVersion,
            includePrereleases: Boolean,
            acceptedDownloadPrefix: String = RELEASE_DOWNLOAD_PREFIX,
        ): AppRelease? =
            (0 until array.length()).mapNotNull { index ->
                val release = array.optJSONObject(index) ?: return@mapNotNull null
                if (release.optBoolean("draft")) return@mapNotNull null
                val tag = release.optString("tag_name")
                val version = SemanticVersion.parse(tag) ?: return@mapNotNull null
                if (!includePrereleases && (release.optBoolean("prerelease") || version.isPrerelease)) return@mapNotNull null
                if (version <= current) return@mapNotNull null
                val expectedName = "BYDKeyless-v${version}-arm64-v8a.apk"
                val assets = release.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).mapNotNull(assets::optJSONObject).firstOrNull {
                    it.optString("name") == expectedName && it.optString("browser_download_url").startsWith(acceptedDownloadPrefix)
                } ?: return@mapNotNull null
                val sums = (0 until assets.length()).mapNotNull(assets::optJSONObject).firstOrNull {
                    it.optString("name") == "SHA256SUMS.txt" && it.optString("browser_download_url").startsWith(acceptedDownloadPrefix)
                } ?: return@mapNotNull null
                AppRelease(
                    version, tag, release.optString("name", tag), release.optString("html_url"),
                    expectedName, apk.getString("browser_download_url"), sums.getString("browser_download_url"),
                    sha256 = "", prerelease = release.optBoolean("prerelease"),
                )
            }.maxByOrNull(AppRelease::version)

        fun parseChecksum(contents: String, apkName: String): String? = contents.lineSequence().mapNotNull { line ->
            val match = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").matchEntire(line.trim()) ?: return@mapNotNull null
            if (match.groupValues[2] == apkName) match.groupValues[1].lowercase(Locale.US) else null
        }.firstOrNull()

        const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/VitalyArt/byd-keyless/releases/download/"
    }
}

class InvalidReleaseException(cause: Throwable) : IOException("GitHub release is invalid", cause)

private fun AppRelease.withChecksum(value: String) = copy(sha256 = value)

enum class UpdateError { NETWORK, RELEASE_INVALID, DOWNLOAD_FAILED, CHECKSUM_MISMATCH, PACKAGE_INVALID, SIGNATURE_MISMATCH }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: AppRelease, val prompt: Boolean) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Int?) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val file: File, val prompt: Boolean = true) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Error(val error: UpdateError, val release: AppRelease? = null, val showDialog: Boolean = false) : UpdateState
}

class AppUpdateManager(
    private val context: Context,
    private val store: SecureSessionStore,
    private val scope: CoroutineScope,
    private val repository: GitHubReleaseRepository = GitHubReleaseRepository(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    val includePrereleases: Boolean get() = store.updatePrereleases
    val readyFile: File? get() = (_state.value as? UpdateState.ReadyToInstall)?.file

    init {
        removeInstalledOrStaleDownload()
        restoreDownload()
    }

    fun check(manual: Boolean) {
        if (checkJob?.isActive == true) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.ReadyToInstall || _state.value is UpdateState.Installing) return
        if (!shouldCheck(store.updateLastCheckAt, now(), manual)) return
        checkJob = scope.launch {
            _state.value = UpdateState.Checking
            try {
                val current = SemanticVersion.parse(BuildConfig.VERSION_NAME) ?: SemanticVersion(0, 0, 0)
                when (val result = repository.fetchLatest(current, store.updatePrereleases, store.updateEtag)) {
                    ReleaseFetchResult.NotModified -> {
                        store.updateLastCheckAt = now()
                        val cached = AppRelease.fromJson(store.updateCachedRelease)
                        _state.value = cached?.takeIf { it.version > current }
                            ?.let { UpdateState.Available(it, prompt = true) } ?: UpdateState.Idle
                    }
                    is ReleaseFetchResult.Found -> {
                        store.updateLastCheckAt = now()
                        store.updateEtag = result.etag
                        store.updateCachedRelease = result.release?.toJson()
                        _state.value = result.release?.let { UpdateState.Available(it, prompt = true) } ?: UpdateState.Idle
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InvalidReleaseException) {
                _state.value = if (manual) UpdateState.Error(UpdateError.RELEASE_INVALID) else UpdateState.Idle
            } catch (_: Exception) {
                _state.value = if (manual) UpdateState.Error(UpdateError.NETWORK) else UpdateState.Idle
            }
        }
    }

    fun setIncludePrereleases(value: Boolean) {
        if (store.updatePrereleases == value) return
        store.updatePrereleases = value
        store.updateLastCheckAt = 0L
        store.updateEtag = null
        store.updateCachedRelease = null
        check(manual = true)
    }

    fun dismissPrompt() {
        store.updateLastPromptAt = now()
        _state.value = when (val value = _state.value) {
            is UpdateState.Available -> value.copy(prompt = false)
            is UpdateState.ReadyToInstall -> value.copy(prompt = false)
            else -> value
        }
    }

    fun download() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        val directory = updateDirectory() ?: run {
            _state.value = UpdateState.Error(UpdateError.DOWNLOAD_FAILED, release, showDialog = true)
            return
        }
        directory.mkdirs()
        directory.listFiles()?.filter { it.name != release.apkName }?.forEach(File::delete)
        val file = File(directory, release.apkName)
        if (file.exists()) file.delete()
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(release.name)
            .setDescription(release.apkName)
            .setMimeType(APK_MIME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        runCatching { downloadManager.enqueue(request) }.onSuccess { id ->
            store.updateDownloadId = id
            store.updateDownloadRelease = release.toJson()
            _state.value = UpdateState.Downloading(release, 0)
            monitorDownload(id, release, file)
        }.onFailure {
            _state.value = UpdateState.Error(UpdateError.DOWNLOAD_FAILED, release, showDialog = true)
        }
    }

    fun markInstalling() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        _state.value = UpdateState.Installing(ready.release)
    }

    fun installationCancelled() {
        val installing = _state.value as? UpdateState.Installing ?: return
        val file = downloadedFile(installing.release)
        store.updateLastPromptAt = now()
        _state.value = if (file?.isFile == true) UpdateState.ReadyToInstall(installing.release, file, prompt = false)
        else UpdateState.Error(UpdateError.DOWNLOAD_FAILED, installing.release, showDialog = true)
    }

    fun clearError() { if (_state.value is UpdateState.Error) _state.value = UpdateState.Idle }

    private fun restoreDownload() {
        val id = store.updateDownloadId
        val release = AppRelease.fromJson(store.updateDownloadRelease)
        val file = release?.let(::downloadedFile)
        if (id > 0L && release != null && file != null) monitorDownload(id, release, file)
    }

    private fun monitorDownload(id: Long, release: AppRelease, file: File) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            while (true) {
                val snapshot = queryDownload(id)
                when (snapshot.status) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_RUNNING -> {
                        _state.value = UpdateState.Downloading(release, snapshot.progress)
                        delay(750L)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        validateDownload(release, file)
                        return@launch
                    }
                    else -> {
                        clearPersistedDownload()
                        file.delete()
                        _state.value = UpdateState.Error(UpdateError.DOWNLOAD_FAILED, release, showDialog = true)
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun validateDownload(release: AppRelease, file: File) {
        val error = withContext(Dispatchers.IO) {
            when {
                !file.isFile || sha256(file) != release.sha256 -> UpdateError.CHECKSUM_MISMATCH
                else -> validatePackage(file, release)
            }
        }
        if (error == null) _state.value = UpdateState.ReadyToInstall(release, file, prompt = shouldPrompt())
        else {
            file.delete()
            clearPersistedDownload()
            _state.value = UpdateState.Error(error, release, showDialog = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(file: File, release: AppRelease): UpdateError? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return UpdateError.PACKAGE_INVALID
        if (archive.packageName != context.packageName || SemanticVersion.parse(archive.versionName.orEmpty()) != release.version) {
            return UpdateError.PACKAGE_INVALID
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val installedSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val archiveDigests = archiveSignatures.orEmpty().map { digest(it.toByteArray()) }.toSet()
        val installedDigests = installedSignatures.orEmpty().map { digest(it.toByteArray()) }.toSet()
        return if (archiveDigests.isEmpty() || archiveDigests.intersect(installedDigests).isEmpty()) UpdateError.SIGNATURE_MISMATCH else null
    }

    private fun queryDownload(id: Long): DownloadSnapshot = downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) return@use DownloadSnapshot(DownloadManager.STATUS_FAILED, null)
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        DownloadSnapshot(status, if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null)
    } ?: DownloadSnapshot(DownloadManager.STATUS_FAILED, null)

    private fun removeInstalledOrStaleDownload() {
        val current = SemanticVersion.parse(BuildConfig.VERSION_NAME) ?: return
        val release = AppRelease.fromJson(store.updateDownloadRelease) ?: return
        if (release.version <= current) {
            downloadedFile(release)?.delete()
            clearPersistedDownload()
            store.updateCachedRelease = null
        }
    }

    private fun downloadedFile(release: AppRelease): File? = updateDirectory()?.let { File(it, release.apkName) }
    private fun updateDirectory(): File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve("updates")
    private fun clearPersistedDownload() { store.updateDownloadId = -1L; store.updateDownloadRelease = null }
    private fun shouldPrompt(): Boolean = store.updateLastPromptAt <= 0L || now() < store.updateLastPromptAt ||
        now() - store.updateLastPromptAt >= CHECK_INTERVAL_MILLIS

    private data class DownloadSnapshot(val status: Int, val progress: Int?)

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L

        fun shouldCheck(lastCheckAt: Long, now: Long, manual: Boolean): Boolean =
            manual || lastCheckAt <= 0L || now < lastCheckAt || now - lastCheckAt >= CHECK_INTERVAL_MILLIS
    }
}

private fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest(digest.digest())
}

private fun digest(bytes: ByteArray): String = bytes.joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}
