package com.f0x1d.logfox.presentation.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
        load(intent)
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

        load(intent)
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

            fileContent = content

            if (content.length > MAX_RENDER_CHARS) {
                renderedText = content.take(MAX_RENDER_CHARS)

                // Re-created on rotation (the content is re-read) without spamming the hint
                if (!truncationNotified) {
                    truncationNotified = true

                    toast(Strings.file_too_large_to_render)
                }
            } else {
                renderedText = content
            }

            renderText()
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

            output.toByteArray().decodeToString()
        }
    }.getOrNull()
}
