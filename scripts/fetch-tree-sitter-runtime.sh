#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="$root/sources/syntax-treesitter/native/tree-sitter"
version="0.27.1"
tarball_sha="982cd3d4d9eb7be18c243240a622423fd2e4ecb4bec2cd98832c2f3fa1f0f333"
url="https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v$version.tar.gz"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl -fsSL "$url" -o "$work/tree-sitter.tar.gz"
echo "$tarball_sha  $work/tree-sitter.tar.gz" | sha256sum -c -
tar xzf "$work/tree-sitter.tar.gz" -C "$work"
src="$work/tree-sitter-$version"

make -C "$src" -j"$(nproc)" static shared

mkdir -p "$target/lib" "$target/include/tree_sitter"
cp "$src/libtree-sitter.a" "$target/lib/libtree-sitter.a"
cp "$src/libtree-sitter.so" "$target/lib/libtree-sitter.so"
cp "$src/lib/include/tree_sitter/api.h" "$target/include/tree_sitter/api.h"
ln -sfn libtree-sitter.so "$target/lib/libtree-sitter.so.0.27"
rm -f "$target/lib/libtree-sitter.so.0.28"

echo "installed tree-sitter $version into $target"
