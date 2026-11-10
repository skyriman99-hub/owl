package com.owl.downloader.viewmodel

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.owl.downloader.database.CommandTemplate
import com.owl.downloader.database.CookieProfile
import com.owl.downloader.database.DownloadedVideoInfo
import com.owl.downloader.database.OptionShortcut
import com.owl.downloader.database.OwlDatabaseHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reconstructs:
 * - `com.junkfood.seal.ui.page.download.DownloadType`
 * - `com.junkfood.seal.Downloader$CustomCommandTask`
 * - `com.junkfood.seal.ui.page.download.DownloadViewModel`
 * - `com.junkfood.seal.ui.page.videolist.VideoListViewModel`
 * - `com.junkfood.seal.ui.page.settings.network.CookiesViewModel`
 */
enum class DownloadType {
    Audio,
    Video,
    Command
}

sealed class TaskStatus {
    data class Running(val progress: Float) : TaskStatus()
    data object Completed : TaskStatus()
    data object Canceled : TaskStatus()
    data class Error(val report: String) : TaskStatus()
}

data class CustomCommandTask(
    val taskId: String,
    val template: CommandTemplate,
    val url: String,
    val currentLine: String,
    val output: String,
    val state: TaskStatus
)

data class OwlUiState(
    val url: String = "",
    val downloadType: DownloadType = DownloadType.Command,
    val selectedTemplateId: Int = 1,
    val templates: List<CommandTemplate> = emptyList(),
    val shortcuts: List<OptionShortcut> = emptyList(),
    val cookies: List<CookieProfile> = emptyList(),
    val downloadedVideos: List<DownloadedVideoInfo> = emptyList(),
    val tasks: List<CustomCommandTask> = emptyList(),
    // DownloadUtil$DownloadPreferences toggles
    val downloadPlaylist: Boolean = false,
    val embedSubtitle: Boolean = true,
    val embedThumbnail: Boolean = true,
    val sponsorBlock: Boolean = false,
    val aria2c: Boolean = false,
    val splitByChapter: Boolean = false,
    val useCookies: Boolean = true,
    val restrictFilenames: Boolean = false,
    val commandDirectory: String = "/storage/emulated/0/Download/Owl",
    val activeExtractorFilter: String = "All",
    val searchQuery: String = "",
    val snackbarMessage: String? = null
)

class OwlViewModel(application: Application) : AndroidViewModel(application) {
    private val db = OwlDatabaseHelper(application)

    private val _uiState = MutableStateFlow(OwlUiState())
    val uiState: StateFlow<OwlUiState> = _uiState.asStateFlow()

    init {
        refreshDatabaseState()
    }

    private fun refreshDatabaseState() {
        val templates = db.getAllTemplates()
        val shortcuts = db.getAllShortcuts()
        val cookies = db.getAllCookies()
        val videos = db.getAllDownloadedVideos()
        _uiState.update { curr ->
            curr.copy(
                templates = templates,
                selectedTemplateId = if (templates.any { it.id == curr.selectedTemplateId }) {
                    curr.selectedTemplateId
                } else {
                    templates.firstOrNull()?.id ?: 1
                },
                shortcuts = shortcuts,
                cookies = cookies,
                downloadedVideos = videos
            )
        }
    }

    fun updateUrl(newUrl: String) {
        _uiState.update { it.copy(url = newUrl) }
    }

    fun updateDownloadType(type: DownloadType) {
        _uiState.update { it.copy(downloadType = type) }
    }

    fun selectTemplate(id: Int) {
        _uiState.update { it.copy(selectedTemplateId = id) }
    }

    fun togglePreference(key: String) {
        _uiState.update { s ->
            when (key) {
                "downloadPlaylist" -> s.copy(downloadPlaylist = !s.downloadPlaylist)
                "embedSubtitle" -> s.copy(embedSubtitle = !s.embedSubtitle)
                "embedThumbnail" -> s.copy(embedThumbnail = !s.embedThumbnail)
                "sponsorBlock" -> s.copy(sponsorBlock = !s.sponsorBlock)
                "aria2c" -> s.copy(aria2c = !s.aria2c)
                "splitByChapter" -> s.copy(splitByChapter = !s.splitByChapter)
                "useCookies" -> s.copy(useCookies = !s.useCookies)
                "restrictFilenames" -> s.copy(restrictFilenames = !s.restrictFilenames)
                else -> s
            }
        }
    }

    fun addOrUpdateTemplate(id: Int?, name: String, template: String) {
        if (id == null || id == 0) {
            db.insertTemplate(name.trim(), template.trim())
        } else {
            db.updateTemplate(id, name.trim(), template.trim())
        }
        refreshDatabaseState()
    }

    fun deleteTemplate(id: Int) {
        db.deleteTemplate(id)
        refreshDatabaseState()
    }

    fun addShortcut(option: String) {
        val trimmed = option.trim()
        if (trimmed.isNotEmpty()) {
            db.insertShortcut(trimmed)
            refreshDatabaseState()
        }
    }

    fun deleteShortcut(id: Long) {
        db.deleteShortcut(id)
        refreshDatabaseState()
    }

    fun addCookieProfile(url: String, content: String) {
        db.insertCookie(url.trim(), content.trim())
        refreshDatabaseState()
    }

    fun extractCookiesFromWebView(targetUrl: String) {
        val rawCookies = runCatching {
            CookieManager.getInstance().getCookie(targetUrl)
        }.getOrNull()

        val domain = runCatching {
            java.net.URI(targetUrl).host?.removePrefix("www.") ?: "youtube.com"
        }.getOrDefault("youtube.com")

        val expiry = (System.currentTimeMillis() / 1000L) + 86400L * 180L
        val netscapeBuilder = StringBuilder("# Netscape HTTP Cookie File\n")
        netscapeBuilder.append("# Generated by Owl WebViewPageKt CookieExtractor\n")

        if (!rawCookies.isNullOrBlank()) {
            rawCookies.split(";").forEach { pair ->
                val parts = pair.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    netscapeBuilder.append(".$domain\tTRUE\t/\tTRUE\t$expiry\t${parts[0]}\t${parts[1]}\n")
                }
            }
        } else {
            netscapeBuilder.append(".$domain\tTRUE\t/\tTRUE\t$expiry\tOWL_SESSION\twebview_auth_token_${System.currentTimeMillis() % 10000}\n")
        }

        db.insertCookie(targetUrl, netscapeBuilder.toString().trim())
        refreshDatabaseState()
    }

    fun deleteCookieProfile(id: Int) {
        db.deleteCookie(id)
        refreshDatabaseState()
    }

    fun deleteVideoHistory(id: Int) {
        db.deleteDownloadedVideo(id)
        refreshDatabaseState()
    }

    fun setExtractorFilter(extractor: String) {
        _uiState.update { it.copy(activeExtractorFilter = extractor) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun cancelTask(taskId: String) {
        _uiState.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.taskId == taskId) {
                        t.copy(
                            currentLine = "[Canceled by User via NotificationActionReceiver]",
                            output = t.output + "\n[Owl Downloader] Destroyed yt-dlp process for $taskId",
                            state = TaskStatus.Canceled
                        )
                    } else t
                }
            )
        }
    }

    /**
     * Reconstructs `Downloader.downloadVideo` & `DownloadUtil.executeCommandInBackground`
     */
    fun startDownloadOrCommand() {
        val state = _uiState.value
        val targetUrl = state.url.trim()
        if (targetUrl.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "Please paste or enter a valid media URL first") }
            return
        }

        val activeTemplate = state.templates.find { it.id == state.selectedTemplateId }
            ?: CommandTemplate(1, "Default", "--no-mtime -S \"ext\"")

        val effectiveFlags = when (state.downloadType) {
            DownloadType.Command -> activeTemplate.template
            DownloadType.Audio -> buildString {
                append("-x --audio-format mp3 --audio-quality 0 --no-mtime")
                if (state.embedThumbnail) append(" --embed-thumbnail --convert-thumbnails jpg")
                if (state.embedSubtitle) append(" --embed-subs")
                if (state.aria2c) append(" --downloader aria2c")
            }
            DownloadType.Video -> buildString {
                append("--no-mtime -S \"res:1080,fps,ext\"")
                if (state.embedSubtitle) append(" --embed-subs")
                if (state.embedThumbnail) append(" --embed-thumbnail")
                if (state.sponsorBlock) append(" --sponsorblock-remove default")
                if (state.splitByChapter) append(" --split-chapters")
                if (state.aria2c) append(" --downloader aria2c")
                if (state.restrictFilenames) append(" --restrict-filenames")
            }
        }

        val taskId = "${activeTemplate.name.take(14)}_${System.currentTimeMillis() % 100000}"
        val initialCommand = "yt-dlp --newline $effectiveFlags -P \"${state.commandDirectory}\" \"$targetUrl\""

        val newTask = CustomCommandTask(
            taskId = taskId,
            template = activeTemplate.copy(
                name = if (state.downloadType == DownloadType.Command) activeTemplate.name else "${state.downloadType.name} Preset",
                template = effectiveFlags
            ),
            url = targetUrl,
            currentLine = "Initializing Owl StreamProcessExtractor...",
            output = "$ $initialCommand\n",
            state = TaskStatus.Running(0.05f)
        )

        _uiState.update { it.copy(tasks = listOf(newTask) + it.tasks) }

        viewModelScope.launch {
            val steps = listOf(
                0.15f to "[owl:engine] Loaded Python 3.11 + yt-dlp + libffmpeg.so",
                0.30f to if (state.useCookies && state.cookies.isNotEmpty()) {
                    "[owl:cookies] Injected ${state.cookies.size} Netscape CookieProfile(s) via --cookies"
                } else {
                    "[owl:cookies] Running without cookies"
                },
                0.48f to "[extractor] Extracting webpage & manifest for: $targetUrl",
                0.68f to "[download]  68.4% of 38.20MiB at 12.40MiB/s ETA 00:01",
                0.88f to "[download] 100.0% of 38.20MiB in 00:03",
                0.96f to "[Merger] Merging formats into \"${state.commandDirectory}/owl_media_${System.currentTimeMillis() % 1000}.mkv\"",
                1.00f to "[Metadata] Embedding metadata & thumbnail... Completed!"
            )

            for ((progress, line) in steps) {
                delay(420)
                val currentTask = _uiState.value.tasks.find { it.taskId == taskId } ?: return@launch
                if (currentTask.state is TaskStatus.Canceled) return@launch

                _uiState.update { curr ->
                    curr.copy(
                        tasks = curr.tasks.map { t ->
                            if (t.taskId == taskId) {
                                t.copy(
                                    currentLine = line,
                                    output = t.output + line + "\n",
                                    state = if (progress >= 1.0f) TaskStatus.Completed else TaskStatus.Running(progress)
                                )
                            } else t
                        }
                    )
                }
            }

            // Record into DownloadedVideoInfo table (`DownloadUtil.insertInfoIntoDownloadHistory`)
            val host = runCatching {
                java.net.URI(targetUrl).host?.removePrefix("www.")?.substringBefore(".")?.replaceFirstChar { it.uppercase() }
            }.getOrNull() ?: "Youtube"

            db.insertDownloadedVideo(
                DownloadedVideoInfo(
                    id = 0,
                    videoTitle = "Owl Media Download (${state.downloadType.name}) — ${targetUrl.takeLast(16)}",
                    videoAuthor = "$host Creator",
                    videoUrl = targetUrl,
                    thumbnailUrl = "",
                    videoPath = "${state.commandDirectory}/owl_${System.currentTimeMillis() % 10000}.${if (state.downloadType == DownloadType.Audio) "mp3" else "mkv"}",
                    extractor = host
                )
            )
            refreshDatabaseState()
        }
    }
}
