package com.gallery.feature.gallery.viewmodel

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gallery.core.domain.model.RecycleBinItem
import com.gallery.core.domain.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecycleBinUiState(
    val items: List<RecycleBinItem> = emptyList(),
    val isLoading: Boolean = true,
    val infoMessage: String? = null,
    val selectedItemIds: Set<String> = emptySet(),
    val pendingDeleteIntentSender: IntentSender? = null,
    val pendingDeleteItems: List<RecycleBinItem> = emptyList()
) {
    val isSelectionMode: Boolean
        get() = selectedItemIds.isNotEmpty()

    val selectedItems: List<RecycleBinItem>
        get() = items.filter { it.id in selectedItemIds }
}

@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val getRecycleBinItemsUseCase: GetRecycleBinItemsUseCase,
    private val restoreFromRecycleBinUseCase: RestoreFromRecycleBinUseCase,
    private val deletePermanentlyUseCase: DeletePermanentlyUseCase,
    private val emptyRecycleBinUseCase: EmptyRecycleBinUseCase,
    private val getPendingPermanentDeleteIntentSenderUseCase: GetPendingPermanentDeleteIntentSenderUseCase,
    private val finalizePermanentDeleteUseCase: FinalizePermanentDeleteUseCase,
    getDefaultGridColumnsUseCase: GetDefaultGridColumnsUseCase,
    private val setDefaultGridColumnsUseCase: SetDefaultGridColumnsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecycleBinUiState())
    val uiState: StateFlow<RecycleBinUiState> = _uiState.asStateFlow()

    val gridColumns: StateFlow<Int> = getDefaultGridColumnsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    fun setGridColumns(columns: Int) {
        viewModelScope.launch {
            setDefaultGridColumnsUseCase(columns.coerceIn(2, 5))
        }
    }

    init {
        loadRecycleBin()
    }

    private fun loadRecycleBin() {
        viewModelScope.launch {
            getRecycleBinItemsUseCase().collect { items ->
                _uiState.update { it.copy(items = items, isLoading = false) }
            }
        }
    }

    fun toggleSelection(id: String) {
        _uiState.update { current ->
            val updated = if (id in current.selectedItemIds) {
                current.selectedItemIds - id
            } else {
                current.selectedItemIds + id
            }
            current.copy(selectedItemIds = updated)
        }
    }

    fun selectAll() {
        _uiState.update { current ->
            val allIds = current.items.map { it.id }.toSet()
            val newSelected = if (current.selectedItemIds.containsAll(allIds)) emptySet() else allIds
            current.copy(selectedItemIds = newSelected)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedItemIds = emptySet()) }
    }

    fun restoreItem(item: RecycleBinItem) {
        viewModelScope.launch {
            val result = restoreFromRecycleBinUseCase(item)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(infoMessage = "${item.originalName} moved back to main gallery")
                }
            } else {
                _uiState.update {
                    it.copy(infoMessage = "Failed to restore: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun restoreSelected() {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return

        viewModelScope.launch {
            var restoredCount = 0
            for (item in selected) {
                val res = restoreFromRecycleBinUseCase(item)
                if (res.isSuccess) restoredCount++
            }
            _uiState.update {
                it.copy(
                    selectedItemIds = emptySet(),
                    infoMessage = "$restoredCount item(s) moved back to main gallery"
                )
            }
        }
    }

    fun deleteItemPermanently(item: RecycleBinItem) {
        requestPermanentDelete(listOf(item))
    }

    fun deleteSelectedPermanently() {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        requestPermanentDelete(selected)
    }

    fun emptyRecycleBin() {
        val allItems = _uiState.value.items
        if (allItems.isEmpty()) return
        requestPermanentDelete(allItems)
    }

    private fun requestPermanentDelete(items: List<RecycleBinItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val intentSender = getPendingPermanentDeleteIntentSenderUseCase(items)
            if (intentSender != null) {
                _uiState.update {
                    it.copy(
                        pendingDeleteIntentSender = intentSender,
                        pendingDeleteItems = items
                    )
                }
            } else {
                val result = deletePermanentlyUseCase(items)
                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(
                            selectedItemIds = it.selectedItemIds - items.map { item -> item.id }.toSet(),
                            infoMessage = if (items.size == 1) "${items.first().originalName} deleted permanently" else "${items.size} item(s) permanently deleted"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(infoMessage = "Failed to delete permanently")
                    }
                }
            }
        }
    }

    fun onSystemDeleteConfirmed() {
        val items = _uiState.value.pendingDeleteItems
        viewModelScope.launch {
            finalizePermanentDeleteUseCase(items)
            _uiState.update {
                it.copy(
                    pendingDeleteIntentSender = null,
                    pendingDeleteItems = emptyList(),
                    selectedItemIds = it.selectedItemIds - items.map { item -> item.id }.toSet(),
                    infoMessage = if (items.size == 1) "Deleted permanently" else "${items.size} item(s) permanently deleted"
                )
            }
        }
    }

    fun onSystemDeleteCancelled() {
        _uiState.update {
            it.copy(
                pendingDeleteIntentSender = null,
                pendingDeleteItems = emptyList()
            )
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }
}
