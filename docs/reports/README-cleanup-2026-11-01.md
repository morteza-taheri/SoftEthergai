# Repository cleanup — 2026-11-01

The repository root had accumulated 73 report files, build outputs, scratch
directories and local artifacts. This note records where everything went, so a
future reader never has to guess.

## Moved into this folder (`docs/reports/`)

73 `*.md` reports that used to sit in the repository root — the `AUTOCONNECT_*`,
`BLOCK_*`, `DEVICE_VERIFY_*`, `V1.0.7x_*`, `V1.0.7x`, `TRIAGE_*`, `OVERNIGHT_*`
and single-purpose `*_REPORT.md` files. They join the 94 reports that were
already archived here; the folder is now the single place for historical
verification evidence.

Four documents stayed at the root because they are canonical, not historical:

| File | Why it stays |
|---|---|
| `README.md` | public overview |
| `PROJECT_SPECIFICATION.md` | canonical requirements and status |
| `SERVER_SOURCE_ARCHITECTURE.md` | canonical source/path design |
| `AGENTS.md` | repository conventions |

Citations inside `PROJECT_SPECIFICATION.md` that name a report by bare filename
now resolve to this folder.

## Deleted (regenerable, no information lost)

| Path | What it was |
|---|---|
| `build/`, `app/build/`, `*/build/` | Gradle outputs (~1.0 GB) |
| `.gradle/`, `.venv/`, `.kotlin/`, `.pytest_cache/`, `__pycache__/` | local caches |
| `.dv6/`, `.dv7/` | ad-hoc device-analysis scratch dirs |
| `lint_v1064.log`, `build_test_baseline.log` | old logs |
| `SoftEthergai-v1.0.67-pro-debug.apk` | superseded local APK (61 MB) |

All of it is rebuilt by `./gradlew assembleProDebug` or `python -m venv`.

## Moved OUTSIDE the project (not deleted)

`test-evidence/` (363 MB, 538 device screenshots and database pulls) and
`releases/` (457 MB of built APKs) were moved to:

```
C:/Dev/_softethergai-archive/2026-11-01/
```

They are the only copies of that material, so they were preserved rather than
destroyed. The published APKs also live on the GitHub releases page; every APK
can be rebuilt from its commit with `git archive <commit> | tar -x`.

`.gitignore` now blocks `releases/`, `test-evidence/`, `*.apk`, `lint_*.log`,
`build_test_baseline.log` and `.dv*/` so none of it returns.
