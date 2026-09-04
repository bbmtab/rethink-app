package com.celzero.bravedns.core.proxy.policy

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetSocketAddress
import java.net.Socket

class InspectionAndroidConnectionOwnerUidResolver
internal constructor(
  private val sdkInt: Int,
  private val ownerUidForSocket: (Socket) -> Int,
) {
  constructor(context: Context) :
    this(
      sdkInt = Build.VERSION.SDK_INT,
      ownerUidForSocket = { socket ->
        resolveOwnerUid(context, socket)
      },
    )

  fun resolve(clientSocket: Socket): Int? {
    if (sdkInt < Build.VERSION_CODES.Q) {
      return null
    }

    val uid =
      try {
        ownerUidForSocket(clientSocket)
      } catch (_: RuntimeException) {
        INVALID_UID
      }

    return uid.takeUnless { it == INVALID_UID }
  }

  companion object {
    internal const val INVALID_UID = -1

    private fun resolveOwnerUid(
      context: Context,
      clientSocket: Socket,
    ): Int {
      val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE)
          as? ConnectivityManager
          ?: return INVALID_UID

      val applicationEndpoint =
        InetSocketAddress(
          clientSocket.inetAddress,
          clientSocket.port,
        )
      val proxyEndpoint =
        InetSocketAddress(
          clientSocket.localAddress,
          clientSocket.localPort,
        )

      return connectivityManager.getConnectionOwnerUid(
        OsConstants.IPPROTO_TCP,
        applicationEndpoint,
        proxyEndpoint,
      )
    }
  }
}