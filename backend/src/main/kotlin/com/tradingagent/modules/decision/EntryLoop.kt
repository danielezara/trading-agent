package com.tradingagent.modules.decision

import com.tradingagent.modules.strategy.ClosedBarPolicy
import com.tradingagent.modules.strategy.MandateControl
import java.time.Duration
import java.time.Instant

object EntryPace {
    val BREAKOUT_INTERVAL: Duration = Duration.ofSeconds(20)

    fun remaining(started: Instant, now: Instant, interval: Duration): Duration {
        val left = interval.minus(Duration.between(started, now))
        return if (left.isNegative) Duration.ZERO else left
    }

    fun wait(now: Instant, barSeconds: Long, graceSeconds: Long): Duration {
        require(barSeconds > 0 && graceSeconds >= 0) { "l'attesa della barra è positiva" }
        val span = barSeconds * 1_000L
        val elapsed = Math.floorMod(now.toEpochMilli(), span)
        val untilBoundary = if (elapsed == 0L) span else span - elapsed
        return Duration.ofMillis(untilBoundary + graceSeconds * 1_000L)
    }

    fun finished(reason: String): Boolean = reason == MandateControl.BUDGET_LIMIT ||
        reason == MandateControl.EXPOSURE_LIMIT ||
        reason == MandateControl.LOSS_LIMIT ||
        reason == MandateControl.EXISTING_POSITION ||
        reason == ClosedBarPolicy.KILL_SWITCH
}

class EntryLoop(
    private val cycle: DecisionCycle,
    private val clock: () -> Instant = Instant::now,
    private val sleep: (Duration) -> Unit = { duration -> Thread.sleep(duration.toMillis()) },
    private val barSeconds: Long = 300,
    private val graceSeconds: Long = 5,
    private val interval: Duration? = null,
    private val onRecorded: (RecordedDecision) -> Unit = {},
) {
    @Volatile
    var accepting: Boolean = false
        private set

    fun start() {
        accepting = true
        val worker = Thread {
            var mark = clock()
            while (accepting) {
                val pause = interval?.let { EntryPace.remaining(mark, clock(), it) }
                    ?: EntryPace.wait(clock(), barSeconds, graceSeconds)
                sleep(pause)
                if (!accepting) {
                    return@Thread
                }
                mark = clock()
                val recorded = try {
                    cycle.record()
                } catch (_: Exception) {
                    continue
                }
                onRecorded(recorded)
                if (EntryPace.finished(recorded.rule.reason)) {
                    accepting = false
                }
            }
        }
        worker.name = if (interval == null) "five-minute-entries" else "twenty-second-entries"
        worker.isDaemon = true
        worker.start()
    }
}
