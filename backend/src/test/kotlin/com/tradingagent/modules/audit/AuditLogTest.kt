package com.tradingagent.modules.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AuditLogTest {
    private val at = Instant.parse("2026-11-27T00:05:00Z")

    @Test
    fun `a chain detects alteration deletion and insertion`() {
        val log = AuditLog()
        log.append(at, "corr-1", "DecisionRecorded", "cfg-1", "a".repeat(64), "observation-decision/1", "action=NO-TRADE")
        log.append(at.plusSeconds(1), "corr-2", "DecisionRecorded", "cfg-1", "a".repeat(64), "observation-decision/1", "action=NO-TRADE")
        val events = log.events()
        assertTrue(AuditLog.verify(events).valid)
        val day = LocalDate.ofInstant(at, ZoneOffset.UTC)
        log.sealDay(day)
        assertTrue(log.digestSealed(day))
        log.sealDay(day)
        log.append(at.plusSeconds(2), "corr-3", "DecisionRecorded", "cfg-1", "a".repeat(64), "observation-decision/1", "action=NO-TRADE")
        assertThrows(IllegalStateException::class.java) { log.sealDay(day) }

        val altered = events.toMutableList()
        altered[0] = altered[0].copy(payloadSha256 = "b".repeat(64))
        assertFalse(AuditLog.verify(altered).valid)

        assertFalse(AuditLog.verify(listOf(events[1].copy(sequence = 1, previousHash = AuditLog.GENESIS))).valid)
        val inserted = events.toMutableList()
        inserted.add(1, events[0].copy(sequence = 2))
        assertFalse(AuditLog.verify(inserted).valid)
        assertFalse(log.wired())
    }

    @Test
    fun `a sealed digest does not match a tampered day`() {
        val log = AuditLog()
        val event = log.append(at, "corr-1", "DecisionRecorded", "cfg-1", "c".repeat(64), "observation-decision/1", "payload")
        val day = LocalDate.ofInstant(at, ZoneOffset.UTC)
        val digest = log.dailyDigest(day)
        log.sealDay(day)
        val tampered = listOf(event.copy(eventHash = "d".repeat(64)))
        assertTrue(log.sealedDigest(day) == digest)
        assertTrue(AuditLog.dailyDigest(day, tampered) != digest)
    }
}
