@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.iris.gallery.ui

import com.iris.gallery.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun RenameFileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val nameWithoutExt = currentName.substringBeforeLast('.')
    val ext = currentName.substringAfterLast('.', "")
    var newName by remember { mutableStateOf(nameWithoutExt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_file_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.rename_file_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    suffix = if (ext.isNotEmpty()) { { Text(".$ext", color = MaterialTheme.colorScheme.outline) } } else null
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = if (ext.isNotEmpty()) "$newName.$ext" else newName
                    onConfirm(finalName)
                    onDismiss()
                },
                enabled = newName.isNotBlank() && newName.trim() != nameWithoutExt
            ) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
