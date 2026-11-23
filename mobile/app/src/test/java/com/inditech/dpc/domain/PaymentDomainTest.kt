package com.inditech.dpc.domain

import com.inditech.dpc.data.api.ApiClientFactory
import com.inditech.dpc.data.api.PlatformJson
import com.inditech.dpc.data.model.EnrollmentResponse
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.TenantConfig
import com.inditech.dpc.messaging.PushService
import org.junit.Assert.assertEquals
import org.junit.Test

class ContractTest {
    @Test
    fun `parses the dynamic tenant configuration payload`() {
        val json = """
        {"tenant_id":"t1","version":4,
         "identity":{"name":"Acme","logo_url":"https://x/logo.png","theme":{"primary_color":"#112233","secondary_color":"#445566","background_color":"#000000","surface_color":"#111111","text_color":"#FFFFFF","button_corner_radius":12}},
         "support":{"phone_number":"1","whatsapp_number":"2","email":"a@b.c","allow_emergency_calls":true,"allow_direct_support_call":true},
         "repayment_settings":{"payment_modes":[{"type":"USSD","enabled":true,"instructions":"Dial","ussd_code":"*1#"}],"whitelisted_domains":["pay.acme.com"]},
         "enforcement_policies":{"grace_period_hours":72,"warning_overlay_hours_before_lock":24,"warning_message":"w","lock_message":"l","whitelisted_packages_when_locked":[],"offline_lock_fallback":{"max_offline_days":3,"action_if_exceeded":"HARD_LOCK"}},
         "unknown_future_field": 1}
        """.trimIndent()
        val cfg = PlatformJson.decodeFromString<TenantConfig>(json)
        assertEquals(4, cfg.version)
        assertEquals(12f, cfg.identity.theme.buttonCornerRadius)
        assertEquals(listOf("112", "911"), cfg.support.emergencyNumbers)
    }

    @Test
    fun `parses the enrollment response`() {
        val json = """{"status":"ENROLLED","device_id":"d","tenant_id":"t","enrolled_at":"2023-10-24T08:30:00Z","device_jwt":"jwt","current_state":"HARD_LOCKED","offline_unlock_secret":"aa","endpoints":{"config_url":"c","heartbeat_url":"h"},"mqtt":null}"""
        val res = PlatformJson.decodeFromString<EnrollmentResponse>(json)
        assertEquals(LockState.HARD_LOCKED, res.currentState)
    }

    @Test
    fun `maps push payloads to commands`() {
        assertEquals(LockState.HARD_LOCKED, PushService.parse(mapOf("commandType" to "LOCK", "commandId" to "1"))!!.targetState)
        assertEquals(LockState.SOFT_LOCKED, PushService.parse(mapOf("commandType" to "SOFT_LOCK", "commandId" to "1", "targetState" to "SOFT_LOCKED"))!!.targetState)
        assertEquals(null, PushService.parse(mapOf("commandType" to "LOCK")))
    }

    @Test
    fun `normalizes gateway urls to the api origin`() {
        assertEquals("https://api.platform.enterprise.com/", ApiClientFactory.normalizeBaseUrl("https://api.platform.enterprise.com/v2/enrollment"))
        assertEquals("http://10.0.2.2:8080/", ApiClientFactory.normalizeBaseUrl("http://10.0.2.2:8080/api/v1"))
    }
}
