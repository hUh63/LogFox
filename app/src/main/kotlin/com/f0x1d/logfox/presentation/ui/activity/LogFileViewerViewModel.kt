package com.f0x1d.logfox.presentation.ui.activity

import android.net.Uri
import androidx.lifecycle.ViewModel

/**
 * The viewer content is kept out of the activity: a rotation used to re-read the whole
 * file (up to 8 MB) and re-lay out 500k characters on the main thread. Putting the text
 * in the saved-instance Bundle is not an option either (transaction limit).
 */
class LogFileViewerViewModel : ViewModel() {
    var loadedUri: Uri? = null
    var content: String? = null
}
