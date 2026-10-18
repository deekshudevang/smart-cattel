package com.example.smartcattle.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cows")
data class CowEntity(
    @PrimaryKey val cattleId: String,
    val name: String,
    val breed: String? = null,
    val age: String? = null,
    val gender: String? = null,
    val dob: String? = null,
    val notes: String? = null,
    val photoPath: String? = null,
    val overallStatus: String? = "Healthy",
    val latestSpo2: Int? = null,
    val latestHeartRate: Int? = null,
    val latestTemp: Float? = null,
    val latestPh: Float? = null,
    val latestLdr: Int? = null,
    val latestStepsToday: Int? = null,
    val latestStepsLifetime: Int? = null,
    val latestAccelX: Float? = null,
    val latestAccelY: Float? = null,
    val latestAccelZ: Float? = null,
    val latestTimestamp: String? = null,
    val lastSyncTimestamp: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

fun CowEntity.toCowProfile(): com.example.smartcattle.CowProfile {
    return com.example.smartcattle.CowProfile(
        cattleId = cattleId,
        name = name,
        breed = breed,
        age = age,
        gender = gender,
        dob = dob,
        notes = notes,
        status = overallStatus,
        latestSpo2 = latestSpo2,
        latestHeartRate = latestHeartRate,
        latestTemp = latestTemp,
        latestPh = latestPh,
        latestLdr = latestLdr,
        latestStepsToday = latestStepsToday,
        latestStepsLifetime = latestStepsLifetime,
        latestAccelX = latestAccelX,
        latestAccelY = latestAccelY,
        latestAccelZ = latestAccelZ,
        overallStatus = overallStatus,
        latestTimestamp = latestTimestamp,
        latestActivitySteps = latestStepsToday,
        photoPath = photoPath
    )
}

fun com.example.smartcattle.CowProfile.toEntity(): CowEntity {
    return CowEntity(
        cattleId = cattleId,
        name = name,
        breed = breed,
        age = age,
        gender = gender,
        dob = dob,
        notes = notes,
        photoPath = photoPath,
        overallStatus = overallStatus ?: status ?: "Healthy",
        latestSpo2 = latestSpo2,
        latestHeartRate = latestHeartRate,
        latestTemp = latestTemp,
        latestPh = latestPh,
        latestLdr = latestLdr,
        latestStepsToday = latestStepsToday ?: latestActivitySteps,
        latestStepsLifetime = latestStepsLifetime,
        latestAccelX = latestAccelX,
        latestAccelY = latestAccelY,
        latestAccelZ = latestAccelZ,
        latestTimestamp = latestTimestamp,
        lastSyncTimestamp = System.currentTimeMillis(),
        isDeleted = false
    )
}

