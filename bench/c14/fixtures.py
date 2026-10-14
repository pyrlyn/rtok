"""Deterministic, seeded fixtures shared by every tool."""
import json
import random
from pathlib import Path

SEED = 1414


def write_all(out: Path) -> dict[str, Path]:
    rng = random.Random(SEED)
    out.mkdir(parents=True, exist_ok=True)
    data = {}
    data["uniform_table"] = [
        {"id": i, "name": f"user{i}", "email": f"user{i}@example.com",
         "role": rng.choice(["admin", "dev", "viewer"]), "active": rng.random() > 0.3,
         "score": round(rng.random() * 100, 2)}
        for i in range(1, 301)
    ]
    data["nested_api"] = {
        "status": "ok", "page": 1, "total": 120,
        "items": [{"id": i, "repo": {"owner": "pyrlyn", "name": "rtok", "private": False},
                   "labels": [rng.choice(["bug", "docs", "perf"]) for _ in range(rng.randint(1, 3))],
                   "author": {"login": f"dev{rng.randint(1, 8)}", "type": "User"},
                   "title": f"Issue number {i}", "state": rng.choice(["open", "closed"])}
                  for i in range(1, 121)],
    }
    data["mixed_array"] = [rng.choice([
        {"kind": "event", "t": rng.randint(0, 10**6)}, rng.randint(0, 999), f"s{rng.randint(0, 99)}",
        {"kind": "log", "msg": "retry", "n": rng.randint(1, 5), "tags": ["a", "b"]}]) for _ in range(200)]
    data["deep_config"] = {"server": {"http": {"port": 8080, "tls": {"cert": "/etc/c.pem", "key": "/etc/k.pem"}},
                                      "limits": {f"route{i}": {"rps": 100 + i, "burst": 20} for i in range(40)}}}
    paths = {}
    for name, value in data.items():
        p = out / f"{name}.json"
        p.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        paths[name] = p
    return paths
