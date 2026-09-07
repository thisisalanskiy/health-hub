from datetime import date, timedelta

KCAL_PER_KG = 7700


def _linreg_slope(points: list[tuple[date, float]]) -> float:
    """Least-squares slope of value-per-day, x measured in days from the first point."""
    x0 = points[0][0]
    xs = [(d - x0).days for d, _ in points]
    ys = [v for _, v in points]
    n = len(points)
    mean_x = sum(xs) / n
    mean_y = sum(ys) / n
    numerator = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    denominator = sum((x - mean_x) ** 2 for x in xs)
    if denominator == 0:
        return 0.0
    return numerator / denominator


def compute_tdee_series(
    daily_weights: dict[date, float],
    daily_intake: dict[date, float],
    target_dates: list[date],
    window_days: int = 14,
    min_readings: int = 7,
) -> dict[date, float | None]:
    result: dict[date, float | None] = {}
    for target in target_dates:
        window_start = target - timedelta(days=window_days - 1)
        window = [window_start + timedelta(days=i) for i in range(window_days)]

        weight_points = [(d, daily_weights[d]) for d in window if d in daily_weights]
        intake_points = [daily_intake[d] for d in window if d in daily_intake]

        if len(weight_points) < min_readings or len(intake_points) < min_readings:
            result[target] = None
            continue

        avg_intake = sum(intake_points) / len(intake_points)
        kg_per_day = _linreg_slope(weight_points)
        result[target] = avg_intake - (kg_per_day * KCAL_PER_KG)

    return result
