package com.celzero.bravedns.core.proxy.policy

import java.io.InputStream
import java.util.Locale

enum class InspectionPackageRuleIssue {
    MALFORMED_PACKAGE,
    MALFORMED_UID,
    UNEXPECTED_UID
}

data class UnsupportedInspectionPackageRule(
    val lineNumber: Int,
    val rawRule: String,
    val issue: InspectionPackageRuleIssue
)

data class InspectionPackagePreset(
    val packages: Set<String>,
    val uids: Set<Int>,
    val unsupportedRules: List<UnsupportedInspectionPackageRule>
)

class InspectionPackagePresetParser {
    fun parsePackages(input: InputStream): InspectionPackagePreset =
        parse(input, allowUids = false)

    fun parsePackagesAndUids(input: InputStream): InspectionPackagePreset =
        parse(input, allowUids = true)

    private fun parse(
        input: InputStream,
        allowUids: Boolean
    ): InspectionPackagePreset {
        val packages = linkedSetOf<String>()
        val uids = linkedSetOf<Int>()
        val unsupportedRules =
            mutableListOf<UnsupportedInspectionPackageRule>()

        input.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, sourceLine ->
                val lineNumber = index + 1
                val rule = sourceLine.trim()

                if (
                    rule.isEmpty() ||
                        rule.startsWith("//") ||
                        rule.startsWith("!")
                ) {
                    return@forEachIndexed
                }

                if (rule.isIntegerToken()) {
                    if (!allowUids) {
                        unsupportedRules +=
                            UnsupportedInspectionPackageRule(
                                lineNumber = lineNumber,
                                rawRule = rule,
                                issue = InspectionPackageRuleIssue.UNEXPECTED_UID
                            )
                        return@forEachIndexed
                    }

                    val uid = rule.toIntOrNull()
                    if (uid == null || uid < 0 || rule.startsWith("+")) {
                        unsupportedRules +=
                            UnsupportedInspectionPackageRule(
                                lineNumber = lineNumber,
                                rawRule = rule,
                                issue = InspectionPackageRuleIssue.MALFORMED_UID
                            )
                    } else {
                        uids += uid
                    }
                    return@forEachIndexed
                }

                val normalizedPackage = normalizePackage(rule)
                if (!PACKAGE_PATTERN.matches(normalizedPackage)) {
                    unsupportedRules +=
                        UnsupportedInspectionPackageRule(
                            lineNumber = lineNumber,
                            rawRule = rule,
                            issue = InspectionPackageRuleIssue.MALFORMED_PACKAGE
                        )
                } else {
                    packages += normalizedPackage
                }
            }
        }

        return InspectionPackagePreset(
            packages = packages,
            uids = uids,
            unsupportedRules = unsupportedRules
        )
    }

    private fun String.isIntegerToken(): Boolean =
        INTEGER_PATTERN.matches(this)

    private fun normalizePackage(packageName: String): String =
        packageName.trim().lowercase(Locale.US)

    private companion object {
        val INTEGER_PATTERN = Regex("[+-]?\\d+")

        val PACKAGE_PATTERN =
            Regex("[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+")
    }
}