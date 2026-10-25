#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root/sources/syntax-treesitter/native"
sha256sum -c SHA256SUMS
