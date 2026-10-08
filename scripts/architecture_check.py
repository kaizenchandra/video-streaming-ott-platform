#!/usr/bin/env python3
"""Check inventories, references, typed topic ownership and synchronous acyclicity.

This supplements the recorded design review; it does not prove runtime correctness.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "requirements": ["functional-requirements", "non-functional-requirements", "assumptions", "constraints", "requirements-traceability-matrix"],
    "architecture": ["architecture-overview", "domain-model", "service-catalog", "data-architecture", "event-architecture", "api-standards", "transactions", "playback", "media-platform", "live-streaming", "reliability", "observability", "deployment", "multi-region", "implementation-roadmap"],
    "security": ["security-architecture", "threat-model", "authentication", "authorization", "secrets-management"],
    "capacity": ["capacity-plan"],
    "testing": ["test-strategy"],
    "diagrams": ["c4", "sequences"],
}
# Owner-to-owner synchronous edges in the accepted design. Kafka does not count.
DEPENDENCIES = {
    "member": [], "content": [], "commerce": ["member"], "media": [],
    "playback": ["member", "content", "commerce"],
    "experience": ["member", "content", "playback"], "engagement": ["member", "content", "commerce", "playback"], "insights": ["member", "playback"],
    "operations": ["member", "commerce"],
}


def validate(root=ROOT):
    errors = []
    for directory, names in REQUIRED.items():
        for name in names:
            path = root / "docs" / directory / (name + ".md")
            if not path.is_file() or path.stat().st_size < 150:
                errors.append(f"Missing/substantively empty artifact: {path.relative_to(root)}")
    def inventory(path, pattern):
        return set(re.findall(pattern, (root / path).read_text()))
    fr = inventory("docs/requirements/functional-requirements.md", r"FR-\d{3}")
    traced = inventory("docs/requirements/requirements-traceability-matrix.md", r"FR-\d{3}")
    if fr != traced or fr != {f"FR-{i:03}" for i in range(1, 38)}:
        errors.append(f"FR traceability mismatch: untraced={sorted(fr-traced)}, unexpected={sorted(traced-fr)}")
    nfr = inventory("docs/requirements/non-functional-requirements.md", r"NFR-\d{3}")
    if nfr != {f"NFR-{i:03}" for i in range(1, 16)}:
        errors.append("NFR inventory mismatch")
    for i in range(1, 16):
        path = root / f"docs/adr/ADR-{i:03}.md"
        if not path.exists():
            errors.append(f"Missing ADR {i}")
        elif any(f"## {h}" not in path.read_text() for h in ["Context", "Decision", "Alternatives", "Rationale", "Consequences", "Operational Impact"]):
            errors.append(f"Incomplete ADR {i}")
    for path in (root / "docs").rglob("*.md"):
        for target in re.findall(r"\]\(([^)]+)\)", path.read_text()):
            if "://" in target or target.startswith("#"):
                continue
            linked = (path.parent / target.split("#")[0]).resolve()
            if not linked.exists():
                errors.append(f"Broken link {path.relative_to(root)} -> {target}")
    topics = json.loads((root / "contracts/events/topics.json").read_text())
    if len({t["topic"] for t in topics}) != len(topics):
        errors.append("Duplicate topic owners")
    for topic in topics:
        if topic["owner"] not in DEPENDENCIES:
            errors.append(f"Unknown owner: {topic}")
        schema = json.loads((root / "contracts/events" / topic["schema"]).read_text())
        fields = {f["name"] for f in schema["fields"]}
        if "payloadJson" in fields or not {"eventId", "aggregateVersion", "occurredAt"}.issubset(fields):
            errors.append(f"Untyped/incomplete event: {topic['topic']}")
    visiting, done = set(), set()
    def visit(node):
        if node in visiting:
            errors.append(f"Synchronous dependency cycle at {node}")
            return
        if node in done:
            return
        visiting.add(node)
        for child in DEPENDENCIES[node]:
            visit(child)
        visiting.remove(node)
        done.add(node)
    for service in DEPENDENCIES:
        visit(service)
    sequences = (root / "docs/diagrams/sequences.md").read_text().count("sequenceDiagram")
    if sequences != 10:
        errors.append(f"Expected 10 critical sequences; found {sequences}")
    return {"status": "FAIL" if errors else "PASS", "functionalRequirements": len(fr), "nonFunctionalRequirements": len(nfr), "services": len(DEPENDENCIES), "topics": len(topics), "sequences": sequences, "errors": errors}


if __name__ == "__main__":
    result = validate()
    print(json.dumps(result, indent=2))
    raise SystemExit(bool(result["errors"]))
