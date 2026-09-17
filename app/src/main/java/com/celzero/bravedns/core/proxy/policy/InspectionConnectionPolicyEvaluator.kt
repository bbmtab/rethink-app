package com.celzero.bravedns.core.proxy.policy

import java.net.Socket
import java.util.concurrent.CancellationException

class InspectionConnectionPolicyEvaluator(
    private val policySnapshot: InspectionPolicySnapshot,
    private val identityResolver:
        suspend (Socket) -> InspectionConnectionIdentity,
    private val policyEngine: InspectionPolicyEngine =
        InspectionPolicyEngine()
) {
    suspend fun evaluate(
        clientSocket: Socket,
        host: String,
        destinationPort: Int
    ): InspectionPolicyResult {
        val identity =
            try {
                identityResolver(clientSocket)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                InspectionConnectionIdentity(
                    uid = null,
                    packageNames = emptySet()
                )
            }

        return policyEngine.evaluate(
            connection =
                InspectionConnection(
                    packageNames = identity.packageNames,
                    uid = identity.uid,
                    host = host,
                    destinationPort = destinationPort
                ),
            policy = policySnapshot
        )
    }
}
