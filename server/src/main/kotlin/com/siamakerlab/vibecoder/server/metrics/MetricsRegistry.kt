package com.siamakerlab.vibecoder.server.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * v0.55.0 — Phase 34 Prometheus-compatible metrics registry.
 *
 * Zero-dep implementation that emits the
 * [Prometheus text exposition format](https://prometheus.io/docs/instrumenting/exposition_formats/)
 * — counters are `LongAdder` for cheap increments under concurrency, gauges are
 * pulled live on every scrape via callable suppliers.
 *
 * Counters reset on server restart (Prometheus handles the discontinuity by tracking
 * cumulative deltas across scrapes).
 */
class MetricsRegistry {

    private data class Counter(
        val name: String,
        val help: String,
        val labels: Map<String, String> = emptyMap(),
        val adder: LongAdder = LongAdder(),
    )

    private data class Gauge(
        val name: String,
        val help: String,
        val labels: Map<String, String> = emptyMap(),
        val supplier: () -> Number,
    )

    // labels 가 다르면 다른 카운터 — composite key.
    private val counters = ConcurrentHashMap<String, Counter>()
    private val gauges = mutableListOf<Gauge>()

    /** Register or fetch a counter. Idempotent — first call defines `help`. */
    fun counter(name: String, help: String, labels: Map<String, String> = emptyMap()): LongAdder {
        val key = counterKey(name, labels)
        val existing = counters[key]
        if (existing != null) return existing.adder
        val created = Counter(name, help, labels)
        return counters.putIfAbsent(key, created)?.adder ?: created.adder
    }

    /** Convenience: increment by 1. */
    fun inc(name: String, help: String, labels: Map<String, String> = emptyMap()) {
        counter(name, help, labels).increment()
    }

    /** Register a gauge sampled live on every scrape. */
    @Synchronized
    fun gauge(name: String, help: String, labels: Map<String, String> = emptyMap(), supplier: () -> Number) {
        gauges += Gauge(name, help, labels, supplier)
    }

    /** Render the full registry in Prometheus text format. */
    fun render(): String = buildString {
        // Counters — group by metric name so HELP/TYPE prints once.
        counters.values.groupBy { it.name }.toSortedMap().forEach { (name, group) ->
            val help = group.first().help
            append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n')
            append("# TYPE ").append(name).append(" counter\n")
            group.sortedBy { it.labels.toString() }.forEach { c ->
                append(name)
                if (c.labels.isNotEmpty()) {
                    append('{')
                    c.labels.entries.sortedBy { it.key }.joinTo(this, ",") { (k, v) ->
                        "$k=\"${escapeLabel(v)}\""
                    }
                    append('}')
                }
                append(' ').append(c.adder.sum()).append('\n')
            }
        }
        // Gauges — same grouping.
        gauges.groupBy { it.name }.toSortedMap().forEach { (name, group) ->
            val help = group.first().help
            append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n')
            append("# TYPE ").append(name).append(" gauge\n")
            group.sortedBy { it.labels.toString() }.forEach { g ->
                append(name)
                if (g.labels.isNotEmpty()) {
                    append('{')
                    g.labels.entries.sortedBy { it.key }.joinTo(this, ",") { (k, v) ->
                        "$k=\"${escapeLabel(v)}\""
                    }
                    append('}')
                }
                val v = runCatching { g.supplier().toDouble() }.getOrDefault(Double.NaN)
                append(' ').append(formatNumber(v)).append('\n')
            }
        }
    }

    private fun counterKey(name: String, labels: Map<String, String>): String =
        if (labels.isEmpty()) name
        else name + "{" + labels.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> "$k=$v" } + "}"

    private fun escapeLabel(v: String): String =
        v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun escapeHelp(h: String): String =
        h.replace("\\", "\\\\").replace("\n", "\\n")

    private fun formatNumber(v: Double): String = when {
        v.isNaN() -> "NaN"
        v.isInfinite() -> if (v > 0) "+Inf" else "-Inf"
        v == v.toLong().toDouble() -> v.toLong().toString()
        else -> v.toString()
    }
}
