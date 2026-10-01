package com.v2ray.helper

import com.v2ray.helper.engine.MockMiningEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockMiningEngineTest {

    private lateinit var engine: MockMiningEngine

    @Before
    fun setUp() {
        engine = MockMiningEngine()
    }

    @Test
    fun testDefaultState() {
        val status = engine.getStatus()
        assertFalse(status.isRunning)
        assertEquals(MockMiningEngine.DEFAULT_CPU_LIMIT, status.cpuLimitPercent)
        assertEquals(0L, status.hashrateHps)
        assertEquals(0L, engine.getHashrate())
    }

    @Test
    fun testCpuLimitClamping() {
        engine.setCpuLimit(10) // below min 20
        assertEquals(20, engine.getStatus().cpuLimitPercent)

        engine.setCpuLimit(95) // above max 80
        assertEquals(80, engine.getStatus().cpuLimitPercent)

        engine.setCpuLimit(50)
        assertEquals(50, engine.getStatus().cpuLimitPercent)
    }

    @Test
    fun testDeterministicHashrateCalculation() {
        engine.setCpuLimit(20)
        engine.startMining()
        assertEquals(250L, engine.getHashrate())

        engine.setCpuLimit(50)
        assertEquals(625L, engine.getHashrate())

        engine.setCpuLimit(80)
        assertEquals(1000L, engine.getHashrate())

        engine.stopMining()
        assertEquals(0L, engine.getHashrate())
    }

    @Test
    fun testStartStopCycle() {
        assertTrue(engine.startMining())
        assertTrue(engine.getStatus().isRunning)

        assertTrue(engine.stopMining())
        assertFalse(engine.getStatus().isRunning)
    }
}
