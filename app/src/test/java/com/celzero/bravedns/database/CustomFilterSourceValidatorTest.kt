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
package com.celzero.bravedns.database

import com.celzero.bravedns.database.CustomFilterSourceValidator.Error
import com.celzero.bravedns.database.CustomFilterSourceValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CustomFilterSourceValidator]. Pure JVM — no Robolectric needed since the
 * validator uses only java.net.URI and java.util.Locale.
 */
class CustomFilterSourceValidatorTest {

    private fun validate(name: String, url: String) =
        CustomFilterSourceValidator.validate(name, url)

    @Test
    fun validate_trimsNameAndUrl() {
        val result = validate("  My Blocklist  ", "   https://example.com/list.txt\t")
        val valid = result as Result.Valid
        assertEquals("My Blocklist", valid.name)
        assertEquals("https://example.com/list.txt", valid.url)
    }

    @Test
    fun validate_emptyName_returnsEmptyName() {
        val result = validate("   ", "https://example.com/list.txt")
        assertEquals(Error.EMPTY_NAME, (result as Result.Invalid).error)
    }

    @Test
    fun validate_emptyUrl_returnsEmptyUrl() {
        val result = validate("My List", " \t ")
        assertEquals(Error.EMPTY_URL, (result as Result.Invalid).error)
    }

    @Test
    fun validate_malformedUrl_returnsInvalidUrl() {
        // Space inside the authority makes java.net.URI throw URISyntaxException.
        val result = validate("My List", "https://exa mple.com/list.txt")
        assertEquals(Error.INVALID_URL, (result as Result.Invalid).error)
    }

    @Test
    fun validate_relativeUrl_returnsInvalidUrl() {
        val result = validate("My List", "filters/list.txt")
        assertEquals(Error.INVALID_URL, (result as Result.Invalid).error)
    }

    @Test
    fun validate_unsupportedScheme_returnsUnsupportedScheme() {
        val result = validate("My List", "ftp://example.com/list.txt")
        assertEquals(Error.UNSUPPORTED_SCHEME, (result as Result.Invalid).error)
    }

    @Test
    fun validate_missingHost_returnsMissingHost() {
        val result = validate("My List", "https:///list.txt")
        assertEquals(Error.MISSING_HOST, (result as Result.Invalid).error)
    }

    @Test
    fun validate_httpsUrlWithQuery_isValid() {
        val url = "https://example.com/path/list.txt?format=raw&level=2"
        val result = validate("  Query List  ", "  $url  ")
        val valid = result as Result.Valid
        assertEquals("Query List", valid.name)
        assertEquals("URL must be preserved exactly after outer trim", url, valid.url)
    }

    @Test
    fun validate_fragment_returnsFragmentNotAllowed() {
        val result = validate("My List", "https://example.com/list.txt#section")
        assertEquals(Error.FRAGMENT_NOT_ALLOWED, (result as Result.Invalid).error)
    }
}
