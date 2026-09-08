package com.celzero.bravedns.core.proxy.policy

import java.net.Socket

enum class LocalProxyFirewallDecision {
    ALLOW,
    BLOCK
}

data class LocalProxyFirewallResult(
    val decision: LocalProxyFirewallDecision,
    val reason: String
)

fun interface LocalProxyFirewallEvaluator {
    suspend fun evaluate(
        clientSocket: Socket,
        host: String,
        destinationPort: Int
    ): LocalProxyFirewallResult
}
