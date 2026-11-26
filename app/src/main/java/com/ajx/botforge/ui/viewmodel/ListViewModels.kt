package com.ajx.botforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ajx.botforge.ui.data.BotForgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 人设列表 ViewModel。 */
class PersonaListViewModel(
    private val repo: BotForgeRepository = BotForgeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(PersonaListState())
    val state: StateFlow<PersonaListState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val personas = repo.personas()
                // 引用统计：有多少机器人绑定了该人设
                val bots = repo.bots()
                val usage = personas.associate { p -> p.id to bots.count { it.personaId == p.id } }
                _state.update { it.copy(loading = false, personas = personas, usage = usage) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "读取失败") }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repo.deletePersona(id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "删除失败") }
            }
        }
    }
}

/** 模型列表 ViewModel。 */
class ModelListViewModel(
    private val repo: BotForgeRepository = BotForgeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(ModelListState())
    val state: StateFlow<ModelListState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val models = repo.models()
                val bots = repo.bots()
                val usage = models.associate { m -> m.id to bots.count { it.modelId == m.id } }
                _state.update { it.copy(loading = false, models = models, usage = usage) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "读取失败") }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteModel(id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "删除失败") }
            }
        }
    }
}