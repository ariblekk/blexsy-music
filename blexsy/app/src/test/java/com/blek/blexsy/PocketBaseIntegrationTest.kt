package com.blek.blexsy

import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.ui.formatStatCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PocketBaseIntegrationTest {

    @Test
    fun `formatStatCount formats counts like Instagram`() {
        assertEquals("0", formatStatCount(0))
        assertEquals("42", formatStatCount(42))
        assertEquals("999", formatStatCount(999))
        assertEquals("1.2k", formatStatCount(1234))
        assertEquals("15k", formatStatCount(15200))
        assertEquals("2M", formatStatCount(2_500_000))
    }

    @Test
    fun `pbUrl defaults to https pb blek my id and trims trailing slashes`() {
        val client = BlexsyApiClient()
        assertEquals("https://pb.blek.my.id", client.pbUrl)

        val slashClient = BlexsyApiClient(pbUrl = "https://pb.blek.my.id/")
        assertEquals("https://pb.blek.my.id", slashClient.pbUrl)

        val customClient = BlexsyApiClient(pbUrl = "http://10.0.2.2:8090/")
        assertEquals("http://10.0.2.2:8090", customClient.pbUrl)
    }

    @Test
    fun `setting token decodes userId from PocketBase JWT payload`() {
        val client = BlexsyApiClient()
        // Payload: {"id":"usr_test123","type":"auth","collectionId":"users"} -> eyJpZCI6InVzcl90ZXN0MTIzIiwidHlwZSI6ImF1dGgiLCJjb2xsZWN0aW9uSWQiOiJ1c2VycyJ9
        val dummyJwt = "header.eyJpZCI6InVzcl90ZXN0MTIzIiwidHlwZSI6ImF1dGgiLCJjb2xsZWN0aW9uSWQiOiJ1c2VycyJ9.signature"
        client.token = dummyJwt
        assertEquals("usr_test123", client.currentUserId)
        assertEquals("usr_test123", client.userId)

        client.token = "guest"
        assertNull(client.currentUserId)
        assertNull(client.userId)

        client.token = null
        assertNull(client.currentUserId)
        assertNull(client.userId)
    }

    @Test
    fun `spin threshold requires at least 20 seconds or 80 percent for short tracks`() {
        fun computeThreshold(durationMs: Long): Long {
            return if (durationMs in 1..19_999L) (durationMs * 0.8f).toLong() else 20_000L
        }

        // Standard song (e.g. 3 minutes) requires 20s
        assertEquals(20_000L, computeThreshold(180_000L))
        // 25s song requires 20s
        assertEquals(20_000L, computeThreshold(25_000L))
        // 10s short skit requires 8s (80%)
        assertEquals(8_000L, computeThreshold(10_000L))
        // Unknown duration defaults to 20s
        assertEquals(20_000L, computeThreshold(0L))
    }
}
