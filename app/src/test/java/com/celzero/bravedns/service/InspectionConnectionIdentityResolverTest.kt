package com.celzero.bravedns.service

import java.net.Socket
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionConnectionIdentityResolverTest {
  private val socket = Socket()

  @Test
  fun unresolvedOwnerSkipsPackageLookup() = runTest {
    var packageLookupInvocations = 0
    val resolver =
      resolver(
        ownerUid = { null },
        packageNames = {
          packageLookupInvocations++
          error("package lookup must not be called when owner uid is null")
        },
      )

    val result = resolver.resolve(socket)

    assertNull(result.uid)
    assertEquals(emptySet<String>(), result.packageNames)
    assertEquals(0, packageLookupInvocations)
  }

  @Test
  fun resolvedOwnerQueriesExistingInventoryByExactUid() = runTest {
    var lastQueriedUid: Int? = null
    val resolver =
      resolver(
        ownerUid = { 10001 },
        packageNames = { uid ->
          lastQueriedUid = uid
          listOf("com.example.app")
        },
      )

    val result = resolver.resolve(socket)

    assertEquals(10001, result.uid)
    assertEquals(setOf("com.example.app"), result.packageNames)
    assertEquals(10001, lastQueriedUid)
  }

  @Test
  fun packageNamesAreTrimmedLowercasedAndDeduplicated() = runTest {
    val resolver =
      resolver(
        ownerUid = { 10001 },
        packageNames = {
          listOf(
            "  com.Example.App  ",
            "COM.EXAMPLE.APP",
            "com.example.app",
            "   ",
            "com.other.app",
          )
        },
      )

    val result = resolver.resolve(socket)

    assertEquals(10001, result.uid)
    assertEquals(
      setOf("com.example.app", "com.other.app"),
      result.packageNames,
    )
    assertEquals(2, result.packageNames.size)
  }

  @Test
  fun sharedUidPreservesEveryAssociatedPackage() = runTest {
    val resolver =
      resolver(
        ownerUid = { 10001 },
        packageNames = {
          listOf(
            "com.example.alpha",
            "com.example.beta",
            "com.example.gamma",
            "com.example.delta",
          )
        },
      )

    val result = resolver.resolve(socket)

    assertEquals(10001, result.uid)
    assertEquals(
      setOf(
        "com.example.alpha",
        "com.example.beta",
        "com.example.gamma",
        "com.example.delta",
      ),
      result.packageNames,
    )
    assertTrue(result.packageNames.size >= 3)
  }

  @Test
  fun inventoryFailurePreservesUidWithEmptyPackages() = runTest {
    val resolver =
      resolver(
        ownerUid = { 10001 },
        packageNames = { _ -> throw SecurityException("inventory denied") },
      )

    val result = resolver.resolve(socket)

    assertNotNull(result.uid)
    assertEquals(10001, result.uid)
    assertEquals(emptySet<String>(), result.packageNames)
  }

  @Test
  fun cancellationIsRethrown() = runTest {
    val cancellation = CancellationException("cancelled")
    val resolver =
      resolver(
        ownerUid = { 10001 },
        packageNames = { _ -> throw cancellation },
      )

    val thrown =
      try {
        resolver.resolve(socket)
        null
      } catch (error: CancellationException) {
        error
      }

    assertSame(cancellation, thrown)
  }

  private fun resolver(
    ownerUid: (Socket) -> Int?,
    packageNames: suspend (Int) -> List<String>,
  ): InspectionConnectionIdentityResolver =
    InspectionConnectionIdentityResolver(
      ownerUidForSocket = ownerUid,
      packageNamesForUid = packageNames,
    )
}
