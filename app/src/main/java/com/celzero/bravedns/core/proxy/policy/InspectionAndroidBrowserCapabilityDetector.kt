package com.celzero.bravedns.core.proxy.policy

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import java.util.Locale

/**
 * Android boundary that gathers browser-capability evidence from PackageManager.
 *
 * Classification remains owned by [InspectionDynamicBrowserClassifier].
 */
class InspectionAndroidBrowserCapabilityDetector
internal constructor(
  private val queryIntentActivities: (Intent) -> List<ResolveInfo>,
) {
  constructor(packageManager: PackageManager) :
    this(
      queryIntentActivities = { intent ->
        queryPackageManager(packageManager, intent)
      },
    )

  fun detect(): List<InspectionBrowserCapability> {
    val appBrowserPackages = queryPackages(appBrowserIntent())
    val genericHttpPackages = queryPackages(genericWebIntent(HTTP_PROBE_URL))
    val genericHttpsPackages = queryPackages(genericWebIntent(HTTPS_PROBE_URL))

    val orderedPackages = LinkedHashSet<String>()
    orderedPackages.addAll(appBrowserPackages)
    orderedPackages.addAll(genericHttpPackages)
    orderedPackages.addAll(genericHttpsPackages)

    return orderedPackages.map { packageName ->
      InspectionBrowserCapability(
        packageName = packageName,
        declaresAppBrowserCategory = packageName in appBrowserPackages,
        handlesGenericHttp = packageName in genericHttpPackages,
        handlesGenericHttps = packageName in genericHttpsPackages,
      )
    }
  }

  private fun queryPackages(intent: Intent): Set<String> {
    val resolveInfos =
      try {
        queryIntentActivities(intent)
      } catch (_: RuntimeException) {
        emptyList()
      }

    val packages = LinkedHashSet<String>()
    for (resolveInfo in resolveInfos) {
      val packageName =
        resolveInfo.activityInfo
          ?.packageName
          ?.trim()
          ?.lowercase(Locale.US)
          .orEmpty()
      if (packageName.isNotEmpty()) {
        packages.add(packageName)
      }
    }
    return packages
  }

  companion object {
    internal const val BROWSER_CAPABILITY_QUERY_FLAGS = 0L
    internal const val HTTP_PROBE_URL = "http://example.com/"
    internal const val HTTPS_PROBE_URL = "https://example.org/"

    internal fun appBrowserIntent(): Intent =
      Intent.makeMainSelectorActivity(
        Intent.ACTION_MAIN,
        Intent.CATEGORY_APP_BROWSER,
      )

    internal fun genericWebIntent(url: String): Intent =
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
      }

    @Suppress("DEPRECATION")
    private fun queryPackageManager(
      packageManager: PackageManager,
      intent: Intent,
    ): List<ResolveInfo> {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
          intent,
          PackageManager.ResolveInfoFlags.of(
            BROWSER_CAPABILITY_QUERY_FLAGS,
          ),
        )
      } else {
        packageManager.queryIntentActivities(
          intent,
          BROWSER_CAPABILITY_QUERY_FLAGS.toInt(),
        )
      }
    }
  }
}
