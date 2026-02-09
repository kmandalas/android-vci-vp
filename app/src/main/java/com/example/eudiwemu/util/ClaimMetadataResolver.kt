package com.example.eudiwemu.util

import com.example.eudiwemu.dto.ClaimMetadata

/**
 * Resolves display names and grouping for claims based on issuer-provided metadata.
 * Used to replace hardcoded claim label/group mappings with dynamic, metadata-driven lookups.
 */
class ClaimMetadataResolver(private val claimsMetadata: List<ClaimMetadata>) {

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
     * Works with both SD-JWT paths (e.g., parentPath=["credential_holder"])
     * and mDoc paths (e.g., parentPath=["namespace", "credential_holder"]).
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
     * Build a grouped structure: parent name -> list of child ClaimMetadata.
     * Groups leaf claims by their parent element — the second-to-last path segment.
     * This handles both SD-JWT paths (["parent", "leaf"]) and mDoc paths
     * (["namespace", "parent", "leaf"]) uniformly.
     * Parent-only entries (where the last segment IS the parent) are excluded.
     */
    fun groupByParent(): Map<String, List<ClaimMetadata>> {
        return claimsMetadata
            .filter { it.path.size >= 2 }
            .groupBy { it.path[it.path.size - 2] }
    }

    /**
     * Get unique top-level parent names (first path element) from all claims.
     * Used by SD-JWT claim selection dialog to identify parent disclosures.
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
