package io.github.grachevskaya.asya.common

import io.github.grachevskaya.asya.common.Utils.encoder
import kotlinx.serialization.Serializable

@Serializable
data class FilterHolder(
    val filterCounts: MutableMap<String, FilterCount> = mutableMapOf(),
) {
    @Serializable
    data class FilterCount(
        var packageManagerCount: Int = 0,
        var activityLaunchCount: Int = 0,
    ) {
        val totalCount: Int get() = packageManagerCount + activityLaunchCount
    }

    val totalCount: Int get() = filterCounts.values.sumOf { it.totalCount }

    companion object {
        fun parse(json: String) = encoder.decodeFromString<FilterHolder>(json)
    }

    enum class FilterType {
        PACKAGE_MANAGER,
        ACTIVITY_LAUNCH,
    }

    override fun toString() = encoder.encodeToString(this)
}
