package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionDynamicBrowserClassifierTest {
  private val selfPackage = "com.celzero.bravedns"

  @Test
  fun appBrowserCategoryQualifiesUnknownPackage() {
    val candidate =
      InspectionBrowserCapability(
        packageName = "com.example.custombrowser",
        declaresAppBrowserCategory = true,
      )

    val result =
      InspectionDynamicBrowserClassifier.classify(
        listOf(candidate),
        selfPackageName = selfPackage,
      )

    assertEquals(setOf("com.example.custombrowser"), result)
  }

  @Test
  fun genericHttpAndHttpsQualifyUnknownPackage() {
    val candidate =
      InspectionBrowserCapability(
        packageName = "com.example.weblinksbrowser",
        declaresAppBrowserCategory = false,
        handlesGenericHttp = true,
        handlesGenericHttps = true,
      )

    val result =
      InspectionDynamicBrowserClassifier.classify(
        listOf(candidate),
        selfPackageName = selfPackage,
      )

    assertEquals(setOf("com.example.weblinksbrowser"), result)
  }

  @Test
  fun splitGenericEvidenceMergesByNormalizedPackage() {
    val httpOnly =
      InspectionBrowserCapability(
        packageName = "  COM.Example.MixedBrowser  ",
        handlesGenericHttp = true,
      )
    val httpsOnly =
      InspectionBrowserCapability(
        packageName = "com.example.mixedbrowser",
        handlesGenericHttps = true,
      )
    val unrelated =
      InspectionBrowserCapability(
        packageName = "com.example.otherbrowser",
        handlesGenericHttps = true,
      )

    val result =
      InspectionDynamicBrowserClassifier.classify(
        listOf(httpOnly, httpsOnly, unrelated),
        selfPackageName = selfPackage,
      )

    assertEquals(
      setOf("com.example.mixedbrowser"),
      result,
    )
  }

  @Test
  fun incompleteGenericHandlingDoesNotQualify() {
    val httpOnly =
      InspectionBrowserCapability(
        packageName = "com.example.httponly",
        handlesGenericHttp = true,
      )
    val httpsOnly =
      InspectionBrowserCapability(
        packageName = "com.example.httpsonly",
        handlesGenericHttps = true,
      )
    val deepLinkOnly =
      InspectionBrowserCapability(
        packageName = "com.example.deeplinkonly",
        declaresAppBrowserCategory = false,
        handlesGenericHttp = false,
        handlesGenericHttps = false,
      )

    val result =
      InspectionDynamicBrowserClassifier.classify(
        listOf(httpOnly, httpsOnly, deepLinkOnly),
        selfPackageName = selfPackage,
      )

    assertTrue(result.isEmpty())
  }

  @Test
  fun knownBrowserDoesNotEnterDynamicSet() {
    val known = "com.android.chrome"
    assertTrue(InspectionKnownBrowserRegistry.isKnownBrowser(known))

    val candidate = InspectionBrowserCapability(packageName = known)

    val result =
      InspectionDynamicBrowserClassifier.classify(
        listOf(candidate),
        selfPackageName = selfPackage,
      )

    assertFalse(result.contains(known))
    assertTrue(result.isEmpty())
  }

  @Test
  fun deniedAndSelfPackagesDoNotQualify() {
    val selfCandidate = InspectionBrowserCapability(packageName = selfPackage)
    val deniedCandidates =
      InspectionDynamicBrowserClassifier.defaultDeniedPackages.map {
        InspectionBrowserCapability(
          packageName = it,
          declaresAppBrowserCategory = true,
          handlesGenericHttp = true,
          handlesGenericHttps = true,
        )
      }
    val additionalDenied =
      InspectionBrowserCapability(
        packageName = "com.example.userdenied",
        declaresAppBrowserCategory = true,
        handlesGenericHttp = true,
        handlesGenericHttps = true,
      )

    val result =
      InspectionDynamicBrowserClassifier.classify(
        listOf(selfCandidate) + deniedCandidates + listOf(additionalDenied),
        selfPackageName = selfPackage,
        additionalDeniedPackages = setOf("com.example.userdenied"),
      )

    assertFalse(result.contains(selfPackage))
    InspectionDynamicBrowserClassifier.defaultDeniedPackages.forEach {
      assertFalse(result.contains(it))
    }
    assertFalse(result.contains("com.example.userdenied"))
  }

  @Test
  fun malformedPackagesAreIgnoredAndDuplicatesCollapse() {
    val candidates =
      listOf(
        InspectionBrowserCapability(packageName = "  "),
        InspectionBrowserCapability(packageName = ""),
        InspectionBrowserCapability(packageName = "noDot"),
        InspectionBrowserCapability(
          packageName = "com.example.duplicate",
          handlesGenericHttp = true,
          handlesGenericHttps = true,
        ),
        InspectionBrowserCapability(
          packageName = "  COM.EXAMPLE.DUPLICATE  ",
          handlesGenericHttp = true,
          handlesGenericHttps = true,
        ),
        InspectionBrowserCapability(
          packageName = "com.example.distinct",
          declaresAppBrowserCategory = true,
        ),
      )

    val result =
      InspectionDynamicBrowserClassifier.classify(
        candidates,
        selfPackageName = selfPackage,
      )

    assertEquals(
      setOf("com.example.duplicate", "com.example.distinct"),
      result,
    )
  }
}
