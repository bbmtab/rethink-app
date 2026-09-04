package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionBrowserRuntimePackageResolverTest {
  private val selfPackage = "com.celzero.bravedns"

  @Test
  fun knownRegistryIsAlwaysAllowedWhenNoDynamicEvidenceExists() {
    val result =
      InspectionBrowserRuntimePackageResolver.resolve(
        capabilities = emptyList(),
        selfPackageName = selfPackage,
      )

    assertEquals(
      InspectionKnownBrowserRegistry.packages,
      result.knownBrowserPackages,
    )
    assertTrue(result.enabledDynamicBrowserPackages.isEmpty())
    assertEquals(
      InspectionKnownBrowserRegistry.packages,
      result.allowedPackages,
    )
  }

  @Test
  fun qualifyingUnknownBrowserFlowsIntoEnabledDynamicPackages() {
    val result =
      InspectionBrowserRuntimePackageResolver.resolve(
        capabilities =
          listOf(
            InspectionBrowserCapability(
              packageName = "com.example.unknownbrowser",
              declaresAppBrowserCategory = true,
            ),
          ),
        selfPackageName = selfPackage,
      )

    assertEquals(
      setOf("com.example.unknownbrowser"),
      result.enabledDynamicBrowserPackages,
    )
    assertTrue(
      result.allowedPackages.contains("com.example.unknownbrowser"),
    )
    assertTrue(
      result.allowedPackages.contains("com.android.chrome"),
    )
  }

  @Test
  fun knownBrowserRemainsKnownAndIsNotDuplicatedAsDynamic() {
    val result =
      InspectionBrowserRuntimePackageResolver.resolve(
        capabilities =
          listOf(
            InspectionBrowserCapability(
              packageName = "com.android.chrome",
              declaresAppBrowserCategory = true,
              handlesGenericHttp = true,
              handlesGenericHttps = true,
            ),
          ),
        selfPackageName = selfPackage,
      )

    assertTrue(result.knownBrowserPackages.contains("com.android.chrome"))
    assertFalse(
      result.enabledDynamicBrowserPackages.contains("com.android.chrome"),
    )
    assertEquals(
      InspectionKnownBrowserRegistry.packages.size,
      result.allowedPackages.size,
    )
  }

  @Test
  fun selfAndDeniedPackagesNeverEnterDynamicPackages() {
    val result =
      InspectionBrowserRuntimePackageResolver.resolve(
        capabilities =
          listOf(
            InspectionBrowserCapability(
              packageName = selfPackage,
              declaresAppBrowserCategory = true,
            ),
            InspectionBrowserCapability(
              packageName = "com.google.android.webview",
              declaresAppBrowserCategory = true,
            ),
            InspectionBrowserCapability(
              packageName = "com.example.deniedbrowser",
              declaresAppBrowserCategory = true,
            ),
          ),
        selfPackageName = selfPackage,
        additionalDeniedPackages =
          setOf("com.example.deniedbrowser"),
      )

    assertTrue(result.enabledDynamicBrowserPackages.isEmpty())
    assertFalse(result.allowedPackages.contains(selfPackage))
    assertFalse(
      result.allowedPackages.contains("com.google.android.webview"),
    )
    assertFalse(
      result.allowedPackages.contains("com.example.deniedbrowser"),
    )
  }

  @Test
  fun incompleteGenericEvidenceDoesNotEnableUnknownApplications() {
    val result =
      InspectionBrowserRuntimePackageResolver.resolve(
        capabilities =
          listOf(
            InspectionBrowserCapability(
              packageName = "com.example.httponly",
              handlesGenericHttp = true,
            ),
            InspectionBrowserCapability(
              packageName = "com.example.httpsonly",
              handlesGenericHttps = true,
            ),
            InspectionBrowserCapability(
              packageName = "com.example.deeplink",
            ),
          ),
        selfPackageName = selfPackage,
      )

    assertTrue(result.enabledDynamicBrowserPackages.isEmpty())
    assertFalse(result.allowedPackages.contains("com.example.httponly"))
    assertFalse(result.allowedPackages.contains("com.example.httpsonly"))
    assertFalse(result.allowedPackages.contains("com.example.deeplink"))
  }

  @Test
  fun splitGenericEvidenceMergesBeforeRuntimeUnion() {
    val result =
      InspectionBrowserRuntimePackageResolver.resolve(
        capabilities =
          listOf(
            InspectionBrowserCapability(
              packageName = "  COM.Example.MixedBrowser  ",
              handlesGenericHttp = true,
            ),
            InspectionBrowserCapability(
              packageName = "com.example.mixedbrowser",
              handlesGenericHttps = true,
            ),
          ),
        selfPackageName = selfPackage,
      )

    assertEquals(
      setOf("com.example.mixedbrowser"),
      result.enabledDynamicBrowserPackages,
    )
    assertTrue(
      result.allowedPackages.contains("com.example.mixedbrowser"),
    )
  }
}