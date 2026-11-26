package com.ajx.botforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ajx.botforge.data.Logger
import com.ajx.botforge.ui.data.BotForgeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 日志页状态。 */
data class LogState(
    val lines: List<Logger.Line> = emptyList(),
    val tailing: Boolean = true,
    val filterLevel: Int = -1,   // -1 全部, 0 info, 1 warn, 2 error
    val error: String? = null
) {
    val shown: List<Logger.Line>
        get() = if (filterLevel < 0) lines else lines.filter { it.level == filterLevel }
}

/**
 * 日志页 ViewModel。
 *
 * 用 StateFlow 承载日志快照，轮询间隔 2s（与旧版 scheduleWithFixedDelay 一致），
 * 跟随开关关闭时停止刷新，避免无意义重组。
 */
class LogViewModel(
    private val repo: BotForgeRepository = BotForgeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(LogState())
    val state: StateFlow<LogState> = _state.asStateFlow()

    private var poller: Job? = null

    init {
        reload()
        startPolling()
    }

    fun reload() {
        viewModelScope.launch {
            try {
                val list = Logger.snapshot()
                _state.update { it.copy(lines = list) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "读取日志失败") }
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            repo.clearLogs()
            _state.update { it.copy(lines = emptyList()) }
        }
    }

    fun toggleTailing() {
        val next = !_state.value.tailing
        _state.update { it.copy(tailing = next) }
        if (next) startPolling() else stopPolling()
    }

    fun setFilter(level: Int) {
        _state.update { it.copy(filterLevel = level) }
    }

    private fun startPolling() {
        stopPolling()
        poller = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                if (!_state.value.tailing) continue
                try {
                    val list = Logger.snapshot()
                    _state.update { it.copy(lines = list) }
                } catch (_: Exception) {
                    // 日志读取失败不打断轮询
                }
            }
        }
    }

    private fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}