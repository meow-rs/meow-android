package io.github.madeye.meow.ui.screens.subscribe

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.ui.theme.meow

/**
 * Asks before adding a subscription offered by an install-config link.
 *
 * The link can come from any web page, so the user sees exactly what will be
 * fetched — the full URL, not just the name the page chose — before anything
 * happens.
 */
@Composable
fun InstallConfigDialog(
    link: InstallConfigLink.Valid,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MaterialTheme.meow
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subs_link_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.subs_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.mutedText,
                )
                Text(text = link.name, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.subs_url),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.mutedText,
                )
                // The host comes first, so a cut-off tail still shows where the
                // config would be fetched from.
                Text(
                    text = link.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.subs_link_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Why a link was turned away, as the snackbar says it. */
@StringRes
fun InstallConfigLink.Reason.messageRes(): Int = when (this) {
    InstallConfigLink.Reason.UNSUPPORTED_LINK -> R.string.subs_link_unsupported
    InstallConfigLink.Reason.MISSING_URL -> R.string.subs_link_missing_url
    InstallConfigLink.Reason.UNSUPPORTED_URL -> R.string.subs_link_bad_url
}
