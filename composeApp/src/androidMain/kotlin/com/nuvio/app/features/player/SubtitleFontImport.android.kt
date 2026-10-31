package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

// Subtitle font choice is only rendered by the iOS (mpv) player.
internal actual fun importedSubtitleFontOptions(): List<SubtitleFontOption> = emptyList()

internal actual fun removeImportedSubtitleFont(id: String) = Unit

@Composable
internal actual fun rememberSubtitleFontImporter(onImported: (SubtitleFontOption?) -> Unit): () -> Unit = {}
