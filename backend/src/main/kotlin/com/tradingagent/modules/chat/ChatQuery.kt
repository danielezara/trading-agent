package com.tradingagent.modules.chat

data class ChatAnswer(
    val executed: Boolean,
    val text: String,
)

class ChatQuery {
    fun answer(message: String): ChatAnswer {
        require(message.isNotBlank())
        return ChatAnswer(
            executed = false,
            text = "Nessuna azione è stata eseguita. La chat dello scheletro è solo informativa e non è collegata.",
        )
    }
}
