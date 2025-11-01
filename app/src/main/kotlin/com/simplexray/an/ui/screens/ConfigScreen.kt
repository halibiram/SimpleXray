package com.simplexray.an.ui.screens

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.simplexray.an.R
import com.simplexray.an.viewmodel.MainViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

private const val TAG = "ConfigScreen"

@Composable
fun ConfigScreen(
    onReloadConfig: () -> Unit,
    onEditConfigClick: (File) -> Unit,
    onDeleteConfigClick: (File, () -> Unit) -> Unit,
    mainViewModel: MainViewModel,
    listState: LazyListState
) {
    val showDeleteDialog = remember { mutableStateOf<File?>(null) }
    val showRenameDialog = remember { mutableStateOf<File?>(null) }
    val showCopyDialog = remember { mutableStateOf<File?>(null) }
    val showTagsDialog = remember { mutableStateOf<File?>(null) }
    val renameText = remember { mutableStateOf("") }
    val copyText = remember { mutableStateOf("") }
    val tagsText = remember { mutableStateOf("") }
    val searchText = remember { mutableStateOf("") }
    val selectedTag = remember { mutableStateOf<String?>(null) }

    val isServiceEnabled by mainViewModel.isServiceEnabled.collectAsState()

    val files by mainViewModel.configFiles.collectAsState()
    val selectedFile by mainViewModel.selectedConfigFile.collectAsState()
    val tagsMap by mainViewModel.configTags.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainViewModel.refreshConfigFileList()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        mainViewModel.refreshConfigFileList()
    }

    val hapticFeedback = LocalHapticFeedback.current
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        mainViewModel.moveConfigFile(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Search + Tag filter
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchText.value,
                onValueChange = { searchText.value = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.search)) }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val allTags = tagsMap.values.flatten().distinct().sorted()
            allTags.forEach { tag ->
                FilterChip(
                    selected = selectedTag.value == tag,
                    onClick = { selectedTag.value = if (selectedTag.value == tag) null else tag },
                    label = { Text(tag) }
                )
            }
        }
        
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_config_files),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val filtered = files.filter { f ->
                val q = searchText.value.trim().lowercase()
                val nameMatch = q.isEmpty() || f.name.lowercase().contains(q)
                val tagMatch = selectedTag.value?.let { t -> (tagsMap[f.name] ?: emptyList()).any { it.equals(t, true) } } ?: true
                nameMatch && tagMatch
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 10.dp, top = 10.dp),
                state = listState
            ) {
                items(filtered, key = { it }) { file ->
                    ReorderableItem(reorderableLazyListState, key = file) {
                        val isSelected = file == selectedFile
                        val itemMenuExpanded = remember { mutableStateOf(false) }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(MaterialTheme.shapes.extraLarge)
                                .clickable {
                                    mainViewModel.updateSelectedConfigFile(file)
                                    if (isServiceEnabled) {
                                        Log.d(
                                            TAG,
                                            "Config selected while service is running, requesting reload."
                                        )
                                        onReloadConfig()
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max)
                                    .longPressDraggableHandle(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        file.name.removeSuffix(".json"),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    // Tags preview
                                    val t = tagsMap[file.name] ?: emptyList()
                                    if (t.isNotEmpty()) {
                                        Text(t.joinToString(" • "), modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { itemMenuExpanded.value = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                                    }
                                    DropdownMenu(expanded = itemMenuExpanded.value, onDismissRequest = { itemMenuExpanded.value = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = {
                                            itemMenuExpanded.value = false
                                            onEditConfigClick(file)
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                                            itemMenuExpanded.value = false
                                            renameText.value = file.name.removeSuffix(".json")
                                            showRenameDialog.value = file
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = {
                                            itemMenuExpanded.value = false
                                            copyText.value = file.name.removeSuffix(".json") + "_copy"
                                            showCopyDialog.value = file
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tags)) }, onClick = {
                                            itemMenuExpanded.value = false
                                            tagsText.value = (tagsMap[file.name] ?: emptyList()).joinToString(",")
                                            showTagsDialog.value = file
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                            itemMenuExpanded.value = false
                                            showDeleteDialog.value = file
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showDeleteDialog.value?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog.value = null },
            title = { Text(stringResource(R.string.delete_config)) },
            text = { Text(fileToDelete.name) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog.value = null
                    onDeleteConfigClick(fileToDelete) {
                        mainViewModel.refreshConfigFileList()
                        mainViewModel.updateSelectedConfigFile(null)
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    showRenameDialog.value?.let { fileToRename ->
        AlertDialog(onDismissRequest = { showRenameDialog.value = null }, title = { Text(stringResource(R.string.rename)) }, text = {
            OutlinedTextField(value = renameText.value, onValueChange = { renameText.value = it }, label = { Text(stringResource(R.string.filename)) })
        }, confirmButton = {
            TextButton(onClick = {
                val newName = renameText.value.trim()
                if (newName.isNotEmpty()) {
                    mainViewModel.renameConfigFile(fileToRename, newName) { }
                }
                showRenameDialog.value = null
            }) { Text(stringResource(R.string.confirm)) }
        }, dismissButton = {
            TextButton(onClick = { showRenameDialog.value = null }) { Text(stringResource(R.string.cancel)) }
        })
    }

    showCopyDialog.value?.let { src ->
        AlertDialog(onDismissRequest = { showCopyDialog.value = null }, title = { Text(stringResource(R.string.copy)) }, text = {
            OutlinedTextField(value = copyText.value, onValueChange = { copyText.value = it }, label = { Text(stringResource(R.string.filename)) })
        }, confirmButton = {
            TextButton(onClick = {
                val newName = copyText.value.trim()
                if (newName.isNotEmpty()) {
                    mainViewModel.duplicateConfigFile(src, newName) { }
                }
                showCopyDialog.value = null
            }) { Text(stringResource(R.string.confirm)) }
        }, dismissButton = {
            TextButton(onClick = { showCopyDialog.value = null }) { Text(stringResource(R.string.cancel)) }
        })
    }

    showTagsDialog.value?.let { target ->
        AlertDialog(onDismissRequest = { showTagsDialog.value = null }, title = { Text(stringResource(R.string.tags)) }, text = {
            OutlinedTextField(value = tagsText.value, onValueChange = { tagsText.value = it }, label = { Text("comma,separated") })
        }, confirmButton = {
            TextButton(onClick = {
                val parts = tagsText.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                mainViewModel.setTagsFor(target.name, parts)
                showTagsDialog.value = null
            }) { Text(stringResource(R.string.confirm)) }
        }, dismissButton = {
            TextButton(onClick = { showTagsDialog.value = null }) { Text(stringResource(R.string.cancel)) }
        })
    }
}
