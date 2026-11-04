package com.example.smartcattle.data.api

import com.example.smartcattle.data.model.AlertRow
import com.example.smartcattle.data.model.formatAlertDateTime
import com.example.smartcattle.data.model.HealthStatus
import com.example.smartcattle.data.model.HistoryRow
import com.example.smartcattle.data.model.SensorData
import com.example.smartcattle.data.model.StepReading
import com.example.smartcattle.data.model.StepStatistics
import com.example.smartcattle.data.model.WeightReading
import com.example.smartcattle.data.model.WeightStatistics
import com.example.smartcattle.data.model.WeightRecordRequest
import com.example.smartcattle.data.model.TareRequest
import com.example.smartcattle.data.model.CalibrateRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response

// Raw API response shapes that mirror the backend JSON exactly
data class ApiHealthResponse(
    val spo2: String = "unknown",
    val heart_rate: String = "unknown",
    val temperature: String = "unknown",
    val mems: String = "unknown",
    val ph: String = "unknown",
    val ldr: String = "unknown",
    val weight: String = "unknown",
    val overall: String = "unknown"
)

data class ApiLatestResponse(
    val cattle_id: String,
    val timestamp: String,
    val spo2: Int?,
    val heart_rate: Int?,
    val temperature: Float?,
    val humidity: Float?,
    val mems_x: Float?,
    val mems_y: Float?,
    val mems_z: Float?,
    val acceleration_x: Float? = null,
    val acceleration_y: Float? = null,
    val acceleration_z: Float? = null,
    val step_count_today: Int? = null,
    val step_count_lifetime: Int? = null,
    val accelerometer_valid: Boolean = false,
    val heart_rate_valid: Boolean = false,
    val spo2_valid: Boolean = false,
    val temperature_valid: Boolean = false,
    val ph_valid: Boolean = false,
    val ldr_valid: Boolean = false,
    val device_connected: Boolean = true,
    val ph: Float?,
    val ldr: Int?,
    val fall_detected: Boolean = false,
    val health: ApiHealthResponse?,
    // Weight fields
    val body_weight_kg: Float? = null,
    val weight_raw: Int? = null,
    val weight_stable: Boolean = false,
    val weight_calibrated: Boolean = false,
    val weight_valid: Boolean = false,
    val weight_sensor_status: String? = null
)

data class ApiHistoryResponse(
    val timestamp: String,
    val spo2: Int?,
    val heart_rate: Int?,
    val temperature: Float?,
    val humidity: Float?,
    val mems_x: Float?,
    val mems_y: Float?,
    val mems_z: Float?,
    val ph: Float?,
    val ldr: Int?,
    val fall_detected: Boolean = false,
    val health: ApiHealthResponse?,
    val body_weight_kg: Float? = null,
    val weight_stable: Boolean = false
)

data class ApiAlertResponse(
    val cattle_id: String,
    val cattle_name: String,
    val timestamp: String,
    val fall_detected: Boolean = false,
    val details: List<String> = emptyList()
)

data class ApiLatestHealth(
    val timestamp: String?,
    val spo2: Int?,
    val heart_rate: Int?,
    val temperature: Float?,
    val overall_status: String?,
    val humidity: Float? = null,
    val ph: Float? = null,
    val ldr: Int? = null,
    val body_weight_kg: Float? = null,
    val weight_stable: Boolean? = null,
    val weight_calibrated: Boolean? = null,
    val weight_valid: Boolean? = null,
    val weight_sensor_status: String? = null
)

data class ApiLatestMilk(
    val quantity: Float?,
    val unit: String?,
    val date: String?
)

data class ApiLatestFeed(
    val quantity: Float?,
    val unit: String?,
    val date: String?
)

data class ApiLatestActivity(
    val steps: Int?,
    val date: String?
)

data class ApiCattleResponse(
    val cattle_id: String,
    val name: String,
    val breed: String?,
    val age: String?,
    val gender: String? = null,
    val date_of_birth: String? = null,
    val weight: Float? = null,
    val status: String?,
    val latest_health: ApiLatestHealth?,
    val latest_milk: ApiLatestMilk?,
    val latest_feed: ApiLatestFeed?,
    val latest_activity: ApiLatestActivity?,
    val active_alerts_count: Int?
)

data class DashboardSummaryResponse(
    val total_cattle: Int,
    val active_alerts: Int,
    val abnormal_cattle: Int,
    val system_status: String
)

data class MilkProductionResponse(
    val cattle_id: String,
    val quantity: Float,
    val unit: String,
    val date: String
)

data class FeedConsumptionResponse(
    val cattle_id: String,
    val quantity: Float,
    val feed_type: String?,
    val unit: String,
    val date: String
)

data class ActivityResponse(
    val cattle_id: String,
    val steps: Int,
    val source: String,
    val date: String
)

interface CattleApi {
    @GET("/api/cows")
    suspend fun getCattle(): List<ApiCattleResponse>

    @GET("/api/cows/{id}")
    suspend fun getCowById(@Path("id") id: String): ApiCattleResponse

    @GET("/api/cattle/{id}/latest")
    suspend fun getLatest(@Path("id") id: String): ApiLatestResponse

    @GET("/api/cattle/{id}/history")
    suspend fun getHistory(@Path("id") id: String, @Query("limit") limit: Int = 30): List<ApiHistoryResponse>

    @GET("/api/alerts")
    suspend fun getAlerts(): List<ApiAlertResponse>

    @GET("/api/dashboard/summary")
    suspend fun getDashboardSummary(): DashboardSummaryResponse

    @GET("/api/milk/{id}")
    suspend fun getMilkProduction(@Path("id") id: String): List<MilkProductionResponse>

    @GET("/api/feed/{id}")
    suspend fun getFeedConsumption(@Path("id") id: String): List<FeedConsumptionResponse>

    @GET("/api/activity/{id}")
    suspend fun getActivity(@Path("id") id: String): List<ActivityResponse>

    @DELETE("/api/cows/{id}")
    suspend fun deleteCow(@Path("id") id: String): Response<Unit>


    // ── Steps API Endpoints ──
    @GET("/api/v1/cattle/{id}/steps/latest")
    suspend fun getLatestSteps(@Path("id") id: String): StepReading

    @GET("/api/v1/cattle/{id}/steps/history")
    suspend fun getStepsHistory(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50
    ): List<StepReading>

    @GET("/api/v1/cattle/{id}/steps/statistics")
    suspend fun getStepStatistics(@Path("id") id: String): StepStatistics

    // ── Weight API Endpoints ──
    @GET("/api/v1/cattle/{id}/weight/latest")
    suspend fun getLatestWeight(@Path("id") id: String): WeightReading

    @GET("/api/v1/cattle/{id}/weight/history")
    suspend fun getWeightHistory(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("skip") skip: Int = 0
    ): List<WeightReading>

    @GET("/api/v1/cattle/{id}/weight/statistics")
    suspend fun getWeightStatistics(@Path("id") id: String): WeightStatistics

    @POST("/api/v1/sensors/weight")
    suspend fun recordWeight(@Body req: WeightRecordRequest): WeightReading

    @POST("/api/v1/sensors/weight/tare")
    suspend fun tareScale(@Body req: TareRequest = TareRequest()): Response<Unit>

    @POST("/api/v1/sensors/weight/calibrate")
    suspend fun calibrateScale(@Body req: CalibrateRequest): Response<Unit>
}

// Mappers — convert raw API shapes to clean domain models
fun ApiLatestResponse.toSensorData() = SensorData(
    cattleId = cattle_id,
    timestamp = formatAlertDateTime(timestamp),
    spo2 = spo2,
    heart_rate = heart_rate,
    temperature = temperature,
    humidity = humidity,
    memsX = mems_x ?: acceleration_x,
    memsY = mems_y ?: acceleration_y,
    memsZ = mems_z ?: acceleration_z,
    stepCountToday = step_count_today,
    stepCountLifetime = step_count_lifetime,
    accelerationX = acceleration_x ?: mems_x,
    accelerationY = acceleration_y ?: mems_y,
    accelerationZ = acceleration_z ?: mems_z,
    accelerometerValid = accelerometer_valid,
    heartRateValid = heart_rate_valid,
    spo2Valid = spo2_valid,
    temperatureValid = temperature_valid,
    phValid = ph_valid,
    ldrValid = ldr_valid,
    deviceConnected = device_connected,
    ph = ph,
    ldr = ldr,
    fallDetected = fall_detected,
    health = health?.let { HealthStatus(it.spo2, it.heart_rate, it.temperature, it.mems, it.ph, it.ldr, it.weight, it.overall) } ?: HealthStatus(),
    bodyWeightKg = body_weight_kg,
    weightRaw = weight_raw,
    weightStable = weight_stable,
    weightCalibrated = weight_calibrated,
    weightValid = weight_valid,
    weightSensorStatus = weight_sensor_status
)

fun ApiHistoryResponse.toHistoryRow() = HistoryRow(
    timestamp = formatAlertDateTime(timestamp),
    spo2 = spo2,
    heart_rate = heart_rate,
    temperature = temperature,
    humidity = humidity,
    memsX = mems_x,
    memsY = mems_y,
    memsZ = mems_z,
    ph = ph,
    ldr = ldr,
    fallDetected = fall_detected,
    health = health?.let { HealthStatus(it.spo2, it.heart_rate, it.temperature, it.mems, it.ph, it.ldr, it.weight, it.overall) } ?: HealthStatus(),
    bodyWeightKg = body_weight_kg,
    weightStable = weight_stable
)

fun ApiAlertResponse.toAlertRow() = AlertRow(
    cattleId = cattle_id,
    cattleName = cattle_name,
    timestamp = formatAlertDateTime(timestamp),
    fallDetected = fall_detected,
    details = details
)
