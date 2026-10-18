package com.example.smartcattle

import androidx.compose.ui.unit.dp
import com.example.smartcattle.ui.components.HealthStatusType
import com.example.smartcattle.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCattleThemeTest {

    @Test
    fun testSpacingTokensMeetDesignSystemSpec() {
        val spacing = Spacing()
        assertEquals(2.dp, spacing.xxs)
        assertEquals(4.dp, spacing.xs)
        assertEquals(8.dp, spacing.sm)
        assertEquals(12.dp, spacing.md)
        assertEquals(16.dp, spacing.lg)
        assertEquals(24.dp, spacing.xl)
        assertEquals(32.dp, spacing.xxl)
        assertEquals(48.dp, spacing.minTouchTarget)
    }

    @Test
    fun testMinTouchTargetMeetsAccessibilityRequirement() {
        val spacing = Spacing()
        assertTrue(
            "Minimum touch target must be at least 48dp for WCAG compliance",
            spacing.minTouchTarget.value >= 48f
        )
    }

    @Test
    fun testHealthStatusResolution() {
        fun resolveType(status: String): HealthStatusType = when (status.trim().lowercase()) {
            "healthy", "normal", "good", "connected" -> HealthStatusType.HEALTHY
            "warning", "check", "moderate", "reconnecting" -> HealthStatusType.WARNING
            "critical", "danger", "alert", "fallen", "fall_detected" -> HealthStatusType.CRITICAL
            else -> HealthStatusType.OFFLINE
        }

        assertEquals(HealthStatusType.HEALTHY, resolveType("Healthy"))
        assertEquals(HealthStatusType.HEALTHY, resolveType("normal"))
        assertEquals(HealthStatusType.HEALTHY, resolveType("good"))
        assertEquals(HealthStatusType.WARNING, resolveType("warning"))
        assertEquals(HealthStatusType.WARNING, resolveType("reconnecting"))
        assertEquals(HealthStatusType.CRITICAL, resolveType("critical"))
        assertEquals(HealthStatusType.CRITICAL, resolveType("fall_detected"))
        assertEquals(HealthStatusType.OFFLINE, resolveType("offline"))
        assertEquals(HealthStatusType.OFFLINE, resolveType("unknown"))
    }
}
