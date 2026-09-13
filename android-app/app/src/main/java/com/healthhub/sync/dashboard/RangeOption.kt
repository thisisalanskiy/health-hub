package com.healthhub.sync.dashboard

enum class RangeOption(val label: String, val days: Int) {
    WEEK("1W", 7),
    MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    YEAR("1Y", 365),
    ALL("All", 1825),
}
