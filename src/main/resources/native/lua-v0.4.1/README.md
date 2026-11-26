# Lua grammar native resources

Upstream: https://github.com/tree-sitter-grammars/tree-sitter-lua
Release: v0.4.1; exact commit: 816840c592ab973500ae9750763c707b447e7fef.
Source archive SHA-256: a73791322252c6917aee75bd371290c0cc748a04450ea57a161c8773bba0520a.
License: MIT, reproduced in LICENSE.txt.

These pure grammar libraries are loaded through the standard Tree-sitter language API, not custom JNI. The published io.github.bonede Lua2.1.3a GNU binaries reject the valid fixture `function refund(amount) return amount end` under three released parser cores; upstream v0.4.1 correctly parses it in the same GNU environment. Mac and GNU platform cases remain mandatory, with no fallback accepted by grammar tests.

Rebuild from the repository root with `scripts/rebuild-lua-grammar.sh` using Python3.12+, curl, Docker and (for the Mac binaries) the Apple C toolchain. Only the checksum-verified trusted upstream C sources are compiled, never indexed repository code. The compiler image starts from a pinned Noble Java21 image and installs native/cross GCC13 toolchains. macOS libraries target macOS11+; GNU libraries target GNU Linux. The build is reproducible by procedure; compiler/package updates can change binary hashes and must be reviewed and revalidated. The SHA256SUMS manifest is verified before loading. GNU ARM and Intel Mac artifacts can be verified locally; GNU x86 is also required in CI. No compiler, build scripts or downloads run in the application.

The application embeds all four platform resources, so a normal clean Maven build remains independent of native compilers and the reference product. New unsupported server operating systems must not be advertised as verified structural coverage.
