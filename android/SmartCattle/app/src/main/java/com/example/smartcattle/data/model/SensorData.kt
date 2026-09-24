package com.example.smartcattle.data.model

data class HealthStatus(
    val spo2: String = "unknown",
    val bpm: String = "unknown",
    val temperature: String = "unknown",
    val mems: String = "unknown",
    val ph: String = "unknown",
    val ldr: String = "unknown",
    val overall: String = "unknown"
)

data class SensorData(
    val cattleId: String,
    val timestamp: String = "",
    val spo2: Int?,
    val bpm: Int?,
    val temperature: Float?,
    val humidity: Float?,
    val memsX: Float?,
    val memsY: Float?,
    val memsZ: Float?,
    val ph: Float?,
    val ldr: Int?,
    val fallDetected: Boolean = false,
    val health: HealthStatus = HealthStatus()
) {
    val overallStatus: String get() = if (health.overall == "normal") "Healthy" else "At Risk"
}

data class HistoryRow(
    val timestamp: String,
    val spo2: Int?,
    val bpm: Int?,
    val temperature: Float?,
    val humidity: Float?,
    val memsX: Float?,
    val memsY: Float?,
    val memsZ: Float?,
    val ph: Float?,
    val ldr: Int?,
    val fallDetected: Boolean,
    val health: HealthStatus
)

data class AlertRow(
    val cattleId: String,
    val cattleName: String,
    val timestamp: String,
    val fallDetected: Boolean,
    val details: List<String>
)
