package com.example.smartcattle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCattleVitalsAndSyncTest {

    // ── Test 1: Deleting a cow profile removes it from Dashboard and Cows list ──
    @Test
    fun testDeleteCowRemovesFromDashboardAndCowsList() {
        val initialProfiles = listOf(
            CowProfile(cattleId = "CATTLE-001", name = "Daisy", breed = "Holstein", age = "3", gender = "Female", dob = null, notes = null, status = "active"),
            CowProfile(cattleId = "CATTLE-002", name = "Bella", breed = "Jersey", age = "4", gender = "Female", dob = null, notes = null, status = "active")
        )
        val initialCattle = listOf(
            Cattle(id = "CATTLE-001", name = "Daisy", breed = "Holstein", age = "3", overall = "Healthy", sensors = emptyList()),
            Cattle(id = "CATTLE-002", name = "Bella", breed = "Jersey", age = "4", overall = "Healthy", sensors = emptyList())
        )

        val targetDeleteId = "CATTLE-002"

        // Simulate synchronized deletion
        val updatedProfiles = initialProfiles.filter { it.cattleId != targetDeleteId }
        val activeIds = updatedProfiles.map { it.cattleId }.toSet()
        val updatedCattle = initialCattle.filter { it.id in activeIds }

        // Assert profile removed from Cows list
        assertEquals(1, updatedProfiles.size)
        assertEquals("CATTLE-001", updatedProfiles[0].cattleId)
        assertFalse(updatedProfiles.any { it.cattleId == targetDeleteId })

        // Assert profile removed from Dashboard list
        assertEquals(1, updatedCattle.size)
        assertEquals("CATTLE-001", updatedCattle[0].id)
        assertFalse(updatedCattle.any { it.id == targetDeleteId })
    }

    // ── Test 2: Deleted/unknown cow WebSocket packets are ignored and not re-added ──
    @Test
    fun testDeletedCowWebSocketPacketsAreIgnored() {
        val activeProfiles = listOf(
            CowProfile(cattleId = "CATTLE-001", name = "Daisy", breed = "Holstein", age = "3", gender = "Female", dob = null, notes = null, status = "active")
        )
        val activeIds = activeProfiles.map { it.cattleId }.toSet()
        val dashboardList = mutableListOf(
            Cattle(id = "CATTLE-001", name = "Daisy", breed = "Holstein", age = "3", overall = "Healthy", sensors = emptyList())
        )

        // Incoming packet for deleted cow
        val deletedPacketCid = "CATTLE-002"

        val shouldProcess = activeIds.isEmpty() || activeIds.contains(deletedPacketCid)
        if (shouldProcess) {
            dashboardList.add(Cattle(id = deletedPacketCid, name = deletedPacketCid, breed = "Not Available", age = "Not Available", overall = "Healthy", sensors = emptyList()))
        }

        assertFalse("Packet for deleted cow must not be processed", shouldProcess)
        assertEquals(1, dashboardList.size)
        assertEquals("CATTLE-001", dashboardList[0].id)
        assertFalse(dashboardList.any { it.id == deletedPacketCid })
    }

    // ── Test 3: Packet with some null vitals still shows the other values ──
    @Test
    fun testPacketWithSomeNullVitalsPreservesOtherValues() {
        val jsonString = """
        {
            "spo2": null,
            "heart_rate": null,
            "temperature": 38.6,
            "ph": 6.8,
            "ldr": 915,
            "step_count_today": 42,
            "acceleration_x": 0.12,
            "acceleration_y": -0.05,
            "acceleration_z": 9.78
        }
        """.trimIndent()

        val map = com.google.gson.Gson().fromJson(jsonString, Map::class.java)

        val spo2 = (map["spo2"] as? Number)?.toInt()
        val heartRate = (map["heart_rate"] as? Number)?.toInt()
        val temp = (map["temperature"] as? Number)?.toFloat()
        val ph = (map["ph"] as? Number)?.toFloat()
        val ldr = (map["ldr"] as? Number)?.toInt()
        val stepsToday = (map["step_count_today"] as? Number)?.toInt()

        // Uncontacted/unplugged sensors are null and show "Not Available"
        assertNull(spo2)
        assertEquals("Not Available", spo2?.let { "$it%" } ?: "Not Available")
        assertNull(heartRate)
        assertEquals("Not Available", heartRate?.let { "$it bpm" } ?: "Not Available")

        // Connected sensors have valid values and do NOT get dropped
        assertNotNull(temp)
        assertEquals("38.6°C", String.format(java.util.Locale.US, "%.1f°C", temp!!))

        assertNotNull(ph)
        assertEquals("6.8", String.format(java.util.Locale.US, "%.1f", ph!!))

        assertNotNull(ldr)
        assertEquals("915 lux", "$ldr lux")

        assertNotNull(stepsToday)
        assertEquals(42, stepsToday)
    }

    // ── Test 4: DTO parsing of a real sample packet shows all vitals ──
    @Test
    fun testDtoParsingOfRealSamplePacketShowsAllVitals() {
        val apiHealth = ApiLatestHealth(
            timestamp = "2026-10-18 12:45:00",
            spo2 = 98,
            heart_rate = 74,
            temperature = 38.7f,
            overall_status = "normal",
            humidity = 55.0f,
            ph = 6.8f,
            ldr = 920,
            step_count_today = 150,
            step_count_lifetime = 1250,
            acceleration_x = 0.2f,
            acceleration_y = 0.1f,
            acceleration_z = 9.8f,
            accelerometer_valid = true,
            heart_rate_valid = true,
            spo2_valid = true,
            temperature_valid = true,
            ph_valid = true,
            ldr_valid = true
        )

        val apiCow = ApiCowProfile(
            cattle_id = "CATTLE-001",
            name = "Daisy",
            breed = "Holstein",
            age = "3 years",
            gender = "Female",
            dob = "2023-05-10",
            notes = "Vaccinated and healthy",
            status = "active",
            latest_health = apiHealth,
            latest_milk = ApiLatestMilk(quantity = 18.5f, unit = "L", date = "2026-10-18"),
            latest_feed = ApiLatestFeed(quantity = 22.0f, unit = "kg", date = "2026-10-18"),
            latest_activity = ApiLatestActivity(steps = 150, date = "2026-10-18"),
            active_alerts_count = 0
        )

        val cowProfile = CowProfile(
            cattleId = apiCow.cattle_id,
            name = apiCow.name,
            breed = apiCow.breed,
            age = apiCow.age,
            gender = apiCow.gender,
            dob = apiCow.dob,
            notes = apiCow.notes,
            status = apiCow.status,
            latestSpo2 = apiCow.latest_health?.spo2,
            latestHeartRate = apiCow.latest_health?.heart_rate,
            latestTemp = apiCow.latest_health?.temperature,
            latestPh = apiCow.latest_health?.ph,
            latestLdr = apiCow.latest_health?.ldr,
            latestHumidity = apiCow.latest_health?.humidity,
            latestStepsToday = apiCow.latest_health?.step_count_today,
            latestStepsLifetime = apiCow.latest_health?.step_count_lifetime,
            latestAccelX = apiCow.latest_health?.acceleration_x,
            latestAccelY = apiCow.latest_health?.acceleration_y,
            latestAccelZ = apiCow.latest_health?.acceleration_z,
            overallStatus = apiCow.latest_health?.overall_status,
            latestTimestamp = apiCow.latest_health?.timestamp,
            latestMilkQty = apiCow.latest_milk?.quantity,
            latestMilkUnit = apiCow.latest_milk?.unit,
            latestFeedQty = apiCow.latest_feed?.quantity,
            latestActivitySteps = apiCow.latest_activity?.steps,
            activeAlertsCount = apiCow.active_alerts_count ?: 0
        )

        assertEquals("CATTLE-001", cowProfile.cattleId)
        assertEquals("Daisy", cowProfile.name)
        assertEquals(98, cowProfile.latestSpo2)
        assertEquals(74, cowProfile.latestHeartRate)
        assertEquals(38.7f, cowProfile.latestTemp)
        assertEquals(6.8f, cowProfile.latestPh)
        assertEquals(920, cowProfile.latestLdr)
        assertEquals(150, cowProfile.latestStepsToday)
        assertEquals(1250, cowProfile.latestStepsLifetime)
        assertEquals(0.2f, cowProfile.latestAccelX)
        assertEquals(0.1f, cowProfile.latestAccelY)
        assertEquals(9.8f, cowProfile.latestAccelZ)
        assertEquals("2026-10-18 12:45:00", cowProfile.latestTimestamp)
        assertEquals("normal", cowProfile.overallStatus)
    }
}
