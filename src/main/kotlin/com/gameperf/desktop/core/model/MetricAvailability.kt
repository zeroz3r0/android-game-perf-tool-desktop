package com.gameperf.desktop.core.model

/**
 * Availability status for a metric on a given platform.
 * Used by [com.gameperf.desktop.report.ReportGenerator] to render N/A cells
 * for metrics unavailable on the capture platform.
 */
enum class MetricAvailability {
    /** Metric is fully available and measured. */
    AVAILABLE,

    /** Metric is available but with reduced accuracy (e.g. GPU% on iOS = estimated). */
    PARTIAL,

    /** Metric is not available on this platform (renders as "N/A" / "No disponible"). */
    NOT_AVAILABLE,
}
