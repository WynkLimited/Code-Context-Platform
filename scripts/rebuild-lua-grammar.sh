#!/usr/bin/env bash
# Maintainer-only rebuild of trusted upstream code; never compiles indexed repository content.
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
commit=816840c592ab973500ae9750763c707b447e7fef
output="$repository_root/src/main/resources/native/lua-v0.4.1"
mkdir -p "$output"
curl --fail --location --silent --show-error "https://codeload.github.com/tree-sitter-grammars/tree-sitter-lua/tar.gz/$commit" -o "$work/source.tar.gz"
python3 - "$work" <<'PY'
import hashlib,pathlib,sys,tarfile
work=pathlib.Path(sys.argv[1]);archive=work/'source.tar.gz'
expected='a73791322252c6917aee75bd371290c0cc748a04450ea57a161c8773bba0520a'
if hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()!=expected:raise SystemExit('Pinned Lua source checksum mismatch')
with tarfile.open(archive) as source:source.extractall(work,filter='data')
PY
source_root="$work/tree-sitter-lua-$commit"
cp "$source_root/LICENSE.md" "$output/LICENSE.txt"
if [[ "$(uname -s)" == Darwin ]]; then
    for architecture in arm64 x86_64; do
        cc -O2 -fPIC -dynamiclib -arch "$architecture" -mmacosx-version-min=11.0 \
            -Wl,-install_name,"@rpath/tree-sitter-lua-macos-$architecture.dylib" \
            -I"$source_root/src" "$source_root/src/parser.c" "$source_root/src/scanner.c" \
            -o "$output/tree-sitter-lua-macos-$architecture.dylib"
    done
fi
docker build -t ccp-lua-grammar-builder:0.4.1 -f "$repository_root/scripts/native-lua-builder.Dockerfile" "$work"
# Cross compilers produce both GNU architectures on either ARM or Intel Linux builders.
docker run --rm --network none --memory 1g --cpus 2 --pids-limit 256 \
    -v "$source_root:/src:ro" -v "$output:/out" ccp-lua-grammar-builder:0.4.1 sh -eu -c '
    aarch64-linux-gnu-gcc -O2 -fPIC -shared -Wl,-z,relro,-z,now -I/src/src /src/src/parser.c /src/src/scanner.c -o /out/tree-sitter-lua-linux-aarch64.so
    x86_64-linux-gnu-gcc -O2 -fPIC -shared -Wl,-z,relro,-z,now -I/src/src /src/src/parser.c /src/src/scanner.c -o /out/tree-sitter-lua-linux-x86_64.so'
python3 - "$output" <<'PY'
import hashlib,pathlib,sys
root=pathlib.Path(sys.argv[1]);names=['tree-sitter-lua-linux-aarch64.so','tree-sitter-lua-linux-x86_64.so','tree-sitter-lua-macos-arm64.dylib','tree-sitter-lua-macos-x86_64.dylib']
if any(not (root/name).is_file() for name in names):raise SystemExit('All four architectures are required; build missing Mac binaries on macOS')
(root/'SHA256SUMS').write_text(''.join(hashlib.file_digest((root/name).open('rb'),'sha256').hexdigest()+'  '+name+'\n' for name in names))
PY
