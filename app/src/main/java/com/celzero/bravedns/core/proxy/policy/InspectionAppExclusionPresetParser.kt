package com.celzero.bravedns.core.proxy.policy

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.InputStream
import java.util.Locale

enum class InspectionAppExclusionIssue {
    MALFORMED_JSON,
    ROOT_NOT_ARRAY,
    ENTRY_NOT_OBJECT,
    MISSING_PACKAGE_NAME,
    MALFORMED_PACKAGE,
    MISSING_PUBLIC_FLAG,
    MALFORMED_PUBLIC_FLAG,
    MALFORMED_OPTIONAL_FIELD,
    DUPLICATE_PACKAGE,
    UNKNOWN_FIELD
}

data class InspectionAppExclusionRule(
    val packageName: String,
    val publicIssueUrl: String?,
    val privateIssueUrl: String?,
    val comment: String?,
    val isPublic: Boolean
)

data class InspectionAppExclusionDiagnostic(
    val entryIndex: Int,
    val fieldName: String,
    val rawValue: String,
    val issue: InspectionAppExclusionIssue
)

data class InspectionAppExclusionPreset(
    val rules: List<InspectionAppExclusionRule>,
    val excludedPackages: Set<String>,
    val diagnostics: List<InspectionAppExclusionDiagnostic>
)

/**
 * Parses the JSON document describing per-application HTTPS compatibility exclusions.
 *
 * Pure-JVM, allocation-friendly, no Android dependencies. Validates required fields,
 * normalizes package names, and surfaces diagnostics for every malformed input without
 * silently dropping any entry that could not be fully validated. The parser does not
 * decide whether an exclusion is justified; URL validation and provenance validation
 * belong to later gates.
 */
class InspectionAppExclusionPresetParser {
    fun parse(input: InputStream): InspectionAppExclusionPreset {
        val payload = readPayload(input)
        val root = parseRoot(payload)
        return parseEntries(root)
    }

    private fun readPayload(input: InputStream): String =
        input.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun parseRoot(payload: String): JsonElement? =
        try {
            JsonParser.parseString(payload)
        } catch (_: JsonParseException) {
            null
        }

    private fun parseEntries(root: JsonElement?): InspectionAppExclusionPreset {
        if (root == null) {
            return diagnosticOnly(
                InspectionAppExclusionDiagnostic(
                    entryIndex = 0,
                    fieldName = ROOT_FIELD,
                    rawValue = "",
                    issue = InspectionAppExclusionIssue.MALFORMED_JSON
                )
            )
        }

        if (!root.isJsonArray) {
            return diagnosticOnly(
                InspectionAppExclusionDiagnostic(
                    entryIndex = 0,
                    fieldName = ROOT_FIELD,
                    rawValue = root.describeRaw(),
                    issue = InspectionAppExclusionIssue.ROOT_NOT_ARRAY
                )
            )
        }

        val rules = mutableListOf<InspectionAppExclusionRule>()
        val excluded = linkedSetOf<String>()
        val diagnostics = mutableListOf<InspectionAppExclusionDiagnostic>()

        val entries = root.asJsonArray
        for (index in 0 until entries.size()) {
            val entryIndex = index + 1
            val rawEntry = entries.get(index)

            if (rawEntry == null || rawEntry.isJsonNull || !rawEntry.isJsonObject) {
                diagnostics +=
                    InspectionAppExclusionDiagnostic(
                        entryIndex = entryIndex,
                        fieldName = ENTRY_FIELD,
                        rawValue = rawEntry.describeRaw(),
                        issue = InspectionAppExclusionIssue.ENTRY_NOT_OBJECT
                    )
                continue
            }

            parseEntry(rawEntry.asJsonObject, entryIndex, rules, excluded, diagnostics)
        }

        return InspectionAppExclusionPreset(
            rules = rules.toList(),
            excludedPackages = excluded.toSet(),
            diagnostics = diagnostics.toList()
        )
    }

    private fun parseEntry(
        entry: JsonObject,
        entryIndex: Int,
        rules: MutableList<InspectionAppExclusionRule>,
        excluded: MutableSet<String>,
        diagnostics: MutableList<InspectionAppExclusionDiagnostic>
    ) {
        if (!entry.has(PACKAGE_NAME_FIELD)) {
            diagnostics +=
                InspectionAppExclusionDiagnostic(
                    entryIndex = entryIndex,
                    fieldName = PACKAGE_NAME_FIELD,
                    rawValue = "",
                    issue = InspectionAppExclusionIssue.MISSING_PACKAGE_NAME
                )
            return
        }

        val rawPackageValue = entry.get(PACKAGE_NAME_FIELD)
        if (
            !rawPackageValue.isJsonPrimitive || !rawPackageValue.asJsonPrimitive.isString
        ) {
            diagnostics +=
                InspectionAppExclusionDiagnostic(
                    entryIndex = entryIndex,
                    fieldName = PACKAGE_NAME_FIELD,
                    rawValue = rawPackageValue.describeRaw(),
                    issue = InspectionAppExclusionIssue.MALFORMED_PACKAGE
                )
            return
        }
        val rawPackageString = rawPackageValue.asString

        val normalizedPackage = normalizePackage(rawPackageString)
        if (!PACKAGE_PATTERN.matches(normalizedPackage)) {
            diagnostics +=
                InspectionAppExclusionDiagnostic(
                    entryIndex = entryIndex,
                    fieldName = PACKAGE_NAME_FIELD,
                    rawValue = rawPackageString,
                    issue = InspectionAppExclusionIssue.MALFORMED_PACKAGE
                )
            return
        }

        if (!entry.has(PUBLIC_FIELD)) {
            diagnostics +=
                InspectionAppExclusionDiagnostic(
                    entryIndex = entryIndex,
                    fieldName = PUBLIC_FIELD,
                    rawValue = "",
                    issue = InspectionAppExclusionIssue.MISSING_PUBLIC_FLAG
                )
            return
        }

        val publicValue = entry.get(PUBLIC_FIELD)
        if (
            !publicValue.isJsonPrimitive || !publicValue.asJsonPrimitive.isBoolean
        ) {
            diagnostics +=
                InspectionAppExclusionDiagnostic(
                    entryIndex = entryIndex,
                    fieldName = PUBLIC_FIELD,
                    rawValue = publicValue.describeRaw(),
                    issue = InspectionAppExclusionIssue.MALFORMED_PUBLIC_FLAG
                )
            return
        }
        val publicBoolean = publicValue.asBoolean

        val publicIssueUrl =
            readOptionalString(entry, PUBLIC_ISSUE_URL_FIELD, entryIndex, diagnostics)
        val privateIssueUrl =
            readOptionalString(entry, PRIVATE_ISSUE_URL_FIELD, entryIndex, diagnostics)
        val comment = readOptionalString(entry, COMMENT_FIELD, entryIndex, diagnostics)

        if (
            publicIssueUrl == OptionalStringResult.REJECTED ||
                privateIssueUrl == OptionalStringResult.REJECTED ||
                comment == OptionalStringResult.REJECTED
        ) {
            return
        }

        if (excluded.contains(normalizedPackage)) {
            diagnostics +=
                InspectionAppExclusionDiagnostic(
                    entryIndex = entryIndex,
                    fieldName = PACKAGE_NAME_FIELD,
                    rawValue = normalizedPackage,
                    issue = InspectionAppExclusionIssue.DUPLICATE_PACKAGE
                )
            return
        }

        captureUnknownFields(entry, entryIndex, diagnostics)

        excluded += normalizedPackage
        rules +=
            InspectionAppExclusionRule(
                packageName = normalizedPackage,
                publicIssueUrl = (publicIssueUrl as OptionalStringResult.Value).value,
                privateIssueUrl = (privateIssueUrl as OptionalStringResult.Value).value,
                comment = (comment as OptionalStringResult.Value).value,
                isPublic = publicBoolean
            )
    }

    private fun readOptionalString(
        entry: JsonObject,
        fieldName: String,
        entryIndex: Int,
        diagnostics: MutableList<InspectionAppExclusionDiagnostic>
    ): OptionalStringResult {
        if (!entry.has(fieldName)) {
            return OptionalStringResult.Value(null)
        }

        val rawValue = entry.get(fieldName)
        if (rawValue.isJsonNull) {
            return OptionalStringResult.Value(null)
        }
        if (!rawValue.isJsonPrimitive || !rawValue.asJsonPrimitive.isString) {
            diagnostics +=
                InspectionAppExclusionDiagnostic(
                    entryIndex = entryIndex,
                    fieldName = fieldName,
                    rawValue = rawValue.describeRaw(),
                    issue = InspectionAppExclusionIssue.MALFORMED_OPTIONAL_FIELD
                )
            return OptionalStringResult.REJECTED
        }

        val stringValue = rawValue.asString
        val trimmed = stringValue.trim()
        return OptionalStringResult.Value(if (trimmed.isEmpty()) null else trimmed)
    }

    private fun captureUnknownFields(
        entry: JsonObject,
        entryIndex: Int,
        diagnostics: MutableList<InspectionAppExclusionDiagnostic>
    ) {
        val known = KNOWN_FIELDS
        for (key in entry.keySet()) {
            if (key !in known) {
                diagnostics +=
                    InspectionAppExclusionDiagnostic(
                        entryIndex = entryIndex,
                        fieldName = key,
                        rawValue = entry.get(key).describeRaw(),
                        issue = InspectionAppExclusionIssue.UNKNOWN_FIELD
                    )
            }
        }
    }

    private fun normalizePackage(packageName: String): String =
        packageName.trim().lowercase(Locale.US)

    private sealed class OptionalStringResult {
        data class Value(val value: String?) : OptionalStringResult()

        data object REJECTED : OptionalStringResult()
    }

    private fun JsonElement?.describeRaw(): String =
        when {
            this == null -> "null"
            this.isJsonNull -> "null"
            this.isJsonPrimitive -> {
                val primitive = this.asJsonPrimitive
                when {
                    primitive.isString -> primitive.asString
                    primitive.isBoolean -> primitive.asBoolean.toString()
                    primitive.isNumber -> primitive.asNumber.toString()
                    else -> this.toString()
                }
            }
            else -> this.toString()
        }

    private fun diagnosticOnly(
        diagnostic: InspectionAppExclusionDiagnostic
    ): InspectionAppExclusionPreset =
        InspectionAppExclusionPreset(
            rules = emptyList(),
            excludedPackages = emptySet(),
            diagnostics = listOf(diagnostic)
        )

    private companion object {
        const val ROOT_FIELD = "<root>"
        const val ENTRY_FIELD = "<entry>"
        const val PACKAGE_NAME_FIELD = "package_name"
        const val PUBLIC_FIELD = "public"
        const val PUBLIC_ISSUE_URL_FIELD = "public_issue_url"
        const val PRIVATE_ISSUE_URL_FIELD = "private_issue_url"
        const val COMMENT_FIELD = "comment"

        val KNOWN_FIELDS =
            setOf(
                PACKAGE_NAME_FIELD,
                PUBLIC_FIELD,
                PUBLIC_ISSUE_URL_FIELD,
                PRIVATE_ISSUE_URL_FIELD,
                COMMENT_FIELD
            )

        val PACKAGE_PATTERN = Regex("[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+")
    }
}
