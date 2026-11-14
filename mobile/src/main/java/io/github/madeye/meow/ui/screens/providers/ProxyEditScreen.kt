package io.github.madeye.meow.ui.screens.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
            items(state.fields.groupBy { it.section }.entries.toList(), key = { it.key }) { (section, fields) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(section, style = MaterialTheme.typography.titleSmall)
                    fields.forEach { field ->
                        ProxyField(field = field, onChange = onChange)
                    }
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
private fun ProxyField(field: ProxyEditField, onChange: (String, String) -> Unit) {
    when (field.kind) {
        ProxyEditFieldKind.Boolean -> Row(
            modifier = Modifier.fillMaxWidth().testTag("proxy_edit_${field.key}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.label, modifier = Modifier.weight(1f))
            Switch(
                checked = field.value.equals("true", ignoreCase = true),
                onCheckedChange = { onChange(field.key, it.toString()) },
            )
        }
        ProxyEditFieldKind.Select -> SelectField(field = field, onChange = onChange)
        else -> SensitiveTextField(field = field, onChange = onChange)
    }
}

@Composable
private fun SensitiveTextField(field: ProxyEditField, onChange: (String, String) -> Unit) {
    var revealed by remember(field.key) { mutableStateOf(!field.sensitive) }
    OutlinedTextField(
        value = field.value,
        onValueChange = { onChange(field.key, it) },
        label = { Text(field.label) },
        singleLine = true,
        visualTransformation = if (field.sensitive && !revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (field.sensitive) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (revealed) "Hide ${field.label}" else "Show ${field.label}",
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = when {
            field.kind == ProxyEditFieldKind.Number -> KeyboardOptions(keyboardType = KeyboardType.Number)
            field.sensitive -> KeyboardOptions(keyboardType = KeyboardType.Password)
            else -> KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth().testTag("proxy_edit_${field.key}"),
    )
}

@Composable
private fun SelectField(field: ProxyEditField, onChange: (String, String) -> Unit) {
    var expanded by remember(field.key) { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = field.value.ifEmpty { "Default" },
            onValueChange = {},
            readOnly = true,
            label = { Text(field.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("proxy_edit_${field.key}")
                .clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.ifEmpty { "Default" }) },
                    onClick = {
                        expanded = false
                        onChange(field.key, option)
                    },
                )
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
