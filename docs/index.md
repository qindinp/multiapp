# MultiApp Docs Index

Use this page as the current documentation entry point.

## Active Runtime Work

- `docs\current-repository-state.md`: current branch state, commit scope,
  active QQ Reader blocker, and verification command.
- `docs\qqreader-offline-patch.md`: QQ Reader offline clone patch flow and the
  current `OnlineChapterDownloadTask.run()` blocker.
- `docs\clone-runtime-general-fix.md`: general clone runtime repair plan:
  component remapping, intent rewriting, resource routing, and service package
  identity handling.
- `docs\protected-app-loading.md`: protected-app loading path, including
  `LoaderFactory`, `Runtime.nativeLoad`, native search paths, and provider
  fallback.

## Historical Analysis

- `docs\jiagu-bypass-analysis.md`: Jiagu/360 shell loading experiments and
  previous crash chains.
- `docs\ywloginmanager-solution.md`: YWLoginManager native binding analysis and
  candidate fixes.
- `docs\dump-rebuild-design.md`: dump/rebuild design notes.
- `docs\architecture-review.md`: broader architecture review.

## Repository Maintenance

- `docs\repository-cleanup-plan.md`: current cleanup rules, completed cleanup,
  and delete-before-asking list.

## Local Reference Material

The following directories are local analysis/reference inputs and are ignored by
Git. Do not delete them without explicit confirmation:

- `.tmp`
- `tmp_apks`
- `.mimocode`
- `.vscode`
