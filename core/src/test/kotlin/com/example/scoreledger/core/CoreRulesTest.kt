package com.example.scoreledger.core
import org.junit.Test
import org.junit.Assert.assertTrue
class CoreRulesTest {
    @Test fun businessContracts() { assertTrue(runContractChecks() >= 50) }
}
