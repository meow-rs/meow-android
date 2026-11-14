package io.github.madeye.meow.ui.screens.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.ui.theme.meow

/** Form for scalar fields in a local profile's proxy entry. */
@Composable
fun ProxyEditScreen(
    state: ProxyEditUiState,
    contentPadding: PaddingValues,
    onChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> Box(
            modifier = modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ReadOnlyField(label = "Name", value = state.name, tag = "proxy_edit_name")
                ReadOnlyField(label = "Proxy Type", value = state.type, tag = "proxy_edit_type")
            }
            items(state.fields, key = { it.key }) { field ->
                if (field.key != "type") {
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = { onChange(field.key, it) },
                        label = { Text(field.key.replace('-', ' ').replaceFirstChar(Char::uppercase)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("proxy_edit_${field.key}"),
                    )
                }
            }
            item {
                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.meow.danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String, tag: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}
