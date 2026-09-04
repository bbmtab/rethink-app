/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.receiver

import com.celzero.bravedns.database.RefreshDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure contract tests for [BravePackageChangeReceiver.shouldRequestRefresh].
 *
 * These tests exercise only the internal pure validation function. They do
 * not require Robolectric, an Android runtime, or a WorkManager instance
 * because the function under test is a pure `String?` -> `Boolean` mapping.
 *
 * The four supported `Intent.ACTION_*` constants are referenced as
 * [android.content.Intent] class constants. In the unit-test classpath
 * these are resolved against the mockable Android jar as inlined string
 * literals (`public static final String` fields), so no Android runtime
 * methods are invoked.
 */
class BravePackageChangeReceiverTest {

    @Test
    fun packageLifecycleRefreshBypassesAutoRefreshThrottle() {
        assertEquals(
            RefreshDatabase.ACTION_REFRESH_FORCE,
            BravePackageChangeReceiver.packageChangeRefreshAction(),
        )
    }

    @Test
    fun packageAddedWithPackageNameRequestsRefresh() {
        assertTrue(
            "ACTION_PACKAGE_ADDED with a non-blank package name must request refresh",
            BravePackageChangeReceiver.shouldRequestRefresh(
                android.content.Intent.ACTION_PACKAGE_ADDED,
                "com.example.installed.app",
            ),
        )
    }

    @Test
    fun packageRemovedWithPackageNameRequestsRefresh() {
        assertTrue(
            "ACTION_PACKAGE_REMOVED with a non-blank package name must request refresh",
            BravePackageChangeReceiver.shouldRequestRefresh(
                android.content.Intent.ACTION_PACKAGE_REMOVED,
                "com.example.uninstalled.app",
            ),
        )
    }

    @Test
    fun packageReplacedWithPackageNameRequestsRefresh() {
        assertTrue(
            "ACTION_PACKAGE_REPLACED with a non-blank package name must request refresh",
            BravePackageChangeReceiver.shouldRequestRefresh(
                android.content.Intent.ACTION_PACKAGE_REPLACED,
                "com.example.upgraded.app",
            ),
        )
    }

    @Test
    fun packageChangedWithPackageNameRequestsRefresh() {
        assertTrue(
            "ACTION_PACKAGE_CHANGED with a non-blank package name must request refresh",
            BravePackageChangeReceiver.shouldRequestRefresh(
                android.content.Intent.ACTION_PACKAGE_CHANGED,
                "com.example.changed.app",
            ),
        )
    }

    @Test
    fun packageFullyRemovedWithPackageNameRequestsRefresh() {
        assertTrue(
            "ACTION_PACKAGE_FULLY_REMOVED with a non-blank package name must request refresh",
            BravePackageChangeReceiver.shouldRequestRefresh(
                android.content.Intent.ACTION_PACKAGE_FULLY_REMOVED,
                "dev.example.removed",
            ),
        )
    }

    @Test
    fun unrelatedActionDoesNotRequestRefresh() {
        assertFalse(
            "An unrelated action must not request refresh even with a valid package name",
            BravePackageChangeReceiver.shouldRequestRefresh(
                "android.intent.action.BOOT_COMPLETED",
                "com.example.app",
            ),
        )
    }

    @Test
    fun missingActionDoesNotRequestRefresh() {
        assertFalse(
            "A null action must not request refresh even with a valid package name",
            BravePackageChangeReceiver.shouldRequestRefresh(null, "com.example.app"),
        )
    }

    @Test
    fun missingPackageNameDoesNotRequestRefresh() {
        assertFalse(
            "A null package name must not request refresh even with a supported action",
            BravePackageChangeReceiver.shouldRequestRefresh(
                android.content.Intent.ACTION_PACKAGE_ADDED,
                null,
            ),
        )
    }

    @Test
    fun blankPackageNameDoesNotRequestRefresh() {
        assertFalse(
            "A blank package name must not request refresh even with a supported action",
            BravePackageChangeReceiver.shouldRequestRefresh(
                android.content.Intent.ACTION_PACKAGE_ADDED,
                "   ",
            ),
        )
    }
}
