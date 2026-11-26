package com.ajx.botforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ajx.botforge.ui.data.BotForgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 机器人列表 ViewModel。
 * 所有写操作完成后会重新拉取列表，保证界面状态与磁盘一致，
 * 不做本地乐观更新，避免与 Config 层归一化（如 intents 修正）产生分歧。
 */
class BotListViewModel(
    private val repo: BotForgeRepository = BotForgeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(BotListState())
    val state: StateFlow<BotListState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val list = repo.bots()
                _state.update { it.copy(loading = false, bots = list) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "读取配置失败")
                }
            }
        }
    }

    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repo.setBotEnabled(id, enabled)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "保存失败") }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteBot(id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "删除失败") }
            }
        }
    }
}