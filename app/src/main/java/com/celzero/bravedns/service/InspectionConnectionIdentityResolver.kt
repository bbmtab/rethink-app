package com.celzero.bravedns.service

import android.content.Context
import com.celzero.bravedns.core.proxy.policy.InspectionAndroidConnectionOwnerUidResolver
import com.celzero.bravedns.core.proxy.policy.InspectionConnectionIdentity
import java.net.Socket
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.CancellationException

class InspectionConnectionIdentityResolver
internal constructor(
  private val ownerUidForSocket: (Socket) -> Int?,
  private val packageNamesForUid: suspend (Int) -> List<String>,
) {
  constructor(context: Context) :
    this(
      ownerUidForSocket =
        InspectionAndroidConnectionOwnerUidResolver(context)::resolve,
      packageNamesForUid = FirewallManager::getPackageNamesByUid,
    )

  suspend fun resolve(clientSocket: Socket): InspectionConnectionIdentity {
    val uid =
      try {
        ownerUidForSocket(clientSocket)
      } catch (_: RuntimeException) {
        null
      }

    if (uid == null) {
      return InspectionConnectionIdentity(
        uid = null,
        packageNames = emptySet(),
      )
    }

    val rawPackageNames =
      try {
        packageNamesForUid(uid)
      } catch (error: CancellationException) {
        throw error
      } catch (_: RuntimeException) {
        emptyList()
      }

    val normalizedPackageNames =
      rawPackageNames
        .mapNotNull(::normalizePackageName)
        .toCollection(LinkedHashSet())

    return InspectionConnectionIdentity(
      uid = uid,
      packageNames = normalizedPackageNames,
    )
  }

  private fun normalizePackageName(packageName: String): String? {
    val normalized = packageName.trim().lowercase(Locale.US)
    return normalized.takeIf { it.isNotEmpty() }
  }
}
