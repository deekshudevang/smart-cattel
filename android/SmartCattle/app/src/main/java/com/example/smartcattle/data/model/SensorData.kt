package com.example.smartcattle.data.model

data class HealthStatus(
    val spo2: String = "unknown",
    val heart_rate: String = "unknown",
    val temperature: String = "unknown",
    val mems: String = "unknown",
    val ph: String = "unknown",
    val ldr: String = "unknown",
    val weight: String = "unknown",
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
    val deviceConnected: Boolean = true,
    // Weight / Load Cell (HX711)
    val bodyWeightKg: Float? = null,
    val weightRaw: Int? = null,
    val weightStable: Boolean = false,
    val weightCalibrated: Boolean = false,
    val weightValid: Boolean = false,
    val weightSensorStatus: String? = null
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
    val health: HealthStatus,
    val bodyWeightKg: Float? = null,
    val weightStable: Boolean = false
)

data class AlertRow(
    val cattleId: String,
    val cattleName: String,
    val timestamp: String,
    val fallDetected: Boolean,
    val details: List<String>
)

// ── Weight Specific Domain Models ──

data class WeightReading(
    val id: Int = 0,
    val cattle_id: String = "",
    val device_id: String? = null,
    val weight_kg: Float = 0f,
    val raw_value: Int? = null,
    val is_stable: Boolean = true,
    val is_calibrated: Boolean = true,
    val measured_at: String = "",
    val created_at: String = "",
    val source: String = "manual",
    val is_simulated: Boolean = false
) {
    val cattleId: String get() = cattle_id
    val weightKg: Float get() = weight_kg
    val isStable: Boolean get() = is_stable
    val isCalibrated: Boolean get() = is_calibrated
    val measuredAt: String get() = measured_at
}

data class WeightStatistics(
    val cattle_id: String = "",
    val current_weight_kg: Float? = null,
    val previous_weight_kg: Float? = null,
    val difference_kg: Float? = null,
    val percentage_change: Float? = null,
    val trend: String = "stable", // "gain", "loss", "stable"
    val average_7d: Float? = null,
    val average_30d: Float? = null,
    val last_measured_at: String? = null,
    val is_stable: Boolean = false,
    val is_calibrated: Boolean = false,
    val readings_count: Int = 0
) {
    val cattleId: String get() = cattle_id
    val currentWeightKg: Float? get() = current_weight_kg
    val previousWeightKg: Float? get() = previous_weight_kg
    val differenceKg: Float? get() = difference_kg
    val percentageChange: Float? get() = percentage_change
    val average7d: Float? get() = average_7d
    val average30d: Float? get() = average_30d
    val lastMeasuredAt: String? get() = last_measured_at
    val isStable: Boolean get() = is_stable
    val isCalibrated: Boolean get() = is_calibrated
    val readingsCount: Int get() = readings_count
}

data class WeightRecordRequest(
    val cattle_id: String,
    val device_id: String? = null,
    val weight_kg: Float,
    val raw_value: Int? = null,
    val is_stable: Boolean = true,
    val is_calibrated: Boolean = true,
    val source: String = "scale"
)

data class TareRequest(
    val device_id: String = "SC-001"
)

data class CalibrateRequest(
    val reference_weight_kg: Float,
    val device_id: String = "SC-001"
)
