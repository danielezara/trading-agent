package com.tradingagent.modules.identity

enum class Role {
    VIEWER,
    OPERATOR,
    EMERGENCY_ADMIN,
}

class AccessModel {
    fun allowsMutation(role: Role): Boolean {
        require(role in Role.values())
        return false
    }
}
