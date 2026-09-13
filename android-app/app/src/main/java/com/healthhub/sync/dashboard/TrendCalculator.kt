package com.healthhub.sync.dashboard

fun trendDelta(series: ChartSeries): Float? {
    val values = series.values
    if (values.size < 2) return null
    val mid = values.size / 2
    val firstHalf = values.subList(0, mid)
    val secondHalf = values.subList(mid, values.size)
    return secondHalf.average().toFloat() - firstHalf.average().toFloat()
}
