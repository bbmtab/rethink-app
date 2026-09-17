package com.celzero.bravedns.core.proxy.policy

data class InspectionTransportPolicyResult(
val forceTcp: Boolean,
val inspectionResult: InspectionPolicyResult?
)

class InspectionTransportPolicy(
private val engine: InspectionPolicyEngine =
InspectionPolicyEngine()
) {
fun evaluate(
packageNames: Set<String>,
uid: Int?,
host: String,
destinationPort: Int,
isUdp: Boolean,
policy: InspectionPolicySnapshot
): InspectionTransportPolicyResult {
if (!isUdp || destinationPort != HTTPS_PORT) {
return InspectionTransportPolicyResult(
forceTcp = false,
inspectionResult = null
)
}

    val inspectionResult =
        engine.evaluate(
            connection =
                InspectionConnection(
                    packageNames = packageNames,
                    uid = uid,
                    host = host,
                    destinationPort = destinationPort
                ),
            policy = policy
        )

    return InspectionTransportPolicyResult(
        forceTcp =
            inspectionResult.decision ==
                InspectionDecision.MITM,
        inspectionResult = inspectionResult
    )
}

companion object {
    private const val HTTPS_PORT = 443
}
}

