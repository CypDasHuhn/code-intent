#!/usr/bin/env python3
import importlib.util
import json
import pathlib
import sys

sys.dont_write_bytecode = True

root = pathlib.Path(__file__).resolve().parent.parent
cli_path = root / "tests/integration/build/parity/cli-report.json"
ipc_path = root / "apps/server/build/parity/ipc-report.json"
out_path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "build/reports/parity/v2-parity-report.json"

if not cli_path.is_file() or not ipc_path.is_file():
    raise SystemExit(f"missing parity reports: {cli_path} {ipc_path}")

cli = json.loads(cli_path.read_text(encoding="utf-8"))
ipc = json.loads(ipc_path.read_text(encoding="utf-8"))

intentional = {}
for surface, report in (("cli", cli), ("ipc", ipc)):
    for entry in report.get("intentionalDifferences", []):
        item = dict(entry)
        item.setdefault("source", surface)
        key = (item["source"], item["id"])
        intentional[key] = item

v2_renderer = ipc.get("renderer", [])
renderer_derivation = {
    "source": "V2",
    "verified": bool(v2_renderer) and all(item.get("verified") is True for item in v2_renderer),
    "passed": bool(v2_renderer) and all(item.get("passed") is True for item in v2_renderer),
    "differenceCount": sum(int(item.get("differenceCount", 0)) for item in v2_renderer),
    "unmatchedCount": sum(int(item.get("unmatchedCount", 0)) for item in v2_renderer),
    "sectionsCompared": sum(len(item.get("sections", [])) for item in v2_renderer),
    "baseline": "frozen-v1-corpus/renderer-visible-v1.json",
}

defects = []
for surface, report in (("cli", cli), ("ipc", ipc)):
    for entry in report.get("defects", []):
        if not entry.get("ticket") or not entry.get("reason"):
            raise SystemExit(f"{surface} defect is missing a ticket or reason: {entry}")
        defects.append({"surface": surface, **entry})
renderer_checks_passed = all(
    bool(check.get("checks")) and all(value is True for value in check.get("checks", {}).values())
    for check in v2_renderer
)
renderer_unexercised = [
    difference_id
    for check in v2_renderer
    for difference_id in check.get("unexercisedDifferenceIds", [])
]
renderer_gate_failures = []
if not renderer_derivation.get("verified"):
    renderer_gate_failures.append("renderer projection was not derived or checked")
if not renderer_derivation.get("passed"):
    renderer_gate_failures.append("one or more renderer sections have unmatched differences")
if not renderer_checks_passed:
    renderer_gate_failures.append("one or more renderer checks drifted")
if renderer_unexercised:
    renderer_gate_failures.append("registered renderer differences were not exercised: " + ", ".join(renderer_unexercised))
if renderer_gate_failures:
    defects.append(
        {
            "surface": "renderer",
            "id": "renderer.derivation",
            "ticket": "094-v2-ipc-payload-parity",
            "reason": "; ".join(renderer_gate_failures),
            "context": {"unmatchedCount": renderer_derivation.get("unmatchedCount", 0)},
        }
    )

cli_summary = cli.get("summary", {})
ipc_summary = ipc.get("summary", {})
renderer = ipc.get("renderer", [])
report = {
    "formatVersion": 1,
    "ticket": "084-v2-parity-integration",
    "corpus": cli.get("corpus", {}),
    "summary": {
        "cliFilesCompared": cli_summary.get("filesCompared", 0),
        "cliFilesMatched": cli_summary.get("filesMatched", 0),
        "annotationsCompared": cli_summary.get("annotationsCompared", 0),
        "annotationsMatched": cli_summary.get("annotationsMatched", 0),
        "candidatesCompared": cli_summary.get("candidatesCompared", 0),
        "candidatesMatched": cli_summary.get("candidatesMatched", 0),
        "ipcScenarios": ipc_summary.get("scenarios", 0),
        "ipcDefects": ipc_summary.get("defectCount", 0),
        "rendererChecksPassed": renderer_checks_passed,
        "rendererDerivationVerified": renderer_derivation.get("verified") is True,
        "rendererPassed": renderer_derivation.get("passed") is True,
        "rendererDifferenceCount": renderer_derivation.get("differenceCount", 0),
        "rendererUnmatchedCount": renderer_derivation.get("unmatchedCount", 0),
        "rendererSectionsCompared": renderer_derivation.get("sectionsCompared", 0),
        "intentionalDifferences": len(intentional),
        "defects": len(defects),
    },
    "closable": len(defects) == 0,
    "followUpTickets": sorted({item["ticket"] for item in defects}),
    "intentionalDifferences": sorted(intentional.values(), key=lambda item: (item.get("source", ""), item["id"])),
    "deferrals": cli.get("deferrals", []),
    "syntax": cli.get("syntax", []),
    "cli": {"files": cli.get("files", []), "scenarios": []},
    "ipc": {
        "scenarios": ipc.get("scenarios", []),
        "renderer": renderer,
        "rendererDerivation": renderer_derivation,
    },
    "defects": sorted(defects, key=lambda item: (item["surface"], item["id"], json.dumps(item.get("context", {}), sort_keys=True))),
}

out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Wrote {out_path}")
print(
    "parity: "
    f"{report['summary']['annotationsMatched']}/{report['summary']['annotationsCompared']} annotations, "
    f"{report['summary']['candidatesMatched']}/{report['summary']['candidatesCompared']} candidates, "
    f"{report['summary']['intentionalDifferences']} intentional differences, "
    f"{report['summary']['defects']} defects"
)
