package com.tradingagent.modules.brokerread

import com.tradingagent.kernel.EvidenceStatus
import com.tradingagent.kernel.Money
import com.tradingagent.modules.configuration.EvidenceGate
import java.time.Duration
import java.time.Instant

data class ProbeSnapshot(
    val connected: Boolean,
    val observedAt: Instant?,
    val errorClass: String?,
    val brokerCash: Money?,
    val equity: Money?,
    val positionCount: Int?,
    val accountLabel: String?,
    val positions: List<ObservedPosition>? = null,
)

class CapabilityProbe(
    private val evidenceGate: EvidenceGate,
    private val keys: () -> DemoKeyMaterial?,
    private val transport: HttpGet,
    private val clock: () -> Instant = Instant::now,
    private val sleep: (Duration) -> Unit = { duration ->
        if (!duration.isZero) {
            Thread.sleep(duration.toMillis())
        }
    },
) {
    private val lock = Any()
    private var snapshot = ProbeSnapshot(
        connected = false,
        observedAt = null,
        errorClass = "KEYS_MISSING",
        brokerCash = null,
        equity = null,
        positionCount = null,
        accountLabel = null,
    )

    fun current(): ProbeSnapshot = synchronized(lock) { snapshot }

    fun refreshIfStale(maxAge: Duration = Duration.ofSeconds(60)): ProbeSnapshot {
        val existing = current()
        val observedAt = existing.observedAt
        if (observedAt != null && Duration.between(observedAt, clock()) < maxAge) {
            return existing
        }
        return refresh()
    }

    fun refresh(): ProbeSnapshot = synchronized(lock) {
        val material = keys()
        val next = if (material == null) {
            evidenceGate.recordBrokerRead(EvidenceStatus.UNVERIFIED)
            ProbeSnapshot(false, clock(), "KEYS_MISSING", null, null, null, null)
        } else {
            when (val outcome = DemoPortfolioRead.execute(transport, material, sleep)) {
                is ReadOutcome.Pass -> {
                    evidenceGate.recordBrokerRead(EvidenceStatus.PASS)
                    ProbeSnapshot(
                        connected = true,
                        observedAt = clock(),
                        errorClass = null,
                        brokerCash = outcome.account.brokerCash,
                        equity = outcome.account.equity,
                        positionCount = outcome.account.positionCount,
                        accountLabel = outcome.account.accountLabel,
                        positions = outcome.account.positions,
                    )
                }
                is ReadOutcome.Failed -> {
                    evidenceGate.recordBrokerRead(EvidenceStatus.FAIL)
                    ProbeSnapshot(false, clock(), outcome.errorClass, null, null, null, null)
                }
                is ReadOutcome.Unverified -> {
                    evidenceGate.recordBrokerRead(EvidenceStatus.UNVERIFIED)
                    ProbeSnapshot(false, clock(), outcome.errorClass, null, null, null, null)
                }
            }
        }
        snapshot = next
        next
    }

    companion object {
        fun offline(): CapabilityProbe = CapabilityProbe(
            evidenceGate = EvidenceGate(),
            keys = { null },
            transport = HttpGet { _, _ -> throw IllegalStateException("offline") },
        )
    }
}
