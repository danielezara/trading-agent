package com.tradingagent.api

import com.tradingagent.modules.brokerread.WhitelistCard
import com.tradingagent.modules.brokerread.WhitelistMarketData
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class WhitelistController(
    private val whitelist: WhitelistMarketData,
) {
    @GetMapping("/whitelist")
    fun whitelist(response: HttpServletResponse): List<WhitelistCard> {
        response.setHeader("Cache-Control", "no-store")
        return whitelist.snapshot()
    }
}
