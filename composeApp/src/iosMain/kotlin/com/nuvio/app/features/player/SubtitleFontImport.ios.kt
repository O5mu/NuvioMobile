package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeFont
import platform.darwin.NSObject

private val subtitleFontExtensions = setOf("ttf", "otf", "ttc", "otc")

// UIDocumentPickerViewController holds its delegate weakly.
private var activeSubtitleFontPickerDelegate: SubtitleFontPickerDelegate? = null

/** Application Support/SubtitleFonts; the Swift player reads imported fonts from here. */
@OptIn(ExperimentalForeignApi::class)
private fun importedSubtitleFontsDirectory(): NSURL? {
    val fileManager = NSFileManager.defaultManager
    val supportUrl = fileManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        .firstOrNull() as? NSURL ?: return null
    val directory = supportUrl.URLByAppendingPathComponent("SubtitleFonts", isDirectory = true) ?: return null
    fileManager.createDirectoryAtURL(directory, withIntermediateDirectories = true, attributes = null, error = null)
    return directory
}

private fun String.isSubtitleFontFileName(): Boolean =
    substringAfterLast('.', "").lowercase() in subtitleFontExtensions

private fun importedFontOption(fileName: String) =
    SubtitleFontOption(SubtitleFontOption.IMPORTED_PREFIX + fileName, fileName.substringBeforeLast('.'))

@OptIn(ExperimentalForeignApi::class)
internal actual fun importedSubtitleFontOptions(): List<SubtitleFontOption> {
    val path = importedSubtitleFontsDirectory()?.path ?: return emptyList()
    return NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, error = null).orEmpty()
        .filterIsInstance<String>()
        .filter { it.isSubtitleFontFileName() }
        .sorted()
        .map(::importedFontOption)
}

@OptIn(ExperimentalForeignApi::class)
private fun importSubtitleFont(source: NSURL): SubtitleFontOption? {
    val fileName = source.lastPathComponent?.takeIf { it.isSubtitleFontFileName() } ?: return null
    val target = importedSubtitleFontsDirectory()?.URLByAppendingPathComponent(fileName) ?: return null
    val fileManager = NSFileManager.defaultManager
    fileManager.removeItemAtURL(target, error = null)
    if (!fileManager.copyItemAtURL(source, toURL = target, error = null)) return null
    return importedFontOption(fileName)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun removeImportedSubtitleFont(id: String) {
    if (!id.startsWith(SubtitleFontOption.IMPORTED_PREFIX)) return
    val fileName = id.removePrefix(SubtitleFontOption.IMPORTED_PREFIX)
    if (fileName.isEmpty() || '/' in fileName) return
    val target = importedSubtitleFontsDirectory()?.URLByAppendingPathComponent(fileName) ?: return
    NSFileManager.defaultManager.removeItemAtURL(target, error = null)
}

private class SubtitleFontPickerDelegate(
    private val onResult: (SubtitleFontOption?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        activeSubtitleFontPickerDelegate = null
        onResult((didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.let(::importSubtitleFont))
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activeSubtitleFontPickerDelegate = null
        onResult(null)
    }
}

@Composable
internal actual fun rememberSubtitleFontImporter(onImported: (SubtitleFontOption?) -> Unit): () -> Unit {
    val viewController = LocalUIViewController.current
    val latestOnImported = rememberUpdatedState(onImported)
    return remember(viewController) {
        {
            // asCopy gives the app its own copy, so no security-scoped access is needed.
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFont), asCopy = true)
            val delegate = SubtitleFontPickerDelegate { latestOnImported.value(it) }
            activeSubtitleFontPickerDelegate = delegate
            picker.delegate = delegate
            picker.allowsMultipleSelection = false

            var presenter = viewController
            while (true) presenter = presenter.presentedViewController ?: break
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }
}
