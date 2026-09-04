package com.celzero.bravedns.core.proxy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionKnownBrowserRegistryTest {
  @Test
  fun registryContainsExactlyTheSealedPackages() {
    assertEquals(144, InspectionKnownBrowserRegistry.packages.size)
  }

  @Test
  fun commonAndIndependentlyVerifiedBrowsersAreKnown() {
    val packages =
      listOf(
        "com.android.chrome",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "com.ucmobile.intl",
        "com.cloudmosa.puffinfree",
        "com.mi.globalbrowser",
        "com.transsion.phoenix",
        "com.heytap.browser",
        "com.apusapps.browser",
        "br.marcelo.monumentbrowser",
        "net.onecook.browser",
        "jp.ejimax.berrybrowser",
        "info.plateaukao.einkbro",
      )

    packages.forEach {
      assertTrue(it, InspectionKnownBrowserRegistry.isKnownBrowser(it))
    }
  }

  @Test
  fun lookupTrimsAndNormalizesPackageName() {
    assertTrue(
      InspectionKnownBrowserRegistry.isKnownBrowser("  COM.ANDROID.CHROME  ")
    )
    assertTrue(
      InspectionKnownBrowserRegistry.isKnownBrowser("Com.UCMobile.Intl")
    )
    assertFalse(InspectionKnownBrowserRegistry.isKnownBrowser(null))
    assertFalse(InspectionKnownBrowserRegistry.isKnownBrowser("   "))
  }

  @Test
  fun nonBrowserAndEmbeddedWebComponentsAreNotKnownBrowsers() {
    val rejected =
      listOf(
        "app.vanadium.webview",
        "com.android.htmlviewer",
        "com.google.android.apps.searchlite",
        "com.google.android.captiveportallogin",
        "com.microsoft.amp.apps.bingnews",
        "idm.internet.download.manager",
        "idm.internet.download.manager.adm.lite",
        "idm.internet.download.manager.plus",
        "org.chromium.webview_shell",
        "org.cromite.webview",
        "ru.yandex.searchplugin",
      )

    rejected.forEach {
      assertFalse(it, InspectionKnownBrowserRegistry.isKnownBrowser(it))
    }
  }

  @Test
  fun registryEntriesAreCanonicalPackageNames() {
    val packagePattern =
      Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    InspectionKnownBrowserRegistry.packages.forEach {
      assertEquals(it.trim().lowercase(), it)
      assertTrue(it, packagePattern.matches(it))
    }
  }
}
