package com.example.smartcattle.data.model

data class SensorData(
    val cattleId: String,
    val spo2: Int,
    val bpm: Int,
    val temperature: Float,
    val memsX: Float,
    val ph: Float,
    val ldr: Int,
    val overallStatus: String
)
