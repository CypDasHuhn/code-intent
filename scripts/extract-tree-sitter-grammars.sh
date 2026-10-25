#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="$root/sources/syntax-treesitter/native/tree-sitter-grammars/lib"
mkdir -p "$target"

# name:version:jar-sha256:library-sha256
pins=(
  "tree-sitter-c:0.24.1:1feba1219f860f8505e118622b7741080e27bd225131e1c1beeb970346c49359:15064c81a04872c4142993b0a1232448e4bb810aa8cef71f016d0b6931cc5e2a"
  "tree-sitter-kotlin:0.3.8.1:ffd114e0f27bf34a7c51f9617796156b19bb1a1e02129b51e3be55277d98b347:749c42b7dffc26eb48c47939037f43309b908f07244b7a7b38b7b4dc7d155f30"
  "tree-sitter-javascript:0.23.1:a4fa85ef10247e9d931df743a760aa0fcd9021dcac7840b13bda8ccfd86af7bc:c9018a3fa7356eed18d74f28e82075d71f3cbdafd7fbf82f99d5a5b41627a1d9"
  "tree-sitter-typescript:0.23.2:5c3d1716441fbda55293b8632f9e691733bd3035a582da3936599d2309183bf0:7e8a7423ee2387f9145106281343df8cd1c430eb460311313d2dd53b9af7e45a"
  "tree-sitter-vue:0.2.1a:a38bb43178f99b1e72df326a1e4c5ba1d3db74d2cd59e6ab38d2f07a6668a180:5f4b41ec1003d42531c86d8aa9b0dff74754f7a534839bad3a88026b1b51470c"
  "tree-sitter-c-sharp:0.23.1:a83e264a9bfe86aa10611ce4d903b17a5558e3d9a24812bfafb81773bc81f130:6392870371e3557611c3c10719cd95a266a588b055753a7fc46f51e3d0e27c07"
)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for pin in "${pins[@]}"; do
  IFS=: read -r name version jar_sha lib_sha <<<"$pin"
  jar="$work/$name-$version.jar"
  url="https://repo1.maven.org/maven2/io/github/bonede/$name/$version/$name-$version.jar"
  curl -fsSL "$url" -o "$jar"
  echo "$jar_sha  $jar" | sha256sum -c -
  so="$target/lib$name.so"
  unzip -p "$jar" "lib/x86_64-linux-gnu-$name.so" > "$so"
  echo "$lib_sha  $so" | sha256sum -c -
  chmod 755 "$so"
  echo "verified $name $version -> lib$name.so"
done
