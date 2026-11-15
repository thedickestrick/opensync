package com.opensync.foldersync.update

import android.os.SystemClock
import com.opensync.foldersync.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * The app's one update state, shared by Settings → App updates and the check that runs each time
 * the app comes to the foreground. Lives outside any screen so a download keeps going when the
 * user leaves Settings.
 */
object AppUpdater {

    data class State(
        val checking: Boolean = false,
        val downloading: Boolean = false,
        val progress: Int = 0,
        val status: String? = null,
        val available: UpdateChecker.Release? = null,
        /** Why the last download failed; cleared when another starts. */
        val downloadError: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** A newer release the open-time check found, waiting for the user to install it or say later. */
    private val _prompt = MutableStateFlow<UpdateChecker.Release?>(null)
    val prompt = _prompt.asStateFlow()

    /** Releases the user said "Later" to; asked about again on the next launch. */
    private val dismissed = mutableSetOf<String>()
    private var lastOpenCheck = 0L

    val currentVersion: String by lazy { UpdateChecker.currentVersion(Graph.appContext) }

    /**
     * Called whenever the app comes to the foreground. Says nothing unless a newer release is out.
     * Every launch checks; coming back from the background checks again only after a few minutes,
     * so hopping between apps doesn't use up GitHub's rate limit.
     */
    fun checkOnOpen() {
        val now = SystemClock.elapsedRealtime()
        if (lastOpenCheck != 0L && now - lastOpenCheck < OPEN_CHECK_INTERVAL_MS) return
        val s = _state.value
        if (s.checking || s.downloading) return
        val prefs = AppPrefs(Graph.appContext)
        val owner = prefs.updateOwner.trim()
        val repo = prefs.updateRepo.trim()
        if (owner.isBlank() || repo.isBlank()) return
        lastOpenCheck = now
        scope.launch {
            val release = runCatching { UpdateChecker.latestRelease(owner, repo) }.getOrNull() ?: return@launch
            if (release.apkUrl == null || !UpdateChecker.isNewer(release.tag, currentVersion)) return@launch
            _state.update {
                if (it.checking || it.downloading) it
                else it.copy(available = release, status = "Update available: ${release.tag}", downloadError = null)
            }
            if (release.tag !in dismissed) _prompt.value = release
        }
    }

    /** The "Check for updates" button: always runs and reports what it found. */
    fun check(owner: String, repo: String) {
        if (owner.isBlank() || repo.isBlank()) {
            _state.update { it.copy(status = "Enter a GitHub owner and repository first") }
            return
        }
        if (_state.value.checking || _state.value.downloading) return
        scope.launch {
            _state.update { it.copy(checking = true, status = null, available = null) }
            try {
                val release = UpdateChecker.latestRelease(owner, repo)
                if (release == null) {
                    _state.update {
                        it.copy(checking = false, available = null,
                            status = "No releases published in $owner/$repo yet")
                    }
                    return@launch
                }
                val newer = UpdateChecker.isNewer(release.tag, currentVersion)
                _state.update {
                    it.copy(
                        checking = false,
                        available = if (newer && release.apkUrl != null) release else null,
                        status = when {
                            release.apkUrl == null -> "Latest release (${release.tag}) has no APK asset"
                            newer -> "Update available: ${release.tag}"
                            else -> "You're up to date ($currentVersion)"
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, status = "Check failed: ${e.message}") }
            }
        }
    }

    /** The update source changed, so what was found for the old one no longer applies. */
    fun sourceChanged() {
        _prompt.value = null
        _state.update { if (it.checking || it.downloading) it else it.copy(status = null, available = null) }
    }

    fun dismissPrompt() {
        _prompt.value?.let { dismissed += it.tag }
        _prompt.value = null
    }

    /** Downloads [release] (by default the one Settings found) and hands it to Android's installer. */
    fun downloadAndInstall(release: UpdateChecker.Release? = _state.value.available) {
        release ?: return
        val url = release.apkUrl ?: return
        if (_state.value.downloading) return
        scope.launch {
            _state.update {
                it.copy(available = release, downloading = true, progress = 0,
                    status = "Downloading…", downloadError = null)
            }
            try {
                val apk = File(File(Graph.appContext.cacheDir, "update"), "opensync-${release.tag}.apk")
                UpdateChecker.download(url, apk) { p -> _state.update { it.copy(progress = p) } }
                _state.update { it.copy(downloading = false, status = "Starting installer…") }
                _prompt.value = null
                UpdateChecker.installApk(Graph.appContext, apk)
            } catch (e: Exception) {
                _state.update {
                    it.copy(downloading = false, status = "Download failed: ${e.message}",
                        downloadError = "Download failed: ${e.message}")
                }
            }
        }
    }

    private const val OPEN_CHECK_INTERVAL_MS = 10 * 60 * 1000L
}
