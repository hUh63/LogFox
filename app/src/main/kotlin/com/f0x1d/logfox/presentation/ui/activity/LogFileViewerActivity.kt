package com.f0x1d.logfox.presentation.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.f0x1d.logfox.R
import com.f0x1d.logfox.core.context.shareIntent
import com.f0x1d.logfox.core.context.shareUriIntent
import com.f0x1d.logfox.core.context.toast
import com.f0x1d.logfox.databinding.ActivityLogFileViewerBinding
import com.f0x1d.logfox.feature.strings.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a log file that was opened from another app (file manager, mail client, ...)
 *
 * F0x1d/LogFox#235: opening exported/saved log files from outside of the app was
 * removed at some point, so the app did not even show up as a handler for *.log files.
 */
class LogFileViewerActivity : AppCompatActivity() {

    private companion object {
        // Keep in memory something sane, huge files are truncated instead of crashing
        private const val MAX_BYTES = 8 * 1024 * 1024

        // TextView layout is O(n) and it was done twice (two views). Rendering multi-megabyte
        // text on the main thread froze the screen, so the preview is capped
        private const val MAX_RENDER_CHARS = 500_000

        private const val KEY_WRAPPED = "wrapped"
        private const val KEY_TRUNCATION_NOTIFIED = "truncation_notified"
    }

    private val viewModel: LogFileViewerViewModel by viewModels()

    private lateinit var binding: ActivityLogFileViewerBinding

    private var fileContent: String? = null
    private var renderedText: String? = null
    private var wrapped = true

    // Loading a second file (or re-creating the activity) used to start a parallel read: the
    // slower, older one could win and show file A's text under file B's title
    private var loadJob: Job? = null
    private var truncationNotified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLogFileViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.log_file_wrap_lines_item -> {
                    wrapped = !wrapped

                    item.isChecked = wrapped

                    applyWrap()
                    true
                }

                R.id.log_file_share_item -> {
                    // The file itself is shared: a real log does not fit into an intent extra
                    // (binder transaction limit) and the resulting failure was swallowed
                    val uri = intent?.data

                    if (uri != null) {
                        shareUriIntent(uri)
                    } else {
                        fileContent?.let { shareIntent(it) }
                    }

                    true
                }

                else -> false
            }
        }

        wrapped = savedInstanceState?.getBoolean(KEY_WRAPPED, true) ?: true
        truncationNotified = savedInstanceState?.getBoolean(KEY_TRUNCATION_NOTIFIED) ?: false

        applyWrap()
        restoreOrLoad(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(KEY_WRAPPED, wrapped)
        outState.putBoolean(KEY_TRUNCATION_NOTIFIED, truncationNotified)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Without setIntent() a process recreation after onNewIntent restored the old URI
        setIntent(intent)

        restoreOrLoad(intent)
    }

    private fun restoreOrLoad(intent: Intent?) {
        val uri = intent?.data

        // content:// only: a file:// URI let any app make LogFox open (and render) a private
        // path with its own uid
        if (uri == null || uri.scheme != "content") {
            finish()
            return
        }

        binding.toolbar.title = displayName(uri)

        // A rotation keeps the ViewModel, so skip both the disk read and the text layout
        if (viewModel.loadedUri == uri && viewModel.content != null) {
            applyContent(viewModel.content!!, notifyTruncation = false)
            return
        }

        load(intent)
    }

    private fun applyContent(content: String, notifyTruncation: Boolean) {
        fileContent = content
        viewModel.loadedUri = intent?.data
        viewModel.content = content

        if (content.length > MAX_RENDER_CHARS) {
            renderedText = content.take(MAX_RENDER_CHARS)

            if (notifyTruncation && !truncationNotified) {
                truncationNotified = true

                toast(Strings.file_too_large_to_render)
            }
        } else {
            renderedText = content
        }

        renderText()
    }

    private fun applyWrap() {
        binding.logText.isVisible = wrapped
        binding.logTextScrollableContainer.isVisible = wrapped.not()

        binding.toolbar.menu.findItem(R.id.log_file_wrap_lines_item).isChecked = wrapped

        renderText()
    }

    private fun renderText() {
        val text = renderedText ?: return

        // Only the visible TextView receives the text, the other one stays empty until the
        // wrap toggle swaps them
        if (wrapped) {
            binding.logText.text = text
            binding.logTextScrollable.text = null
        } else {
            binding.logTextScrollable.text = text
            binding.logText.text = null
        }
    }

    private fun load(intent: Intent?) {
        val uri = intent?.data

        // content:// only: a file:// URI let any app make LogFox open (and render) a private
        // path with its own uid
        if (uri == null || uri.scheme != "content") {
            finish()
            return
        }

        binding.toolbar.title = displayName(uri)

        loadJob?.cancel()

        loadJob = lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { readContent(uri) }

            // A newer intent or another activity instance superseded this load
            if (intent?.data != uri) return@launch

            if (content == null) {
                toast(Strings.cannot_open_file)
                finish()
                return@launch
            }

            applyContent(content, notifyTruncation = true)
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull() ?: uri.lastPathSegment ?: getString(Strings.app_name)

    private fun readContent(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val buffer = ByteArray(64 * 1024)
            val output = java.io.ByteArrayOutputStream()

            while (true) {
                val read = inputStream.read(buffer)

                if (read < 0) break

                output.write(buffer, 0, read)

                if (output.size() >= MAX_BYTES) break
            }

            decodeUtf8(output.toByteArray())
        }
    }.getOrNull()

private fun decodeUtf8(bytes: ByteArray): String {
    // The cap may land in the middle of a multi-byte character; decoding it produces a
    // replacement character at the end of every large log file
    var endIndex = bytes.size
    while (endIndex > 0 && (bytes[endIndex - 1].toInt() and 0xC0) == 0x80) endIndex--
    return bytes.decodeToString(endIndex = endIndex)
}
}
