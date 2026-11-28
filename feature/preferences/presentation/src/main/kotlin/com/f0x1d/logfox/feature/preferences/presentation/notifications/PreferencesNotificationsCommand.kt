package com.f0x1d.logfox.feature.preferences.presentation.notifications

internal sealed interface PreferencesNotificationsCommand {
    data object CheckPermission : PreferencesNotificationsCommand
    data object OpenLoggingNotificationSettings : PreferencesNotificationsCommand
    data object OpenNotificationsPermissionSettings : PreferencesNotificationsCommand
    data object OpenNotificationChannels : PreferencesNotificationsCommand

    // Commands from effect handler
    data class PermissionChecked(val hasPermission: Boolean) : PreferencesNotificationsCommand
}
