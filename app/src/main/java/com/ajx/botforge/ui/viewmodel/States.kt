package com.ajx.botforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ajx.botforge.data.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bot 列表页状态。 */
data class BotListState(
    val loading: Boolean = true,
    val bots: List<Config.Bot> = emptyList(),
    val error: String? = null
) {
    val enabledCount: Int get() = bots.count { it.enabled }
}

/** 群配置编辑页状态。 */
data class GroupListState(
    val loading: Boolean = true,
    val botName: String = "",
    val groups: List<Config.GroupCfg> = emptyList(),
    val error: String? = null
)

/** 人设列表状态。 */
data class PersonaListState(
    val loading: Boolean = true,
    val personas: List<Config.Persona> = emptyList(),
    val usage: Map<String, Int> = emptyMap(),
    val error: String? = null
)

/** 模型列表状态。 */
data class ModelListState(
    val loading: Boolean = true,
    val models: List<Config.Model> = emptyList(),
    val usage: Map<String, Int> = emptyMap(),
    val error: String? = null
)