package com.tradingagent.modules.ledger

import com.fasterxml.jackson.databind.ObjectMapper

enum class JournalAccount {
    BROKER_CASH,
    RESERVED_CASH,
    EXTERNAL_HOLDINGS,
    CASH_BUFFER,
    EQUITY,
}

data class JournalLine(
    val account: JournalAccount,
    val minorUnits: Long,
)

data class JournalEntry(
    val id: String,
    val lines: List<JournalLine>,
)

class Journal {
    private val entries = mutableListOf<JournalEntry>()
    private val balances = linkedMapOf<JournalAccount, Long>()
    private val posted = mutableSetOf<JournalAccount>()

    fun post(entry: JournalEntry) {
        require(entry.id.isNotBlank()) { "entry id is required" }
        require(entries.none { it.id == entry.id }) { "duplicate entry" }
        require(entry.lines.size >= 2) { "a journal entry needs at least two lines" }
        val sum = entry.lines.fold(0L) { acc, line -> Math.addExact(acc, line.minorUnits) }
        require(sum == 0L) { "unbalanced journal entry" }
        entry.lines.forEach { line ->
            val current = balances[line.account] ?: 0L
            balances[line.account] = Math.addExact(current, line.minorUnits)
            posted += line.account
        }
        entries += entry
    }

    fun entryCount(): Int = entries.size

    fun balance(account: JournalAccount): Long = balances[account] ?: 0L

    fun isPosted(account: JournalAccount): Boolean = posted.contains(account)

    fun isBalanced(): Boolean {
        val sum = JournalAccount.values().fold(0L) { acc, account ->
            Math.addExact(acc, balances[account] ?: 0L)
        }
        return sum == 0L
    }
}

object JournalFixtures {
    private val mapper = ObjectMapper()

    fun loadClasspath(resource: String = "/fixtures/ledger/balanced-journal.json"): Journal {
        val stream = JournalFixtures::class.java.getResourceAsStream(resource)
            ?: throw IllegalArgumentException("journal fixture not found")
        return fromJson(stream.bufferedReader().use { it.readText() })
    }

    fun fromJson(json: String): Journal {
        val root = mapper.readTree(json)
        val journal = Journal()
        val entries = root.get("entries") ?: throw IllegalArgumentException("entries are required")
        require(entries.isArray) { "entries must be an array" }
        entries.forEach { node ->
            val id = node.get("id")?.asText() ?: throw IllegalArgumentException("entry id is required")
            val linesNode = node.get("lines") ?: throw IllegalArgumentException("lines are required")
            require(linesNode.isArray) { "lines must be an array" }
            val lines = linesNode.map { line ->
                val account = JournalAccount.valueOf(line.get("account").asText())
                val minor = line.get("minor_units")
                require(minor != null && minor.isIntegralNumber) { "minor_units must be an integer" }
                require(minor.canConvertToLong()) { "minor_units overflow" }
                JournalLine(account, minor.asLong())
            }
            journal.post(JournalEntry(id, lines))
        }
        return journal
    }
}
