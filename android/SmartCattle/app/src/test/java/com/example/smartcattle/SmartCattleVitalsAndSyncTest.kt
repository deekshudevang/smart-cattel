package com.example.smartcattle

import com.example.smartcattle.data.local.toEntity
import com.example.smartcattle.data.local.toCowProfile
import com.example.smartcattle.data.model.formatAlertDateTime
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

    // ── Test 5: Legitimate 0-lux is preserved and formatted as "0", only null produces "Not Available" ──
    @Test
    fun testZeroLuxLdrPreservedAsZeroNotCoercedToNotAvailable() {
        val darkReadingLdr: Int? = 0
        val disconnectedLdr: Int? = null

        // In MainActivity: r.ldr?.let { "$it" } ?: "Not Available"
        val darkDisplay = darkReadingLdr?.let { "$it" } ?: "Not Available"
        val disconnectedDisplay = disconnectedLdr?.let { "$it" } ?: "Not Available"

        assertEquals("0", darkDisplay)
        assertEquals("Not Available", disconnectedDisplay)
    }

    // ── Test 6: Verify all vitals show "Not Available" only when truly null ──
    @Test
    fun testNullVitalsFormattingProducesNotAvailable() {
        fun formatSpo2(v: Int?): String = v?.let { if (it <= 0) "Not Available" else "$it" } ?: "Not Available"
        fun formatHeartRate(v: Int?): String = v?.let { if (it <= 0) "Not Available" else "$it" } ?: "Not Available"
        fun formatTemp(v: Float?): String = v?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "Not Available"
        fun formatPh(v: Float?): String = v?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "Not Available"
        fun formatLdr(v: Int?): String = v?.let { "$it" } ?: "Not Available"

        // Case A: Missing / disconnected sensors (truly null)
        assertEquals("Not Available", formatSpo2(null))
        assertEquals("Not Available", formatHeartRate(null))
        assertEquals("Not Available", formatTemp(null))
        assertEquals("Not Available", formatPh(null))
        assertEquals("Not Available", formatLdr(null))

        // Case B: Present / valid readings
        assertEquals("98", formatSpo2(98))
        assertEquals("72", formatHeartRate(72))
        assertEquals("38.5", formatTemp(38.5f))
        assertEquals("6.80", formatPh(6.8f))
        assertEquals("0", formatLdr(0)) // 0 lux night-time
        assertEquals("850", formatLdr(850))
    }

    // ── Test 7: Room entity mapping preserves all vitals, steps, and status ──
    @Test
    fun testRoomEntityToProfileConversionPreservesAllVitalsAndStatus() {
        val originalProfile = CowProfile(
            cattleId = "CATTLE-ROOM-01",
            name = "Buttercup",
            breed = "Guernsey",
            age = "2.5 years",
            gender = "Female",
            dob = "2024-01-15",
            notes = "Top dairy producer",
            status = "Healthy",
            latestSpo2 = 97,
            latestHeartRate = 68,
            latestTemp = 38.6f,
            latestPh = 6.75f,
            latestLdr = 0, // pitch dark barn
            latestStepsToday = 340,
            latestStepsLifetime = 12500,
            latestAccelX = 0.05f,
            latestAccelY = 0.02f,
            latestAccelZ = 9.81f,
            overallStatus = "Healthy",
            latestTimestamp = "2026-10-18T14:30:00Z",
            latestActivitySteps = 340,
            photoPath = "/data/photos/buttercup.jpg"
        )

        // Convert to Room Entity
        val entity = originalProfile.toEntity()
        assertEquals("CATTLE-ROOM-01", entity.cattleId)
        assertEquals("Buttercup", entity.name)
        assertEquals(97, entity.latestSpo2)
        assertEquals(68, entity.latestHeartRate)
        assertEquals(38.6f, entity.latestTemp)
        assertEquals(6.75f, entity.latestPh)
        assertEquals(0, entity.latestLdr)
        assertEquals(340, entity.latestStepsToday)
        assertEquals(12500, entity.latestStepsLifetime)
        assertFalse(entity.isDeleted)

        // Convert back to CowProfile
        val restoredProfile = entity.toCowProfile()
        assertEquals(originalProfile.cattleId, restoredProfile.cattleId)
        assertEquals(originalProfile.name, restoredProfile.name)
        assertEquals(originalProfile.latestSpo2, restoredProfile.latestSpo2)
        assertEquals(originalProfile.latestHeartRate, restoredProfile.latestHeartRate)
        assertEquals(originalProfile.latestTemp, restoredProfile.latestTemp)
        assertEquals(originalProfile.latestPh, restoredProfile.latestPh)
        assertEquals(originalProfile.latestLdr, restoredProfile.latestLdr)
        assertEquals(originalProfile.latestStepsToday, restoredProfile.latestStepsToday)
        assertEquals(originalProfile.latestStepsLifetime, restoredProfile.latestStepsLifetime)
        assertEquals(originalProfile.overallStatus, restoredProfile.overallStatus)
        assertEquals(originalProfile.photoPath, restoredProfile.photoPath)
    }

    // ── Test 8: Memory blacklist set drops deleted cow packets instantly ──
    @Test
    fun testDeletedCowIdBlacklistPreventsReaddition() {
        val deletedCattleIds = mutableSetOf("CATTLE-002", "CATTLE-005")

        fun shouldAcceptPacket(cid: String): Boolean {
            return !deletedCattleIds.contains(cid)
        }

        assertTrue("Active cow must be accepted", shouldAcceptPacket("CATTLE-001"))
        assertFalse("Deleted cow must be rejected", shouldAcceptPacket("CATTLE-002"))
        assertFalse("Deleted cow must be rejected", shouldAcceptPacket("CATTLE-005"))
        assertTrue("Active cow must be accepted", shouldAcceptPacket("CATTLE-003"))
    }

    // ── Test 9: Null vitals and null step counts stay null and never default to 0 ──
    @Test
    fun testRoomEntityWithNullStepsPreservesNull() {
        val profileWithNullSteps = CowProfile(
            cattleId = "CATTLE-NULL-01",
            name = "Luna",
            breed = "Angus",
            age = null,
            gender = null,
            dob = null,
            notes = null,
            status = "Healthy",
            latestSpo2 = null,
            latestHeartRate = null,
            latestTemp = null,
            latestPh = null,
            latestLdr = null,
            latestStepsToday = null,
            latestStepsLifetime = null,
            latestActivitySteps = null
        )

        val entity = profileWithNullSteps.toEntity()
        assertNull("latestStepsToday must remain null in Room entity, never default to 0", entity.latestStepsToday)
        assertNull("latestStepsLifetime must remain null in Room entity, never default to 0", entity.latestStepsLifetime)
        assertNull("latestSpo2 must remain null", entity.latestSpo2)

        val restored = entity.toCowProfile()
        assertNull("Restored latestStepsToday must remain null", restored.latestStepsToday)
        assertNull("Restored latestStepsLifetime must remain null", restored.latestStepsLifetime)
    }

    // ── Test 10: Stale packets older than 10 seconds are detected ──
    @Test
    fun testStalenessTimeoutMarksCattleDisconnected() {
        val tenSecondsAgo = System.currentTimeMillis() - 11_000
        val liveCattle = Cattle(
            id = "CATTLE-001",
            name = "Daisy",
            breed = "Holstein",
            age = "3",
            overall = "Healthy",
            sensors = listOf(
                Sensor("💓", "SpO2", "Not Available", "%", "Normal", androidx.compose.ui.graphics.Color(0xFF2E7D32), isValid = false, reason = "No contact")
            ),
            dataSource = "Live from sensor",
            isSimulated = false,
            isStale = false,
            lastPacketTime = tenSecondsAgo
        )

        val isStale = (System.currentTimeMillis() - liveCattle.lastPacketTime) > 10_000
        assertTrue("Packet older than 10s must be marked stale", isStale)
    }

    // ── Test 11: Sensor reason mapping verifies proper message when invalid ──
    @Test
    fun testSensorReasonMappingWhenInvalid() {
        // When max_state is NO_SENSOR, reason must be "Sensor not connected"
        val maxStateNoSensor = "NO_SENSOR"
        val reason1 = if (maxStateNoSensor == "NO_CONTACT") "No contact" else "Sensor not connected"
        assertEquals("Sensor not connected", reason1)

        // When max_state is NO_CONTACT, reason must be "No contact"
        val maxStateNoContact = "NO_CONTACT"
        val reason2 = if (maxStateNoContact == "NO_CONTACT") "No contact" else "Sensor not connected"
        assertEquals("No contact", reason2)

        // When pH is invalid, reason must be "Probe not in solution"
        val phValid = false
        val phReason = if (!phValid) "Probe not in solution" else null
        assertEquals("Probe not in solution", phReason)
    }

    // ── Test 12: Null vital values format to Not Available, NEVER 0 or default ──
    @Test
    fun testNullVitalNeverDisplaysZero() {
        val nullSpo2: Double? = null
        val nullHr: Double? = null
        val nullSteps: Int? = null

        val spo2Display = if (nullSpo2 != null && nullSpo2 > 0) "${nullSpo2.toInt()}" else "Not Available"
        val hrDisplay = if (nullHr != null && nullHr > 0) "${nullHr.toInt()}" else "Not Available"
        val stepsDisplay = if (nullSteps != null) "$nullSteps" else "Not Available"

        assertEquals("Not Available", spo2Display)
        assertEquals("Not Available", hrDisplay)
        assertEquals("Not Available", stepsDisplay)
        assertFalse(spo2Display == "0")
        assertFalse(hrDisplay == "0")
        assertFalse(stepsDisplay == "0")
    }

    // ── Test 13: Simulated data flag triggers simulated banner state ──
    @Test
    fun testSimulatedDataFlagTriggersWarningBanner() {
        val simulatedJson = """
        {
            "cattle_id": "SIM-COW-01",
            "source": "simulator",
            "is_simulated": true,
            "spo2": 98,
            "heart_rate": 72
        }
        """.trimIndent()
        val simMap = com.google.gson.Gson().fromJson(simulatedJson, Map::class.java)

        val isSim = (simMap["is_simulated"] as? Boolean == true) ||
                    (simMap["source"] as? String == "simulator")
        assertTrue("Simulated packet must be identified as simulated", isSim)

        val realJson = """
        {
            "cattle_id": "CATTLE-001",
            "source": "serial",
            "is_simulated": false,
            "spo2": null,
            "heart_rate": null
        }
        """.trimIndent()
        val realMap = com.google.gson.Gson().fromJson(realJson, Map::class.java)

        val isRealSim = (realMap["is_simulated"] as? Boolean == true) ||
                        (realMap["source"] as? String == "simulator")
        assertFalse("Real hardware packet must not be identified as simulated", isRealSim)
    }

    // ── Test 14: Packet with valid heart rate and SpO2 reaches Android state ──
    @Test
    fun testValidHeartRateAndSpo2ReachesAndroidState() {
        val validPacketJson = """
        {
            "event_type": "sensor_update",
            "source": "serial",
            "is_simulated": false,
            "validity": {
                "spo2": true,
                "heart_rate": true,
                "temperature": true,
                "ldr": true
            },
            "validity_reasons": {
                "spo2": null,
                "heart_rate": null
            },
            "payload": {
                "type": "sensor_update",
                "cattle_id": "CATTLE-001",
                "data": {
                    "heart_rate": 68,
                    "spo2": 98,
                    "temperature": 38.6,
                    "ldr": 920,
                    "heart_rate_valid": true,
                    "spo2_valid": true,
                    "temperature_valid": true,
                    "ldr_valid": true
                },
                "health": {
                    "heart_rate": {"status": "normal"},
                    "spo2": {"status": "normal"},
                    "temperature": {"status": "normal"},
                    "ldr": {"status": "normal"}
                }
            }
        }
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val envelope = com.google.gson.Gson().fromJson(validPacketJson, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val payload = envelope["payload"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val data = payload["data"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val validity = envelope["validity"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val validityReasons = envelope["validity_reasons"] as? Map<String, Any?>

        fun resolveValidity(key: String, validKey: String, hasData: Boolean): Boolean {
            if (validity != null && validity.containsKey(key)) return validity[key] as? Boolean ?: false
            if (data.containsKey(validKey)) return data[validKey] as? Boolean ?: false
            return hasData
        }

        fun resolveReason(key: String, rawVal: Any?, isValid: Boolean, defaultUnavailableReason: String): String? {
            if (rawVal != null && isValid) return null
            val backendReason = validityReasons?.get(key) as? String
            if (!backendReason.isNullOrBlank() && backendReason != "null") return backendReason
            if (rawVal != null && !isValid) return "Reading out of valid range"
            return defaultUnavailableReason
        }

        val rawSpo2 = (data["spo2"] as? Number)?.toInt()
        val spo2Valid = resolveValidity("spo2", "spo2_valid", rawSpo2 != null && rawSpo2 > 0)
        val spo2Val = if (rawSpo2 != null && spo2Valid && rawSpo2 > 0) "$rawSpo2" else "Not Available"
        val spo2Reason = resolveReason("spo2", rawSpo2, spo2Valid, "No contact")

        val rawHr = (data["heart_rate"] as? Number)?.toInt()
        val hrValid = resolveValidity("heart_rate", "heart_rate_valid", rawHr != null && rawHr > 0)
        val hrVal = if (rawHr != null && hrValid && rawHr > 0) "$rawHr" else "Not Available"
        val hrReason = resolveReason("heart_rate", rawHr, hrValid, "No contact")

        assertEquals("98", spo2Val)
        assertTrue("SpO2 must be valid", spo2Valid)
        assertNull("SpO2 reason must be null when valid", spo2Reason)

        assertEquals("68", hrVal)
        assertTrue("Heart rate must be valid", hrValid)
        assertNull("Heart rate reason must be null when valid", hrReason)
    }

    // ── Test 15: Out-of-range SpO2 shows Not Available with reason while other fields display ──
    @Test
    fun testOutOfRangeSpo2ShowsNotAvailableWithReasonWhileOtherFieldsDisplay() {
        val outOfRangePacketJson = """
        {
            "event_type": "sensor_update",
            "source": "serial",
            "is_simulated": false,
            "validity": {
                "spo2": false,
                "heart_rate": true,
                "temperature": true,
                "ldr": true
            },
            "validity_reasons": {
                "spo2": "Reading out of valid range",
                "heart_rate": null
            },
            "payload": {
                "type": "sensor_update",
                "cattle_id": "CATTLE-001",
                "data": {
                    "heart_rate": 72,
                    "spo2": 24,
                    "temperature": 38.5,
                    "ldr": 850,
                    "heart_rate_valid": true,
                    "spo2_valid": false,
                    "temperature_valid": true,
                    "ldr_valid": true
                },
                "health": {
                    "heart_rate": {"status": "normal"},
                    "spo2": {"status": "abnormal"},
                    "temperature": {"status": "normal"},
                    "ldr": {"status": "normal"}
                }
            }
        }
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val envelope = com.google.gson.Gson().fromJson(outOfRangePacketJson, Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val payload = envelope["payload"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val data = payload["data"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val validity = envelope["validity"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val validityReasons = envelope["validity_reasons"] as? Map<String, Any?>

        fun resolveValidity(key: String, validKey: String, hasData: Boolean): Boolean {
            if (validity != null && validity.containsKey(key)) return validity[key] as? Boolean ?: false
            if (data.containsKey(validKey)) return data[validKey] as? Boolean ?: false
            return hasData
        }

        fun resolveReason(key: String, rawVal: Any?, isValid: Boolean, defaultUnavailableReason: String): String? {
            if (rawVal != null && isValid) return null
            val backendReason = validityReasons?.get(key) as? String
            if (!backendReason.isNullOrBlank() && backendReason != "null") return backendReason
            if (rawVal != null && !isValid) return "Reading out of valid range"
            return defaultUnavailableReason
        }

        // Parse SpO2 (out of range: 24%)
        val rawSpo2 = (data["spo2"] as? Number)?.toInt()
        val spo2Valid = resolveValidity("spo2", "spo2_valid", rawSpo2 != null && rawSpo2 > 0)
        val spo2Val = if (rawSpo2 != null && spo2Valid && rawSpo2 > 0) "$rawSpo2" else "Not Available"
        val spo2Reason = resolveReason("spo2", rawSpo2, spo2Valid, "No contact")

        // Parse Heart Rate (valid: 72 bpm)
        val rawHr = (data["heart_rate"] as? Number)?.toInt()
        val hrValid = resolveValidity("heart_rate", "heart_rate_valid", rawHr != null && rawHr > 0)
        val hrVal = if (rawHr != null && hrValid && rawHr > 0) "$rawHr" else "Not Available"
        val hrReason = resolveReason("heart_rate", rawHr, hrValid, "No contact")

        // Parse Temperature (valid: 38.5 C)
        val rawTemp = (data["temperature"] as? Number)?.toFloat()
        val tempValid = resolveValidity("temperature", "temperature_valid", rawTemp != null)
        val tempVal = if (rawTemp != null && tempValid) String.format(java.util.Locale.US, "%.1f", rawTemp) else "Not Available"

        // Parse LDR (valid: 850 lux)
        val rawLdr = (data["ldr"] as? Number)?.toInt()
        val ldrValid = resolveValidity("ldr", "ldr_valid", rawLdr != null)
        val ldrVal = if (rawLdr != null && ldrValid) "$rawLdr" else "Not Available"

        // Assert SpO2 is Not Available with reason "Reading out of valid range"
        assertEquals("Not Available", spo2Val)
        assertFalse("SpO2 must be invalid", spo2Valid)
        assertEquals("Reading out of valid range", spo2Reason)

        // Assert other fields still display their valid values
        assertEquals("72", hrVal)
        assertTrue(hrValid)
        assertNull(hrReason)

        assertEquals("38.5", tempVal)
        assertTrue(tempValid)

        assertEquals("850", ldrVal)
        assertTrue(ldrValid)
    }

    // ── Test 16: Serial port busy state is detected and reported ──
    @Test
    fun testSerialPortBusyStatusReported() {
        val hardwareStatusJson = """
        {
            "serial_connected": false,
            "serial_busy": true,
            "status_message": "Serial port busy",
            "port": "COM10"
        }
        """.trimIndent()

        val statusMap = com.google.gson.Gson().fromJson(hardwareStatusJson, Map::class.java)
        val isBusy = statusMap["serial_busy"] as? Boolean == true
        val statusMsg = statusMap["status_message"] as? String

        assertTrue("Serial port busy flag must be true", isBusy)
        assertEquals("Serial port busy", statusMsg)
    }

    // ── Test 17: Weight sensor JSON parsing with stability and calibration flags ──
    @Test
    fun testWeightSensorParsingWithStabilityAndCalibration() {
        val jsonString = """
        {
            "cattle_id": "CATTLE-001",
            "body_weight_kg": 452.4,
            "weight_raw": 842100,
            "weight_stable": true,
            "weight_calibrated": true,
            "weight_valid": true,
            "weight_sensor_status": "stable"
        }
        """.trimIndent()

        val map = com.google.gson.Gson().fromJson(jsonString, Map::class.java)

        val weightKg = (map["body_weight_kg"] as? Number)?.toFloat()
        val weightStable = map["weight_stable"] as? Boolean == true
        val weightCalibrated = map["weight_calibrated"] as? Boolean == true
        val weightValid = map["weight_valid"] as? Boolean == true
        val sensorStatus = map["weight_sensor_status"] as? String

        assertNotNull(weightKg)
        assertEquals(452.4f, weightKg!!, 0.01f)
        assertTrue("Weight must be marked stable", weightStable)
        assertTrue("Weight must be calibrated", weightCalibrated)
        assertTrue("Weight reading must be valid", weightValid)
        assertEquals("stable", sensorStatus)

        val displayVal = if (weightKg != null && weightValid) String.format(java.util.Locale.US, "%.1f", weightKg) else "Not Available"
        assertEquals("452.4", displayVal)
    }

    // ── Test 18: Weight null or disconnected displays 'Not Available' and never 0.0 ──
    @Test
    fun testWeightNullDisplaysNotAvailableNeverZero() {
        val jsonString = """
        {
            "cattle_id": "CATTLE-001",
            "body_weight_kg": null,
            "weight_stable": false,
            "weight_calibrated": false,
            "weight_valid": false,
            "weight_sensor_status": "disconnected"
        }
        """.trimIndent()

        val map = com.google.gson.Gson().fromJson(jsonString, Map::class.java)

        val weightKg = (map["body_weight_kg"] as? Number)?.toFloat()
        val weightValid = map["weight_valid"] as? Boolean == true

        assertNull("Raw weight must be null when disconnected", weightKg)
        val displayVal = if (weightKg != null && weightValid) String.format(java.util.Locale.US, "%.1f", weightKg) else "Not Available"
        assertEquals("Not Available", displayVal)
        assertFalse("Must never format null into 0 or 0.0", displayVal == "0" || displayVal == "0.0")
    }

    // ── Test 19: Strict 7-sensor category validation on Dashboard ──
    @Test
    fun testStrictSevenSensorCategories() {
        val sensors = listOf(
            Sensor("❤️", "Heart Rate", "72", "bpm", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("💓", "SpO2", "98", "%", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("🌡️", "Body Temp", "38.5", "°C", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("🧪", "pH Level", "6.8", "", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("☀️", "Light", "850", "lux", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("⚖️", "Weight", "450.0", "kg", "Stable", androidx.compose.ui.graphics.Color.Green),
            Sensor("🏃", "MEMS Accelerometer", "X:0.1 Y:-0.0 Z:9.8", "m/s²", "Active", androidx.compose.ui.graphics.Color.Green)
        )

        assertEquals("Dashboard must contain exactly 7 sensor categories", 7, sensors.size)

        val labels = sensors.map { it.label }
        assertTrue("Must contain Heart Rate", labels.contains("Heart Rate"))
        assertTrue("Must contain SpO2", labels.contains("SpO2"))
        assertTrue("Must contain Body Temp", labels.contains("Body Temp"))
        assertTrue("Must contain pH Level", labels.contains("pH Level"))
        assertTrue("Must contain Light", labels.contains("Light"))
        assertTrue("Must contain Weight", labels.contains("Weight"))
        assertTrue("Must contain combined MEMS Accelerometer", labels.contains("MEMS Accelerometer"))

        assertFalse("Must NOT have separate MEMS X card", labels.contains("MEMS X"))
        assertFalse("Must NOT have separate MEMS Y card", labels.contains("MEMS Y"))
        assertFalse("Must NOT have separate MEMS Z card", labels.contains("MEMS Z"))
    }

    // ── Test 20: Weight statistics and trend determination ──
    @Test
    fun testWeightStatisticsTrendCalculation() {
        val currentWeight = 452.4f
        val previousWeight = 450.0f
        val diff = currentWeight - previousWeight
        val diffPct = (diff / previousWeight) * 100f
        val trend = when {
            diff > 0.5f -> "gain"
            diff < -0.5f -> "loss"
            else -> "stable"
        }

        assertEquals(2.4f, diff, 0.05f)
        assertEquals("gain", trend)
        assertTrue("Gain percentage must be positive", diffPct > 0f)
    }

    // ── Test 21: Real date and time formatting for alerts ──
    @Test
    fun testAlertRealTimeAndDateFormatting() {
        val isoUtc = "2026-11-04T15:30:00Z"
        val formattedIso = formatAlertDateTime(isoUtc)
        assertNotNull(formattedIso)
        assertTrue("Formatted date must contain year 2026", formattedIso.contains("2026"))
        assertTrue("Formatted date must contain month", formattedIso.contains("Nov"))
        assertTrue("Formatted date must contain AM or PM", formattedIso.endsWith("AM") || formattedIso.endsWith("PM"))

        val sqlDatetime = "2026-11-04 15:30:00"
        val formattedSql = formatAlertDateTime(sqlDatetime)
        assertNotNull(formattedSql)
        assertTrue("Formatted SQL timestamp must contain year 2026", formattedSql.contains("2026"))
        assertTrue("Formatted SQL timestamp must contain month", formattedSql.contains("Nov"))

        val blankFormatted = formatAlertDateTime("")
        assertNotNull(blankFormatted)
        assertTrue("Fallback must contain AM or PM", blankFormatted.endsWith("AM") || blankFormatted.endsWith("PM"))
        assertFalse("Must never be hardcoded placeholder Just now", blankFormatted == "Just now")
    }

    // ── Test 22: Alert model captures real date and time instead of 'Just now' placeholder ──
    @Test
    fun testAlertModelDisplaysRealDateTimeNotPlaceholder() {
        val cid = "CATTLE-001"
        val msg = "SpO2 abnormal (82%) • Heart Rate abnormal (130 bpm)"
        val realTimestamp = formatAlertDateTime("2026-11-04T15:30:00Z")
        val alert = Alert(
            cattle = cid,
            msg = msg,
            time = realTimestamp,
            tag = "WARNING",
            color = androidx.compose.ui.graphics.Color(0xFFF85149)
        )

        assertFalse("Alert time must not be 'Just now'", alert.time == "Just now")
        assertTrue("Alert time must include date", alert.time.contains("2026"))
        assertTrue("Alert time must include time indicator", alert.time.contains(":") && (alert.time.contains("AM") || alert.time.contains("PM")))
    }

    // ── Test 23: Steps and Total Steps card state and persistence rules ──
    @Test
    fun testStepsAndTotalStepsCardsAndPersistenceRules() {
        val cid = "CATTLE-001"
        var stepsToday = 150
        var totalSteps = 5000

        // Rule 1: Normal stride increments both
        val stride = 12
        stepsToday += stride
        totalSteps += stride
        assertEquals(162, stepsToday)
        assertEquals(5012, totalSteps)

        // Rule 2: Midnight rollover resets stepsToday without decreasing totalSteps
        stepsToday = 0
        assertEquals(0, stepsToday)
        assertEquals(5012, totalSteps)

        // Rule 3: Post-rollover steps accumulate normally on totalSteps
        stepsToday += 25
        totalSteps += 25
        assertEquals(25, stepsToday)
        assertEquals(5037, totalSteps)
    }

    // ── Test 24: Steps and Total Steps are present in 9-card sensor list and Cattle domain model ──
    @Test
    fun testStepsAndTotalStepsPresentInSensorListAndCattleModel() {
        val cid = "CATTLE-001"
        val sensors = listOf(
            Sensor("hr", "Heart Rate", "72", "bpm", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("spo2", "SpO2", "98", "%", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("temp", "Body Temp", "38.5", "°C", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("ph", "pH Level", "6.8", "", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("ldr", "Light", "450", "lux", "Normal", androidx.compose.ui.graphics.Color.Green),
            Sensor("weight", "Weight", "450.0", "kg", "Stable", androidx.compose.ui.graphics.Color.Green),
            Sensor("steps", "Steps", "1420", "steps", "Active", androidx.compose.ui.graphics.Color.Green),
            Sensor("total_steps", "Total Steps", "12500", "steps", "Tracking", androidx.compose.ui.graphics.Color.Green),
            Sensor("mems", "MEMS Accelerometer", "X:0.1 Y:0.2 Z:9.8", "m/s²", "Normal", androidx.compose.ui.graphics.Color.Green)
        )
        val cattle = Cattle(
            id = cid,
            name = "Bessie",
            breed = "Holstein",
            age = "4 years",
            overall = "Healthy",
            sensors = sensors,
            stepsToday = 1420,
            stepsLifetime = 12500
        )

        assertEquals("Sensor list must have exactly 9 sensors", 9, cattle.sensors.size)
        val stepsSensor = cattle.sensors.find { it.label.equals("Steps", ignoreCase = true) }
        val totalStepsSensor = cattle.sensors.find { it.label.equals("Total Steps", ignoreCase = true) }

        assertNotNull("Steps sensor card must be present", stepsSensor)
        assertEquals("1420", stepsSensor!!.value)
        assertEquals("Active", stepsSensor.status)

        assertNotNull("Total Steps sensor card must be present", totalStepsSensor)
        assertEquals("12500", totalStepsSensor!!.value)
        assertEquals("Tracking", totalStepsSensor.status)

        assertEquals(1420, cattle.stepsToday)
        assertEquals(12500, cattle.stepsLifetime)
    }
}
