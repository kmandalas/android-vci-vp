package com.example.eudiwemu.util

import com.example.eudiwemu.dto.ClaimMetadata

/**
 * Resolves display names and grouping for claims based on issuer-provided metadata.
 * Used to replace hardcoded claim label/group mappings with dynamic, metadata-driven lookups.
 */
class ClaimMetadataResolver(private val claimsMetadata: List<ClaimMetadata>) {

    /**
     * Resolve display name for a claim identified by its full path.
     * Falls back to the last segment of the path if no metadata match is found.
     */
    fun getDisplayName(path: List<String>, locale: String = "en"): String {
        val metadata = claimsMetadata.find { it.path == path }
        if (metadata != null) {
            val localeMatch = metadata.display?.find { it.locale == locale }
            val anyDisplay = localeMatch ?: metadata.display?.firstOrNull()
            if (anyDisplay != null) return anyDisplay.name
        }
        return path.lastOrNull()?.formatAsLabel() ?: ""
    }

    /**
     * Resolve display name for a leaf claim given just its claim name (last path segment).
     * Searches all metadata entries for a matching last path element.
     */
    fun getDisplayNameByClaimName(claimName: String, locale: String = "en"): String {
        val metadata = claimsMetadata.find { it.path.lastOrNull() == claimName }
        if (metadata != null) {
            val localeMatch = metadata.display?.find { it.locale == locale }
            val anyDisplay = localeMatch ?: metadata.display?.firstOrNull()
            if (anyDisplay != null) return anyDisplay.name
        }
        return claimName.formatAsLabel()
    }

    /**
     * Find all leaf claim metadata entries under a given parent prefix.
     * E.g., parentPath=["credential_holder"] returns entries with paths like
     * ["credential_holder", "family_name"], ["credential_holder", "given_name"], etc.
     */
    fun getChildClaims(parentPath: List<String>): List<ClaimMetadata> {
        return claimsMetadata.filter { claim ->
            claim.path.size > parentPath.size &&
            claim.path.subList(0, parentPath.size) == parentPath
        }
    }

    /**
     * Get all child claim names (last path segment) for a given parent name.
     */
    fun getChildClaimNames(parentName: String): Set<String> {
        return getChildClaims(listOf(parentName))
            .mapNotNull { it.path.lastOrNull() }
            .toSet()
    }

    /**
     * Given DCQL requested paths, return all matching claim metadata entries using prefix matching.
     * A requested path like ["credential_holder"] matches all entries whose path starts with that prefix.
     */
    fun matchRequestedClaims(dcqlPaths: List<List<String>>): List<ClaimMetadata> {
        return claimsMetadata.filter { claim ->
            dcqlPaths.any { requestedPath ->
                claim.path.size >= requestedPath.size &&
                claim.path.subList(0, requestedPath.size) == requestedPath
            }
        }
    }

    /**
     * Build a grouped structure: parent name -> list of child ClaimMetadata.
     * Groups are determined by the first path element of each claim.
     */
    fun groupByParent(): Map<String, List<ClaimMetadata>> {
        return claimsMetadata
            .filter { it.path.size >= 2 }
            .groupBy { it.path.first() }
    }

    /**
     * Get unique parent names (first path element) from all claims.
     */
    fun getParentNames(): Set<String> {
        return claimsMetadata
            .filter { it.path.size >= 2 }
            .map { it.path.first() }
            .toSet()
    }

    /**
     * Get a human-readable label for a parent group name.
     * Combines the display names of all children under that parent.
     */
    fun getParentDisplayLabel(parentName: String, locale: String = "en"): String {
        val children = getChildClaims(listOf(parentName))
        if (children.isEmpty()) return parentName.formatAsLabel()

        val childLabels = children.mapNotNull { child ->
            val display = child.display?.find { it.locale == locale }
                ?: child.display?.firstOrNull()
            display?.name
        }
        val parentLabel = parentName.formatAsLabel()
        return if (childLabels.isNotEmpty()) {
            "$parentLabel (${childLabels.joinToString(", ") { it.lowercase() }})"
        } else {
            parentLabel
        }
    }

    companion object {
        /**
         * Creates a resolver, or null if no metadata is available.
         */
        fun fromNullable(claimsMetadata: List<ClaimMetadata>?): ClaimMetadataResolver? {
            return claimsMetadata?.let { ClaimMetadataResolver(it) }
        }
    }
}

/**
 * Convert a snake_case string to a human-readable label.
 * E.g., "family_name" -> "Family Name", "credential_holder" -> "Credential Holder"
 */
private fun String.formatAsLabel(): String {
    return split("_").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
}
