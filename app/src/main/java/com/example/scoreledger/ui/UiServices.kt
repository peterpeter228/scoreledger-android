package com.example.scoreledger.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.scoreledger.backup.BackupManager
import com.example.scoreledger.export.ReportManager

/** Explicit UI dependencies keep test repositories, files and destructive controls together. */
data class UiServices(val backup:BackupManager,val reports:ReportManager)
val LocalUiServices=staticCompositionLocalOf<UiServices> { error("UI services must be provided") }
