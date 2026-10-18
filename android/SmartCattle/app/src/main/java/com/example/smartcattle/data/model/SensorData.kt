package com.example.smartcattle.data.model

data class HealthStatus(
    val spo2: String = "unknown",
    val heart_rate: String = "unknown",
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
    val heart_rate: Int?,
    val temperature: Float?,
    val humidity: Float?,
    val memsX: Float?,
    val memsY: Float?,
    val memsZ: Float?,
    val ph: Float?,
    val ldr: Int?,
    val fallDetected: Boolean = false,
    val health: HealthStatus = HealthStatus(),
    val stepCountToday: Int? = null,
    val stepCountLifetime: Int? = null,
    val accelerationX: Float? = null,
    val accelerationY: Float? = null,
    val accelerationZ: Float? = null,
    val accelerometerValid: Boolean = false,
    val heartRateValid: Boolean = false,
    val spo2Valid: Boolean = false,
    val temperatureValid: Boolean = false,
    val phValid: Boolean = false,
    val ldrValid: Boolean = false,
    val deviceConnected: Boolean = true
) {
    val overallStatus: String get() = if (health.overall == "normal") "Healthy" else "At Risk"
}

data class HistoryRow(
    val timestamp: String,
    val spo2: Int?,
    val heart_rate: Int?,
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
