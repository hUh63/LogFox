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
import com.f0x1d.logfox.core.context.toast
import com.f0x1d.logfox.databinding.ActivityLogFileViewerBinding
import com.f0x1d.logfox.feature.strings.Strings
import kotlinx.coroutines.Dispatchers
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
    }

    private lateinit var binding: ActivityLogFileViewerBinding

    private var fileContent: String? = null
    private var wrapped = true

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
                    fileContent?.let { shareIntent(it) }
                    true
                }

                else -> false
            }
        }

        applyWrap()
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        load(intent)
    }

    private fun applyWrap() {
        binding.logText.isVisible = wrapped
        binding.logTextScrollableContainer.isVisible = wrapped.not()

        binding.toolbar.menu.findItem(R.id.log_file_wrap_lines_item).isChecked = wrapped
    }

    private fun load(intent: Intent?) {
        val uri = intent?.data

        if (uri == null) {
            finish()
            return
        }

        binding.toolbar.title = displayName(uri)

        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { readContent(uri) }

            if (content == null) {
                toast(Strings.cannot_open_file)
                finish()
                return@launch
            }

            fileContent = content

            binding.logText.text = content
            binding.logTextScrollable.text = content
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
