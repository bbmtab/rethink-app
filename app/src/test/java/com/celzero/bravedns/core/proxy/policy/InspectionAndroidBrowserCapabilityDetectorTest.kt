package com.celzero.bravedns.core.proxy.policy

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InspectionAndroidBrowserCapabilityDetectorTest {
  private val selfPackage = "com.celzero.bravedns"

  @Test
  fun packageManagerQueriesAreNotLimitedToDefaultHandlers() {
    assertEquals(
      0L,
      InspectionAndroidBrowserCapabilityDetector.BROWSER_CAPABILITY_QUERY_FLAGS,
    )
  }

  @Test
  fun queriesAppBrowserAndTwoGenericWebProbes() {
    val seen = mutableListOf<Intent>()
    val detector =
      InspectionAndroidBrowserCapabilityDetector { intent ->
        seen.add(Intent(intent))
        emptyList()
      }

    detector.detect()

    assertEquals(3, seen.size)

    val appBrowser = seen[0]
    assertEquals(Intent.ACTION_MAIN, appBrowser.selector?.action)
    assertTrue(
      appBrowser.selector
        ?.categories
        ?.contains(Intent.CATEGORY_APP_BROWSER) == true,
    )

    val http = seen[1]
    assertEquals(Intent.ACTION_VIEW, http.action)
    assertEquals("http://example.com/", http.data.toString())
    assertTrue(http.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)

    val https = seen[2]
    assertEquals(Intent.ACTION_VIEW, https.action)
    assertEquals("https://example.org/", https.data.toString())
    assertTrue(https.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
  }

  @Test
  fun appBrowserHandlerProducesCapability() {
    val detector =
      detector { kind ->
        if (kind == QueryKind.APP_BROWSER) {
          listOf(resolve("com.example.appbrowser"))
        } else {
          emptyList()
        }
      }

    assertEquals(
      listOf(
        InspectionBrowserCapability(
          packageName = "com.example.appbrowser",
          declaresAppBrowserCategory = true,
        ),
      ),
      detector.detect(),
    )
  }

  @Test
  fun httpAndHttpsEvidenceMergesForSamePackage() {
    val detector =
      detector { kind ->
        when (kind) {
          QueryKind.HTTP,
          QueryKind.HTTPS -> listOf(resolve("com.example.genericbrowser"))
          QueryKind.APP_BROWSER -> emptyList()
        }
      }

    assertEquals(
      listOf(
        InspectionBrowserCapability(
          packageName = "com.example.genericbrowser",
          handlesGenericHttp = true,
          handlesGenericHttps = true,
        ),
      ),
      detector.detect(),
    )
  }

  @Test
  fun evidenceNeverCrossesPackageBoundaries() {
    val detector =
      detector { kind ->
        when (kind) {
          QueryKind.HTTP -> listOf(resolve("com.example.httponly"))
          QueryKind.HTTPS -> listOf(resolve("com.example.httpsonly"))
          QueryKind.APP_BROWSER -> emptyList()
        }
      }

    val detected = detector.detect()
    val enabled =
      InspectionDynamicBrowserClassifier.classify(
        detected,
        selfPackageName = selfPackage,
      )

    assertEquals(2, detected.size)
    assertTrue(enabled.isEmpty())
  }

  @Test
  fun duplicateActivitiesCollapseInFirstSeenOrder() {
    val detector =
      detector { kind ->
        when (kind) {
          QueryKind.APP_BROWSER ->
            listOf(
              resolve("com.example.alpha"),
              resolve("com.example.alpha"),
              resolve("com.example.beta"),
            )
          QueryKind.HTTP -> listOf(resolve("com.example.alpha"))
          QueryKind.HTTPS -> emptyList()
        }
      }

    val detected = detector.detect()

    assertEquals(
      listOf("com.example.alpha", "com.example.beta"),
      detected.map { it.packageName },
    )
    assertTrue(detected[0].declaresAppBrowserCategory)
    assertTrue(detected[0].handlesGenericHttp)
    assertFalse(detected[0].handlesGenericHttps)
  }

  @Test
  fun malformedResolveInfoEntriesAreIgnored() {
    val detector =
      InspectionAndroidBrowserCapabilityDetector {
        listOf(
          ResolveInfo(),
          resolve("  "),
          resolve("  COM.Example.ValidBrowser  "),
        )
      }

    val detected = detector.detect()

    assertEquals(1, detected.size)
    assertEquals("com.example.validbrowser", detected.single().packageName)
    assertTrue(detected.single().declaresAppBrowserCategory)
    assertTrue(detected.single().handlesGenericHttp)
    assertTrue(detected.single().handlesGenericHttps)
  }

  @Test
  fun queryFailureIsIsolatedAndFailsClosed() {
    val detector =
      detector { kind ->
        when (kind) {
          QueryKind.APP_BROWSER -> listOf(resolve("com.example.appbrowser"))
          QueryKind.HTTP -> throw SecurityException("query denied")
          QueryKind.HTTPS -> listOf(resolve("com.example.httpsonly"))
        }
      }

    val detected = detector.detect()
    val enabled =
      InspectionDynamicBrowserClassifier.classify(
        detected,
        selfPackageName = selfPackage,
      )

    assertEquals(setOf("com.example.appbrowser"), enabled)
    assertFalse(enabled.contains("com.example.httpsonly"))
  }

  private fun detector(
    answer: (QueryKind) -> List<ResolveInfo>,
  ): InspectionAndroidBrowserCapabilityDetector =
    InspectionAndroidBrowserCapabilityDetector { intent ->
      answer(queryKind(intent))
    }

  private fun queryKind(intent: Intent): QueryKind {
    val selector = intent.selector
    if (
      selector?.action == Intent.ACTION_MAIN &&
        selector.categories?.contains(Intent.CATEGORY_APP_BROWSER) == true
    ) {
      return QueryKind.APP_BROWSER
    }

    return when (intent.data?.scheme) {
      "http" -> QueryKind.HTTP
      "https" -> QueryKind.HTTPS
      else -> error("Unexpected browser capability query: $intent")
    }
  }

  private fun resolve(packageName: String?): ResolveInfo =
    ResolveInfo().apply {
      if (packageName != null) {
        activityInfo =
          ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.MainActivity"
          }
      }
    }

  private enum class QueryKind {
    APP_BROWSER,
    HTTP,
    HTTPS,
  }
}
