package com.example.smartcattle.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Format raw alert timestamps (ISO-8601, UTC, SQL datetime, epoch, or empty)
 * into a clear, human-readable real date and time: "dd MMM yyyy, hh:mm:ss a"
 * (e.g., "04 Nov 2026, 09:25:30 PM") using the device's local timezone.
 */
fun formatAlertDateTime(rawTimestamp: String?): String {
    val displayFormat = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
    if (rawTimestamp.isNullOrBlank()) {
        return displayFormat.format(Date())
    }

    val trimmed = rawTimestamp.trim()

    // Already in readable date+time format
    if (trimmed.contains(",") && (trimmed.endsWith("AM", ignoreCase = true) || trimmed.endsWith("PM", ignoreCase = true))) {
        return trimmed
    }

    // Epoch timestamp
    trimmed.toLongOrNull()?.let { epoch ->
        val millis = if (epoch < 100_000_000_000L) epoch * 1000L else epoch
        return displayFormat.format(Date(millis))
    }

    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSSSSS",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )

    for (pattern in patterns) {
        try {
            val parser = SimpleDateFormat(pattern, Locale.US)
            val hasTz = pattern.contains("Z") || pattern.contains("XXX") || pattern.contains("'Z'") ||
                    trimmed.endsWith("Z", ignoreCase = true) ||
                    trimmed.matches(Regex(".*[+-]\\d{2}:?\\d{2}$"))
            if (hasTz) {
                parser.timeZone = TimeZone.getTimeZone("UTC")
            } else {
                // Backend timestamps without explicit offset are stored in UTC
                parser.timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(trimmed)
            if (date != null) {
                return displayFormat.format(date)
            }
        } catch (_: Exception) {}
    }

    // Android O+ java.time fallback
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        try {
            val instant = java.time.Instant.parse(trimmed)
            val zdt = instant.atZone(java.time.ZoneId.systemDefault())
            val formatter = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
            return zdt.format(formatter)
        } catch (_: Exception) {}
        try {
            val ldt = java.time.LocalDateTime.parse(trimmed)
            val zdt = ldt.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.systemDefault())
            val formatter = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
            return zdt.format(formatter)
        } catch (_: Exception) {}
    }

    return trimmed
}

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


// ── Step Specific Domain Models ──

data class StepReading(
    val id: Int = 0,
    val cattle_id: String = "",
    val device_id: String? = null,
    val steps_today: Int = 0,
    val total_steps: Int = 0,
    val step_increment: Int = 0,
    val step_sensor_status: String = "active",
    val step_algorithm_version: String = "1.0",
    val measured_at: String = "",
    val created_at: String = "",
    val is_simulated: Boolean = false
) {
    val cattleId: String get() = cattle_id
    val stepsToday: Int get() = steps_today
    val totalSteps: Int get() = total_steps
    val stepIncrement: Int get() = step_increment
    val sensorStatus: String get() = step_sensor_status
    val measuredAt: String get() = measured_at
}

data class StepStatistics(
    val cattle_id: String = "",
    val steps_today: Int = 0,
    val total_steps: Int = 0,
    val daily_average: Float = 0f,
    val hourly_average: Float = 0f,
    val peak_hour: Int? = null,
    val activity_status: String = "normal",
    val step_sensor_status: String = "active",
    val last_updated: String? = null
) {
    val cattleId: String get() = cattle_id
    val stepsToday: Int get() = steps_today
    val totalSteps: Int get() = total_steps
    val dailyAverage: Float get() = daily_average
    val hourlyAverage: Float get() = hourly_average
    val activityStatus: String get() = activity_status
    val lastUpdated: String? get() = last_updated
}

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
