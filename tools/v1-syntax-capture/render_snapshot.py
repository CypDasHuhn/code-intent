import json
import sys

snapshot_path, generated_path = sys.argv[1], sys.argv[2]
with open(snapshot_path, encoding="utf-8") as handle:
    snapshot = json.load(handle)

lines = []
lines.append("package dev.cint.v2.syntax.treesitter")
lines.append("")
lines.append("internal object V1KotlinSnapshot {")
lines.append(f'    const val V1_COMMIT: String = {json.dumps(snapshot["v1Commit"])}')
lines.append("")
lines.append("    val fingerprints: Map<String, String> =")
lines.append("        mapOf(")
for name, entry in snapshot["files"].items():
    lines.append(f'            {json.dumps(name)} to {json.dumps(entry["fingerprint"])},')
lines.append("        )")
lines.append("")
lines.append("    val sources: Map<String, String> =")
lines.append("        mapOf(")
for name, entry in snapshot["files"].items():
    lines.append(f'            {json.dumps(name)} to')
    lines.append(f'                {json.dumps(entry["source"])},')
lines.append("        )")
lines.append("}")
lines.append("")
with open(generated_path, "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines))
