package com.example.smartcattle

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class AbnormalNotificationTest {

    @Test
    fun testAbnormalEventJsonParsing() {
        val jsonStr = """
        {
            "event_id": "EVT-COW-1-TEMP-1731500000",
            "cattle_id": "COW-1",
            "cattle_name": "Gauri",
            "sensor_key": "temperature",
            "sensor_name": "Body Temperature",
            "current_value": "40.8 °C",
            "status": "HIGH",
            "expected_range": "38.0°C - 39.3°C",
            "timestamp": "2026-11-13T19:30:00Z",
            "severity": "CRITICAL",
            "recommended_action": "High fever detected. Move to shaded cool area."
        }
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val map = com.google.gson.Gson().fromJson(jsonStr, Map::class.java) as Map<String, Any?>
        val event = AbnormalAlertEvent(
            eventId = map["event_id"] as? String ?: "",
            cattleId = map["cattle_id"] as? String ?: "",
            cattleName = map["cattle_name"] as? String ?: "",
            sensorKey = map["sensor_key"] as? String ?: "",
            sensorName = map["sensor_name"] as? String ?: "",
            currentValue = map["current_value"] as? String ?: "",
            statusDirection = map["status"] as? String ?: "",
            expectedRange = map["expected_range"] as? String ?: "",
            timestamp = map["timestamp"] as? String ?: "",
            severity = map["severity"] as? String ?: "",
            recommendedAction = map["recommended_action"] as? String ?: ""
        )

        assertEquals("COW-1", event.cattleId)
        assertEquals("Body Temperature", event.sensorName)
        assertEquals("HIGH", event.statusDirection)
        assertEquals("CRITICAL", event.severity)
        assertEquals("40.8 °C", event.currentValue)
        assertTrue(event.expectedRange.contains("38.0"))
    }

    @Test
    fun testNotificationDeduplicationLogic() {
        val sensorStatusMap = ConcurrentHashMap<String, String>()
        val popupEvents = mutableListOf<String>()

        fun processSensorReading(cattleId: String, sensorKey: String, isAbnormal: Boolean, value: String) {
            val key = "$cattleId:$sensorKey"
            val prevStatus = sensorStatusMap[key]

            if (isAbnormal) {
                // Deduplication: Only trigger if transition is new (not already abnormal)
                if (prevStatus != "abnormal") {
                    sensorStatusMap[key] = "abnormal"
                    popupEvents.add("POPUP: $key at $value")
                }
            } else {
                // Recovery back to normal
                if (prevStatus == "abnormal") {
                    sensorStatusMap[key] = "normal"
                }
            }
        }

        val cow = "CATTLE-001"
        val sensor = "temperature"

        // 1. Initial normal reading: no popup
        processSensorReading(cow, sensor, isAbnormal = false, value = "38.5")
        assertEquals(0, popupEvents.size)

        // 2. Normal -> Abnormal: popup triggers!
        processSensorReading(cow, sensor, isAbnormal = true, value = "40.5")
        assertEquals(1, popupEvents.size)
        assertEquals("POPUP: CATTLE-001:temperature at 40.5", popupEvents[0])

        // 3. Continuous Abnormal (subsequent packets): NO popup triggers! (Deduplicated)
        processSensorReading(cow, sensor, isAbnormal = true, value = "40.6")
        processSensorReading(cow, sensor, isAbnormal = true, value = "40.7")
        processSensorReading(cow, sensor, isAbnormal = true, value = "40.5")
        assertEquals(1, popupEvents.size) // Still exactly 1!

        // 4. Abnormal -> Normal (Recovery)
        processSensorReading(cow, sensor, isAbnormal = false, value = "38.6")
        assertEquals(1, popupEvents.size)
        assertEquals("normal", sensorStatusMap["$cow:$sensor"])

        // 5. Normal -> Abnormal again (Re-triggering)
        processSensorReading(cow, sensor, isAbnormal = true, value = "40.8")
        assertEquals(2, popupEvents.size)
        assertEquals("POPUP: CATTLE-001:temperature at 40.8", popupEvents[1])
    }
}
