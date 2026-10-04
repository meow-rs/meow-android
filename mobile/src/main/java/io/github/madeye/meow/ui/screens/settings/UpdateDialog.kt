package io.github.madeye.meow.ui.screens.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.madeye.meow.R
import io.github.madeye.meow.update.AppRelease

/** Release notes beyond this are cut; GitHub allows 125,000 characters. */
internal const val MAX_NOTES_CHARS = 3_000

/**
 * Offers a newer GitHub release: its notes, and a link to its APK (or, if it
 * has none, its page). The browser downloads the APK and the system installer
 * takes it from there, so the app never needs REQUEST_INSTALL_PACKAGES.
 */
@Composable
fun UpdateDialog(
    release: AppRelease,
    onDismiss: () -> Unit,
    onOpen: (url: String) -> Unit,
) {
    val notes = remember(release.notes) { plainReleaseNotes(release.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_update_available, release.version)) },
        text = if (notes.isEmpty()) {
            null
        } else {
            {
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen(release.apkUrl ?: release.pageUrl) }) {
                Text(
                    stringResource(
                        if (release.apkUrl != null) R.string.settings_update_download else R.string.settings_update_view,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_update_later)) }
        },
    )
}

/**
 * GitHub release notes are Markdown; the dialog shows them as plain text:
 * heading marks, bold and code ticks dropped, list markers as bullets, links
 * as their text. Single `*`/`_` stay, as they are as often literal.
 */
internal fun plainReleaseNotes(markdown: String): String {
    val text = markdown.lineSequence()
        .joinToString("\n") { line -> line.trimEnd().replace(HEADING, "").replace(LIST_ITEM, "$1• ") }
        .replace(LINK, "$1")
        .replace(STRONG_OR_CODE, "")
        .trim()
    if (text.length <= MAX_NOTES_CHARS) return text
    // At a line break, so no line is left half-finished.
    val cut = text.take(MAX_NOTES_CHARS)
    return cut.substringBeforeLast('\n', cut).trimEnd() + "\n…"
}

private val HEADING = Regex("""^ {0,3}#{1,6}\s+""")
private val LIST_ITEM = Regex("""^(\s*)[-*+]\s+""")
private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val STRONG_OR_CODE = Regex("""\*\*|__|`""")
