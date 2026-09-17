package com.celzero.bravedns.core.proxy.policy

import java.util.Locale

/**
 * Per-package evidence gathered by the PackageManager adapter (out of scope for this contract).
 *
 * Holds three independent boolean flags describing how an installed application declares itself a
 * browser. The classifier merges duplicate entries for the same normalized package using OR.
 */
data class InspectionBrowserCapability(
  val packageName: String,
  val declaresAppBrowserCategory: Boolean = false,
  val handlesGenericHttp: Boolean = false,
  val handlesGenericHttps: Boolean = false,
)

/**
 * Pure-JVM classifier that decides which unknown packages may enter
 * `enabledDynamicBrowserPackages`.
 *
 * Rules:
 *  - Package names are trim/lowercase normalized with `Locale.US`.
 *  - At least two dot-separated components are required (canonical Android-style identifiers).
 *  - Duplicate entries merge with OR across all three capability flags.
 *  - A package qualifies when either `declaresAppBrowserCategory` is true, OR both
 *    `handlesGenericHttp` and `handlesGenericHttps` are true.
 *  - Rejected: malformed/blank names, the application's own package, `defaultDeniedPackages`,
 *    the caller's `additionalDeniedPackages`, and any package already recognized by
 *    [InspectionKnownBrowserRegistry].
 *  - The result is a deterministic, insertion-ordered [LinkedHashSet] with no duplicates.
 *
 * This object does not query the platform. It does not modify the engine, the snapshot factory,
 * or the known browser registry. User/system/compatibility/domain/app-port precedence remains
 * an engine responsibility and is not implemented here.
 */
object InspectionDynamicBrowserClassifier {
  val defaultDeniedPackages: Set<String> =
    linkedSetOf(
      "com.android.htmlviewer",
      "com.android.captiveportallogin",
      "com.google.android.captiveportallogin",
      "com.google.android.webview",
      "com.google.android.apps.searchlite",
      "com.microsoft.bing",
      "com.microsoft.amp.apps.bingnews",
      "com.yandex.searchplugin",
      "idm.internet.download.manager",
      "idm.internet.download.manager.adm.lite",
      "org.cromite.webview",
    )

  private val canonicalPackagePattern = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
  private val deniedLookup: Set<String> = defaultDeniedPackages

  fun classify(
    candidates: Iterable<InspectionBrowserCapability>,
    selfPackageName: String,
    additionalDeniedPackages: Set<String> = emptySet(),
  ): Set<String> {
    val selfNormalized = normalizeOrNull(selfPackageName)
    val extraDenied =
      additionalDeniedPackages.mapNotNull { normalizeOrNull(it) }.toSet()

    // Merge duplicate capability records by normalized package name using Boolean OR.
    val merged = LinkedHashMap<String, MutableFlags>()
    for (capability in candidates) {
      val key = normalizeOrNull(capability.packageName) ?: continue
      val flags = merged.getOrPut(key) { MutableFlags() }
      flags.appBrowser = flags.appBrowser || capability.declaresAppBrowserCategory
      flags.http = flags.http || capability.handlesGenericHttp
      flags.https = flags.https || capability.handlesGenericHttps
    }

    val result = LinkedHashSet<String>()
    for ((packageName, flags) in merged) {
      if (!canonicalPackagePattern.matches(packageName)) continue
      if (packageName == selfNormalized) continue
      if (packageName in deniedLookup) continue
      if (packageName in extraDenied) continue
      if (InspectionKnownBrowserRegistry.isKnownBrowser(packageName)) continue

      val qualifies = flags.appBrowser || (flags.http && flags.https)
      if (qualifies) result.add(packageName)
    }
    return result
  }

  private fun normalizeOrNull(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return trimmed.lowercase(Locale.US)
  }

  private data class MutableFlags(
    var appBrowser: Boolean = false,
    var http: Boolean = false,
    var https: Boolean = false,
  )
}
