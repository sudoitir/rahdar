#!/usr/bin/env python3
"""Assign unit-test classes to one of three shards by measured duration.

Usage: pick-unit-shard.py <shard 0|1|2> <tests-file> <weights-file>

The tests file is one path per line. The weights file is `classname seconds`,
with # comments. A class with no weight uses the median of the known weights.
Prints this shard's paths on stdout, and the class count and seconds on stderr.
"""

import statistics
import sys
from pathlib import Path

SHARDS = 3


def classname(path: str) -> str:
    prefix = "src/test/java/"
    name = path[len(prefix) :] if path.startswith(prefix) else path
    if name.endswith(".java"):
        name = name[:-5]
    return name.replace("/", ".")


def main() -> None:
    if len(sys.argv) != 4:
        print(
            "usage: pick-unit-shard.py <shard 0|1|2> <tests-file> <weights-file>",
            file=sys.stderr,
        )
        sys.exit(2)
    shard = int(sys.argv[1])
    if shard not in range(SHARDS):
        print(f"shard must be 0, 1 or 2, got {shard}", file=sys.stderr)
        sys.exit(2)

    paths = [ln.strip() for ln in Path(sys.argv[2]).read_text().splitlines() if ln.strip()]
    weights: dict[str, float] = {}
    for ln in Path(sys.argv[3]).read_text().splitlines():
        ln = ln.strip()
        if not ln or ln.startswith("#"):
            continue
        cls, sec = ln.rsplit(" ", 1)
        weights[cls] = float(sec)
    default = statistics.median(weights.values()) if weights else 1.0

    def weight(path: str) -> float:
        return weights.get(classname(path), default)

    loads = [0.0] * SHARDS
    mine: list[str] = []
    for path in sorted(paths, key=lambda p: (-weight(p), p)):
        chosen = min(range(SHARDS), key=lambda s: (loads[s], s))
        loads[chosen] += weight(path)
        if chosen == shard:
            mine.append(path)
    for path in sorted(mine):
        print(path)
    rounded = [round(load) for load in loads]
    print(
        f"shard {shard}: {len(mine)} classes, {loads[shard]:.0f}s (all shards {rounded}s)",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
