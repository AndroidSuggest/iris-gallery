@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.iris.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.iris.gallery.data.MediaImage
import com.iris.gallery.ui.DuplicateScanState
import com.iris.gallery.ui.MediaThumbnail
import com.iris.gallery.data.DuplicateGroup
import com.iris.gallery.R
import java.util.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
internal fun DuplicateReviewScreen(
    state: DuplicateScanState,
    padding: PaddingValues,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (DuplicateGroup, MediaImage) -> Unit,
    onTrash: (List<MediaImage>) -> Unit,
) {
    var selectedIds by remember(state.groups) {
        mutableStateOf(state.groups.flatMap { it.items.drop(1) }.mapTo(mutableSetOf()) { it.id }.toSet())
    }
    val layoutDir = LocalLayoutDirection.current
    val startPadding = padding.calculateStartPadding(layoutDir)
    val endPadding = padding.calculateEndPadding(layoutDir)
    val topPadding = padding.calculateTopPadding()
    val bottomPadding = padding.calculateBottomPadding()

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(start = startPadding, top = topPadding, end = endPadding, bottom = 0.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.duplicates_hero_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.duplicates_hero_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        state.scanning -> {
                            val progress = if (state.total == 0) 0f else state.done.toFloat() / state.total
                            LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.duplicates_comparing_progress, state.done, state.total), style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                            }
                        }
                        else -> Button(onClick = onScan) { Text(if (state.hasScanned) stringResource(R.string.duplicates_scan_again) else stringResource(R.string.duplicates_scan_library)) }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (state.hasScanned && state.groups.isEmpty() && state.error == null) {
            item { Text(stringResource(R.string.duplicates_empty_state),
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
        }
        if (state.groups.isNotEmpty()) {
            item {
                val selected = state.groups.flatMap { it.items }.filter { it.id in selectedIds }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.duplicates_groups_count, state.groups.size), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.duplicates_suggested_removal, selected.size, formatBytes(selected.sumOf { it.sizeBytes })),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(enabled = selected.isNotEmpty(), onClick = { onTrash(selected) }) { Text(stringResource(R.string.duplicates_review_delete)) }
                }
            }
            items(state.groups, key = { group -> group.items.joinToString { it.id.toString() } }) { group ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (group.exact) stringResource(R.string.duplicates_exact_copies) else stringResource(R.string.duplicates_similar_photos), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.duplicates_save_up_to, formatBytes(group.reclaimableBytes)),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(group.items, key = { it.id }) { media ->
                                val selected = media.id in selectedIds
                                Box(Modifier.size(126.dp).clip(RoundedCornerShape(16.dp))
                                    .clickable { selectedIds = selectedIds.toMutableSet().apply {
                                        if (!add(media.id)) remove(media.id)
                                    } }) {
                                    MediaThumbnail(media, Modifier.fillMaxSize())
                                    Surface(Modifier.align(Alignment.TopEnd).padding(6.dp),
                                        shape = RoundedCornerShape(50),
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = .86f)) {
                                        Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Close,
                                            if (selected) stringResource(R.string.duplicates_remove) else stringResource(R.string.duplicates_keep), Modifier.padding(5.dp).size(18.dp),
                                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                    }
                                    TextButton(onClick = { onOpen(group, media) },
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = .82f))) { Text(stringResource(R.string.duplicates_preview)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(java.util.Locale.US, bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.US, bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(java.util.Locale.US, bytes / 1_000.0)
    else -> "$bytes B"
}
