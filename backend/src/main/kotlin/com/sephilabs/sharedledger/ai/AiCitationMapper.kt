package com.sephilabs.sharedledger.ai

import tools.jackson.databind.JsonNode

data class AiCitationDto(
    val path: String,
    val value: JsonNode?,
    val grounded: Boolean,
    val context: String? = null,
)

/** Maps raw model citations into verified DTOs with an optional human context resolved from the payload parent node. */
object AiCitationMapper {

    fun fromNode(
        node: JsonNode,
        payload: JsonNode?,
        contextResolver: (JsonNode) -> String?,
    ): List<AiCitationDto> = fromArray(node.path("citations"), payload, contextResolver)

    fun fromArray(
        node: JsonNode?,
        payload: JsonNode?,
        contextResolver: (JsonNode) -> String?,
    ): List<AiCitationDto> =
        node?.takeIf { it.isArray }?.toList()?.map { cite ->
            val path = cite.path("path").asString("")
            val check = payload?.let { AiGroundingVerifier.check(it, cite) }
            val grounded = check?.grounded == true
            val actualValue = check?.takeIf { it.grounded }?.actualValue
            AiCitationDto(
                path = path,
                value = actualValue ?: cite.path("value").takeIf { !it.isMissingNode },
                grounded = grounded,
                context = if (payload != null && grounded) parentContext(payload, path, contextResolver) else null,
            )
        } ?: emptyList()

    private fun parentContext(
        payload: JsonNode,
        path: String,
        contextResolver: (JsonNode) -> String?,
    ): String? {
        val normalized = path.replace("[", ".").replace("]", "")
        val segments = normalized.split('.').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments.none { it.toIntOrNull() != null }) return null
        val parent = AiGroundingVerifier.resolve(payload, segments.dropLast(1).joinToString(".")) ?: return null
        if (!parent.isObject) return null
        return contextResolver(parent)
    }
}
