package com.example.smartcattle.data.api

import com.example.smartcattle.data.model.SensorData
import retrofit2.http.GET
import retrofit2.http.Path

interface CattleApi {
    @GET("/api/cattle/{id}/latest")
    suspend fun getLatestReading(@Path("id") id: String): SensorData
}
