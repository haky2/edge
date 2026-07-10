package com.haky.edge.model

import kotlinx.serialization.Serializable

@Serializable
data class DeepResearch(
    val code: String,
    val name: String,
    val date: String,
    val summary: String? = null,
    val comment: String,
    val sources: List<ResearchSource> = emptyList(),
    val generatedAt: String,
)

@Serializable
data class ResearchSource(val title: String, val url: String)
