package com.example.smartcattle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartcattle.data.model.SensorData
import com.example.smartcattle.data.repository.CattleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CattleViewModel(private val repository: CattleRepository) : ViewModel() {
    
    private val _latestData = MutableStateFlow<SensorData?>(null)
    val latestData: StateFlow<SensorData?> = _latestData

    init {
        repository.startListening("CATTLE-001")
        
        viewModelScope.launch {
            repository.liveData.collect { jsonString ->
                // Parse JSON from WebSocket and update _latestData
            }
        }
    }
}
