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

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Pure Kotlin validator for user-entered custom filter-source name and subscription URL.
 *
 * Validation only: no duplicate checking, no canonicalization of valid input, no I/O.
 * A valid URL is returned exactly as entered after surrounding-whitespace trim — query
 * parameters and user info are preserved verbatim. Fragments are rejected because they
 * are never sent to the server in HTTP requests. Only `http` and `https` schemes are
 * accepted since filter lists are downloaded over HTTP(S).
 */
object CustomFilterSourceValidator {

    enum class Error {
        EMPTY_NAME,
        EMPTY_URL,
        INVALID_URL,
        UNSUPPORTED_SCHEME,
        MISSING_HOST,
        FRAGMENT_NOT_ALLOWED
    }

    sealed interface Result {
        data class Valid(
            val name: String,
            val url: String
        ) : Result

        data class Invalid(
            val error: Error
        ) : Result
    }

    /**
     * Validate [name] and [url] for custom source creation. Never throws for ordinary
     * invalid input; returns [Result.Invalid] with the first failing check in order:
     * empty name → empty URL → unparseable/non-absolute URL → non-HTTP(S) scheme →
     * missing host → fragment present.
     */
    fun validate(name: String, url: String): Result {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return Result.Invalid(Error.EMPTY_NAME)
        }

        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            return Result.Invalid(Error.EMPTY_URL)
        }

        val uri = try {
            URI(trimmedUrl)
        } catch (_: URISyntaxException) {
            return Result.Invalid(Error.INVALID_URL)
        }
        if (!uri.isAbsolute) {
            return Result.Invalid(Error.INVALID_URL)
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid(Error.UNSUPPORTED_SCHEME)
        }

        if (uri.host.isNullOrBlank()) {
            return Result.Invalid(Error.MISSING_HOST)
        }

        if (uri.rawFragment != null) {
            return Result.Invalid(Error.FRAGMENT_NOT_ALLOWED)
        }

        return Result.Valid(name = trimmedName, url = trimmedUrl)
    }
}
