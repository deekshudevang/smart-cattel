package com.example.smartcattle.data.api

import com.example.smartcattle.data.model.AlertRow
import com.example.smartcattle.data.model.HealthStatus
import com.example.smartcattle.data.model.HistoryRow
import com.example.smartcattle.data.model.SensorData
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// Raw API response shapes that mirror the backend JSON exactly
data class ApiHealthResponse(
    val spo2: String = "unknown",
    val bpm: String = "unknown",
    val temperature: String = "unknown",
    val mems: String = "unknown",
    val ph: String = "unknown",
    val ldr: String = "unknown",
    val overall: String = "unknown"
)

data class ApiLatestResponse(
    val cattle_id: String,
    val timestamp: String,
    val spo2: Int,
    val bpm: Int,
    val temperature: Float,
    val humidity: Float,
    val mems_x: Float,
    val mems_y: Float,
    val mems_z: Float,
    val ph: Float,
    val ldr: Int,
    val fall_detected: Boolean = false,
    val health: ApiHealthResponse
)

data class ApiHistoryResponse(
    val timestamp: String,
    val spo2: Int,
    val bpm: Int,
    val temperature: Float,
    val humidity: Float,
    val mems_x: Float,
    val mems_y: Float,
    val mems_z: Float,
    val ph: Float,
    val ldr: Int,
    val fall_detected: Boolean = false,
    val health: ApiHealthResponse
)

data class ApiAlertResponse(
    val cattle_id: String,
    val cattle_name: String,
    val timestamp: String,
    val fall_detected: Boolean = false,
    val details: List<String> = emptyList()
)

data class ApiCattleResponse(
    val cattle_id: String,
    val name: String,
    val status: String
)

interface CattleApi {
    @GET("/api/cattle")
    suspend fun getCattle(): List<ApiCattleResponse>

    @GET("/api/cattle/{id}/latest")
    suspend fun getLatest(@Path("id") id: String): ApiLatestResponse

    @GET("/api/cattle/{id}/history")
    suspend fun getHistory(@Path("id") id: String, @Query("limit") limit: Int = 30): List<ApiHistoryResponse>

    @GET("/api/alerts")
    suspend fun getAlerts(): List<ApiAlertResponse>
}

// Mappers — convert raw API shapes to clean domain models
fun ApiLatestResponse.toSensorData() = SensorData(
    cattleId = cattle_id,
    timestamp = timestamp,
    spo2 = spo2,
    bpm = bpm,
    temperature = temperature,
    humidity = humidity,
    memsX = mems_x,
    memsY = mems_y,
    memsZ = mems_z,
    ph = ph,
    ldr = ldr,
    fallDetected = fall_detected,
    health = HealthStatus(health.spo2, health.bpm, health.temperature, health.mems, health.ph, health.ldr, health.overall)
)

fun ApiHistoryResponse.toHistoryRow() = HistoryRow(
    timestamp = timestamp,
    spo2 = spo2,
    bpm = bpm,
    temperature = temperature,
    humidity = humidity,
    memsX = mems_x,
    memsY = mems_y,
    memsZ = mems_z,
    ph = ph,
    ldr = ldr,
    fallDetected = fall_detected,
    health = HealthStatus(health.spo2, health.bpm, health.temperature, health.mems, health.ph, health.ldr, health.overall)
)

fun ApiAlertResponse.toAlertRow() = AlertRow(
    cattleId = cattle_id,
    cattleName = cattle_name,
    timestamp = timestamp,
    fallDetected = fall_detected,
    details = details
)
