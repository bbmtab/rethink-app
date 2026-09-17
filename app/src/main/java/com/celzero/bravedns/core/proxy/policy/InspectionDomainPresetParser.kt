package com.celzero.bravedns.core.proxy.policy

import java.io.InputStream
import java.net.IDN
import java.util.Locale

enum class InspectionDomainRuleIssue {
    MALFORMED_RULE,
    UNSUPPORTED_WILDCARD,
    UNSUPPORTED_MODIFIER
}

data class UnsupportedInspectionDomainRule(
    val lineNumber: Int,
    val rawRule: String,
    val issue: InspectionDomainRuleIssue
)

data class InspectionDomainPreset(
    val protectedDomains: Set<String>,
    val protectedDomainsByPackage: Map<String, Set<String>>,
    val unsupportedRules: List<UnsupportedInspectionDomainRule>
)

object InspectionDomainPresetParser {
    private const val APP_MARKER = "\$app="

    private val packagePattern =
        Regex("[a-z0-9_]+(?:\\.[a-z0-9_]+)+")

    fun parse(inputStream: InputStream): InspectionDomainPreset =
        inputStream.bufferedReader().use { reader ->
            parseLines(reader.lineSequence())
        }

    private fun parseLines(
        lines: Sequence<String>
    ): InspectionDomainPreset {
        val globalDomains = linkedSetOf<String>()
        val packageDomains =
            linkedMapOf<String, MutableSet<String>>()
        val unsupported =
            mutableListOf<UnsupportedInspectionDomainRule>()

        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val rule = rawLine.trim()

            if (
                rule.isEmpty() ||
                    rule.startsWith("//") ||
                    rule.startsWith("!")
            ) {
                return@forEachIndexed
            }

            if ('*' in rule) {
                unsupported +=
                    unsupported(
                        lineNumber,
                        rule,
                        InspectionDomainRuleIssue.UNSUPPORTED_WILDCARD
                    )
                return@forEachIndexed
            }

            val markerIndex = rule.indexOf(APP_MARKER)
            if ('$' in rule && markerIndex < 0) {
                unsupported +=
                    unsupported(
                        lineNumber,
                        rule,
                        InspectionDomainRuleIssue.UNSUPPORTED_MODIFIER
                    )
                return@forEachIndexed
            }

            if (
                markerIndex >= 0 &&
                    rule.indexOf(
                        '$',
                        markerIndex + APP_MARKER.length
                    ) >= 0
            ) {
                unsupported +=
                    unsupported(
                        lineNumber,
                        rule,
                        InspectionDomainRuleIssue.UNSUPPORTED_MODIFIER
                    )
                return@forEachIndexed
            }

            val rawDomain =
                if (markerIndex >= 0) {
                    rule.substring(0, markerIndex)
                } else {
                    rule
                }
            val domain = normalizeDomain(rawDomain)

            if (domain == null) {
                unsupported +=
                    unsupported(
                        lineNumber,
                        rule,
                        InspectionDomainRuleIssue.MALFORMED_RULE
                    )
                return@forEachIndexed
            }

            if (markerIndex < 0) {
                globalDomains += domain
                return@forEachIndexed
            }

            val packageName =
                rule.substring(markerIndex + APP_MARKER.length)
                    .trim()
                    .lowercase(Locale.US)

            if (!packagePattern.matches(packageName)) {
                unsupported +=
                    unsupported(
                        lineNumber,
                        rule,
                        InspectionDomainRuleIssue.MALFORMED_RULE
                    )
                return@forEachIndexed
            }

            packageDomains
                .getOrPut(packageName) { linkedSetOf() }
                .add(domain)
        }

        return InspectionDomainPreset(
            protectedDomains = globalDomains.toSet(),
            protectedDomainsByPackage =
                packageDomains.mapValues { (_, domains) ->
                    domains.toSet()
                },
            unsupportedRules = unsupported.toList()
        )
    }

    private fun normalizeDomain(rawDomain: String): String? {
        val domain = rawDomain.trim().trimEnd('.')
        if (domain.isEmpty()) return null

        return try {
            IDN.toASCII(
                domain,
                IDN.USE_STD3_ASCII_RULES
            ).lowercase(Locale.US)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun unsupported(
        lineNumber: Int,
        rawRule: String,
        issue: InspectionDomainRuleIssue
    ): UnsupportedInspectionDomainRule =
        UnsupportedInspectionDomainRule(
            lineNumber = lineNumber,
            rawRule = rawRule,
            issue = issue
        )
}