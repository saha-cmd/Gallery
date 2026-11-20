package com.gallery.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gallery.core.domain.model.DuplicateAnalysisResult
import com.gallery.core.domain.model.DuplicateCandidate
import com.gallery.core.domain.model.DuplicateGroup
import com.gallery.core.domain.model.DuplicateGroupType
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.pow

enum class DuplicateFilterChip {
    ALL,
    IDENTICAL_COPIES,
    SAME_NAME_DIFFERENT_CONTENT
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}

private fun formatDate(epochMillisOrSeconds: Long): String {
    if (epochMillisOrSeconds <= 0L) return ""
    val millis = if (epochMillisOrSeconds > 1_000_000_000_000L) epochMillisOrSeconds else epochMillisOrSeconds * 1000L
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
}

private fun generateSuggestedName(currentName: String, existingNamesInGroup: List<String>): String {
    val nameWithoutExt = currentName.substringBeforeLast('.', currentName)
    val ext = if (currentName.contains('.')) "." + currentName.substringAfterLast('.') else ""
    val regex = Regex("^(.*)_([0-9]+)$")
    val match = regex.matchEntire(nameWithoutExt)
    var base = nameWithoutExt
    var counter = 2
    if (match != null) {
        base = match.groupValues[1]
        counter = (match.groupValues[2].toIntOrNull() ?: 1) + 1
    }
    var candidate = "${base}_$counter$ext"
    val existingNormalized = existingNamesInGroup.map { it.trim().lowercase() }.toSet()
    while (candidate.trim().lowercase() in existingNormalized) {
        counter++
        candidate = "${base}_$counter$ext"
    }
    return candidate
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DuplicateAnalyzerSheet(
    isScanning: Boolean,
    scannedCount: Int,
    totalCount: Int,
    result: DuplicateAnalysisResult<T>?,
    onDismiss: () -> Unit,
    onDeleteItem: (T) -> Unit,
    onDeleteAllDuplicatesInGroup: (DuplicateGroup<T>) -> Unit,
    onRenameItem: (T, String) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedFilter by remember { mutableStateOf(DuplicateFilterChip.ALL) }

    // Dialog states
    var candidateToRename by remember { mutableStateOf<DuplicateCandidate<T>?>(null) }
    var renameGroupCandidates by remember { mutableStateOf<List<DuplicateCandidate<T>>>(emptyList()) }
    var itemToDelete by remember { mutableStateOf<DuplicateCandidate<T>?>(null) }
    var isDeletingKeptItem by remember { mutableStateOf(false) }
    var groupToDeleteDuplicates by remember { mutableStateOf<DuplicateGroup<T>?>(null) }

    val allGroups = result?.groups ?: emptyList()
    val filteredGroups = remember(allGroups, selectedFilter) {
        when (selectedFilter) {
            DuplicateFilterChip.ALL -> allGroups
            DuplicateFilterChip.IDENTICAL_COPIES -> allGroups.filter {
                it.type == DuplicateGroupType.IDENTICAL_SAME_NAME || it.type == DuplicateGroupType.IDENTICAL_DIFFERENT_NAME
            }
            DuplicateFilterChip.SAME_NAME_DIFFERENT_CONTENT -> allGroups.filter {
                it.type == DuplicateGroupType.SAME_NAME_DIFFERENT_CONTENT
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Duplicate & Name Analyzer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isScanning && result != null) {
                        val recoverable = formatBytes(result.totalReclaimableBytes)
                        Text(
                            text = "${result.groups.size} duplicate group(s) • $recoverable recoverable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            // Scanning State
            if (isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Scanning photos & videos...",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "$scannedCount / $totalCount items analyzed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (allGroups.isEmpty()) {
                // Empty State
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No duplicate photos or videos found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Your storage is clean. No identical media copies or name collisions detected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                // Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == DuplicateFilterChip.ALL,
                        onClick = { selectedFilter = DuplicateFilterChip.ALL },
                        label = { Text("All (${allGroups.size})") }
                    )
                    val identicalCount = allGroups.count {
                        it.type == DuplicateGroupType.IDENTICAL_SAME_NAME || it.type == DuplicateGroupType.IDENTICAL_DIFFERENT_NAME
                    }
                    FilterChip(
                        selected = selectedFilter == DuplicateFilterChip.IDENTICAL_COPIES,
                        onClick = { selectedFilter = DuplicateFilterChip.IDENTICAL_COPIES },
                        label = { Text("Identical Copies ($identicalCount)") }
                    )
                    val nameCollisionCount = allGroups.count {
                        it.type == DuplicateGroupType.SAME_NAME_DIFFERENT_CONTENT
                    }
                    FilterChip(
                        selected = selectedFilter == DuplicateFilterChip.SAME_NAME_DIFFERENT_CONTENT,
                        onClick = { selectedFilter = DuplicateFilterChip.SAME_NAME_DIFFERENT_CONTENT },
                        label = { Text("Same Name, Different Content ($nameCollisionCount)") }
                    )
                }

                // Groups List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredGroups, key = { it.groupId }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            onDeleteCandidate = { cand ->
                                itemToDelete = cand
                                isDeletingKeptItem = cand.isRecommendedKeep
                            },
                            onDeleteAllDuplicates = {
                                groupToDeleteDuplicates = group
                            },
                            onRenameCandidate = { cand ->
                                candidateToRename = cand
                                renameGroupCandidates = group.candidates
                            },
                            thumbnail = thumbnail
                        )
                    }
                }
            }
        }
    }

    // Rename Dialog
    candidateToRename?.let { cand ->
        RenameCandidateDialog(
            candidate = cand,
            existingGroupNames = renameGroupCandidates.map { it.name },
            onDismiss = { candidateToRename = null },
            onConfirmRename = { newName ->
                onRenameItem(cand.item, newName)
                candidateToRename = null
            }
        )
    }

    // Single Item Delete Confirmation
    itemToDelete?.let { cand ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = {
                Text(
                    text = if (isDeletingKeptItem) "Delete Recommended Copy?" else "Delete Copy?"
                )
            },
            text = {
                Text(
                    text = if (isDeletingKeptItem) {
                        "This copy is marked as the recommended version to keep. Are you sure you want to delete '${cand.name}'?"
                    } else {
                        "Are you sure you want to delete '${cand.name}'? It will be removed from your gallery."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteItem(cand.item)
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Group Delete Duplicates Confirmation
    groupToDeleteDuplicates?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToDeleteDuplicates = null },
            icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete All Duplicates?") },
            text = {
                val nonKeptCount = group.candidates.count { !it.isRecommendedKeep }
                val spaceSaved = formatBytes(group.reclaimableBytes)
                Text(
                    text = "This will delete $nonKeptCount duplicate copy/copies and reclaim $spaceSaved. The recommended copy will be preserved."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteAllDuplicatesInGroup(group)
                        groupToDeleteDuplicates = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Duplicates")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDeleteDuplicates = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun <T> DuplicateGroupCard(
    group: DuplicateGroup<T>,
    onDeleteCandidate: (DuplicateCandidate<T>) -> Unit,
    onDeleteAllDuplicates: () -> Unit,
    onRenameCandidate: (DuplicateCandidate<T>) -> Unit,
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Group Header Badge & Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                when (group.type) {
                    DuplicateGroupType.IDENTICAL_SAME_NAME -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "Identical • Same Name",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    DuplicateGroupType.IDENTICAL_DIFFERENT_NAME -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "Identical • Different Names",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    DuplicateGroupType.SAME_NAME_DIFFERENT_CONTENT -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "Same Name • Different Content",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (group.type != DuplicateGroupType.SAME_NAME_DIFFERENT_CONTENT && group.candidates.size > 1) {
                    FilledTonalButton(
                        onClick = onDeleteAllDuplicates,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Delete Duplicates",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (group.type == DuplicateGroupType.SAME_NAME_DIFFERENT_CONTENT) {
                Text(
                    text = "Same filename, but different photo/video content. Rename one item to resolve the conflict or delete if unwanted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Candidates List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.candidates.forEach { candidate ->
                    CandidateItemRow(
                        candidate = candidate,
                        isSameNameDifferentContent = group.type == DuplicateGroupType.SAME_NAME_DIFFERENT_CONTENT,
                        onDelete = { onDeleteCandidate(candidate) },
                        onRename = { onRenameCandidate(candidate) },
                        thumbnail = thumbnail
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> CandidateItemRow(
    candidate: DuplicateCandidate<T>,
    isSameNameDifferentContent: Boolean,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (candidate.isRecommendedKeep) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                thumbnail(candidate.item, Modifier.fillMaxSize())
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Item Details
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = candidate.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (candidate.isRecommendedKeep) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(start = 2.dp)
                        ) {
                            Text(
                                text = "Keep",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = "📁 ${candidate.albumOrFolderName.ifBlank { "Main" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatBytes(candidate.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val dateFormatted = formatDate(candidate.dateAdded)
                    if (dateFormatted.isNotBlank()) {
                        Text(
                            text = "•  $dateFormatted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (isSameNameDifferentContent) {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Rename",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> RenameCandidateDialog(
    candidate: DuplicateCandidate<T>,
    existingGroupNames: List<String>,
    onDismiss: () -> Unit,
    onConfirmRename: (String) -> Unit
) {
    val suggestedName = remember(candidate.name) {
        generateSuggestedName(candidate.name, existingGroupNames)
    }
    var inputName by remember { mutableStateOf(suggestedName) }

    val originalExt = remember(candidate.name) {
        if (candidate.name.contains('.')) candidate.name.substringAfterLast('.') else ""
    }

    val trimmedInput = inputName.trim()
    val isBlank = trimmedInput.isEmpty()
    val isUnchanged = trimmedInput.equals(candidate.name.trim(), ignoreCase = true)
    val isCollisionWithGroup = existingGroupNames.any { it.trim().equals(trimmedInput, ignoreCase = true) && !it.trim().equals(candidate.name.trim(), ignoreCase = true) }

    val errorMessage = when {
        isBlank -> "Name cannot be empty"
        isUnchanged -> "Please choose a different name"
        isCollisionWithGroup -> "Another item in this group already has this name"
        else -> null
    }

    val isValid = errorMessage == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Current name: ${candidate.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (originalExt.isNotEmpty()) {
                    Text(
                        text = "File extension (.$originalExt) will be preserved.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("New file name") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmRename(trimmedInput) },
                enabled = isValid
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
