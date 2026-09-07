#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "mcp[cli]",
#     "requests",
# ]
# ///
"""Local MCP server exposing health-hub's read-only API to Claude Desktop.

Runs on the same machine as the health-hub Docker stack and talks to it over
localhost — nothing here is reachable from outside this Mac.
"""

import requests
from mcp.server.mcpserver import MCPServer

BASE_URL = "http://localhost:8000"

mcp = MCPServer("health-hub")


def _get(path: str, **params) -> dict:
    resp = requests.get(f"{BASE_URL}{path}", params=params, timeout=10)
    resp.raise_for_status()
    return resp.json()


@mcp.tool()
def get_snapshot() -> dict:
    """Latest weight, today's calories/TDEE/steps, and last sleep duration."""
    return _get("/api/snapshot")


@mcp.tool()
def get_weight(days: int = 30) -> dict:
    """Weight readings and MacroFactor trend line over the last N days."""
    return _get("/api/weight", days=days)


@mcp.tool()
def get_recovery(days: int = 30) -> dict:
    """Daily resting heart rate and HRV over the last N days."""
    return _get("/api/recovery", days=days)


@mcp.tool()
def get_heartrate(days: int = 30) -> dict:
    """Daily min/avg/max heart rate over the last N days."""
    return _get("/api/heartrate", days=days)


@mcp.tool()
def get_steps(days: int = 30) -> dict:
    """Daily step totals over the last N days."""
    return _get("/api/steps", days=days)


@mcp.tool()
def get_sleep(days: int = 30) -> dict:
    """Nightly sleep stage minutes (deep/rem/core/awake/other) over the last N days."""
    return _get("/api/sleep", days=days)


@mcp.tool()
def get_workouts(days: int = 30) -> dict:
    """Weekly workout totals and the 10 most recent workouts."""
    return _get("/api/workouts", days=days)


@mcp.tool()
def get_nutrition(days: int = 30) -> dict:
    """Daily calories, TDEE, and macros over the last N days."""
    return _get("/api/nutrition", days=days)


@mcp.tool()
def get_tdee(days: int = 30) -> dict:
    """Self-calculated TDEE (from weight trend + intake) vs. MacroFactor's
    reported TDEE, per day, over the last N days."""
    return _get("/api/tdee", days=days)


if __name__ == "__main__":
    mcp.run()
