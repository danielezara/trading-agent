package com.tradingagent.api

import com.tradingagent.config.DemoKeySlot
import com.tradingagent.modules.oms.KillSwitch
import com.tradingagent.modules.readmodel.DashboardReadModel
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class ConsoleController(
    private val readModel: DashboardReadModel,
    private val killSwitch: KillSwitch,
) {
    @GetMapping("/", "/console")
    fun home(model: Model): String {
        val portfolio = readModel.portfolio()
        val keys = DemoKeySlot.fromEnvironment(System.getenv())
        model.addAttribute("keysReady", keys.complete())
        model.addAttribute("killSwitch", killSwitch.armed())
        model.addAttribute("operationalState", portfolio.operationalState.wire)
        model.addAttribute("connected", portfolio.brokerConnected)
        model.addAttribute("positions", portfolio.positions.orEmpty())
        model.addAttribute("brokerCash", portfolio.brokerCash)
        model.addAttribute("cashBalances", portfolio.cashBalances.orEmpty())
        return "console"
    }
}
