package com.celzero.bravedns.core.proxy.policy

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionAndroidConnectionOwnerUidResolverTest {
  private val socket = Socket()

  @Test
  fun preAndroidQReturnsNullWithoutQueryingPlatform() {
    var queried = false
    val resolver =
      resolver(
        sdkInt = 28,
        ownerUid = {
          queried = true
          10001
        },
      )

    assertNull(resolver.resolve(socket))
    assertFalse(queried)
  }

  @Test
  fun androidQAndLaterReturnsResolvedUid() {
    var queried = false
    val resolver =
      resolver(
        ownerUid = {
          queried = true
          10001
        },
      )

    assertEquals(10001, resolver.resolve(socket))
    assertTrue(queried)
  }

  @Test
  fun invalidOwnerUidReturnsNull() {
    val resolver =
      resolver(
        ownerUid = {
          InspectionAndroidConnectionOwnerUidResolver.INVALID_UID
        },
      )

    assertNull(resolver.resolve(socket))
  }

  @Test
  fun platformLookupFailureReturnsNull() {
    val resolver =
      resolver(
        ownerUid = {
          throw SecurityException("owner lookup denied")
        },
      )

    assertNull(resolver.resolve(socket))
  }

  private fun resolver(
    sdkInt: Int = 29,
    ownerUid: (Socket) -> Int,
  ): InspectionAndroidConnectionOwnerUidResolver =
    InspectionAndroidConnectionOwnerUidResolver(
      sdkInt = sdkInt,
      ownerUidForSocket = ownerUid,
    )
}