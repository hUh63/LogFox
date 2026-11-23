package com.f0x1d.logfox.compose.designsystem.component.button

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.f0x1d.logfox.compose.base.preview.DayNightPreview
import com.f0x1d.logfox.compose.designsystem.Icons
import com.f0x1d.logfox.compose.designsystem.theme.LogFoxTheme
import com.f0x1d.logfox.feature.strings.Strings

@Composable
fun NavigationBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        modifier = modifier,
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(id = Icons.ic_arrow_back),
            // Icon-only buttons need a label, otherwise TalkBack only announces "Button"
            contentDescription = stringResource(Strings.back),
        )
    }
}

@DayNightPreview
@Composable
private fun Preview() = LogFoxTheme {
    NavigationBackButton(onClick = { })
}
