package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/** Fonts the user imported from Files, with ids starting with [SubtitleFontOption.IMPORTED_PREFIX]. */
internal expect fun importedSubtitleFontOptions(): List<SubtitleFontOption>

/**
 * Returns a launcher that opens the system file picker for a font file, copies it into the
 * app and reports the new option, or null when the user cancels or the file is not a font.
 */
@Composable
internal expect fun rememberSubtitleFontImporter(onImported: (SubtitleFontOption?) -> Unit): () -> Unit

internal fun availableSubtitleFontOptions(): List<SubtitleFontOption> =
    SubtitleFontOption.all + importedSubtitleFontOptions()
