from datetime import date, timedelta

import pytest

from tdee import compute_tdee_series


def _dates(start: date, n: int):
    return [start + timedelta(days=i) for i in range(n)]


def test_steady_weight_means_tdee_equals_intake():
    start = date(2026, 1, 1)
    days = _dates(start, 14)
    weights = {d: 70.0 for d in days}
    intake = {d: 2000.0 for d in days}

    result = compute_tdee_series(weights, intake, [days[-1]], window_days=14, min_readings=7)

    assert result[days[-1]] == 2000.0


def test_steady_weight_loss_raises_tdee_above_intake():
    start = date(2026, 1, 1)
    days = _dates(start, 14)
    # Exact linear loss of 0.1 kg/day
    weights = {d: 70.0 - 0.1 * i for i, d in enumerate(days)}
    intake = {d: 2000.0 for d in days}

    result = compute_tdee_series(weights, intake, [days[-1]], window_days=14, min_readings=7)

    # TDEE = intake - (kg/day trend * 7700)  ->  2000 - (-0.1 * 7700) = 2770
    assert result[days[-1]] == pytest.approx(2770.0, abs=0.5)


def test_returns_none_when_fewer_than_min_readings():
    start = date(2026, 1, 1)
    days = _dates(start, 14)
    # Only 5 weight readings and 5 intake readings in the window (below min_readings=7)
    weights = {d: 70.0 for d in days[:5]}
    intake = {d: 2000.0 for d in days[:5]}

    result = compute_tdee_series(weights, intake, [days[-1]], window_days=14, min_readings=7)

    assert result[days[-1]] is None


def test_computes_independent_value_per_target_date():
    start = date(2026, 1, 1)
    days = _dates(start, 20)
    weights = {d: 70.0 for d in days}
    # Intake jumps from 1800 to 2200 halfway through
    intake = {d: (1800.0 if i < 10 else 2200.0) for i, d in enumerate(days)}

    targets = [days[13], days[19]]
    result = compute_tdee_series(weights, intake, targets, window_days=14, min_readings=7)

    # days[13]'s 14-day window (days[0..13]) mixes 10 low + 4 high readings
    assert result[days[13]] == pytest.approx((1800.0 * 10 + 2200.0 * 4) / 14, abs=0.01)
    # days[19]'s 14-day window (days[6..19]) mixes 4 low + 10 high readings
    assert result[days[19]] == pytest.approx((1800.0 * 4 + 2200.0 * 10) / 14, abs=0.01)
