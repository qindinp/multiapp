# MultiApp Docs Index

Use this page as the current documentation entry point.

## Active Runtime Work

- `docs\multiapp-container-lsplant-roadmap.md`: current top-level plan for the
  self-developed MultiApp container + LSPlant route, including architecture,
  Android 16 constraints, team ownership, phases, and acceptance gates.
- `docs\container-runtime-refactor-execution-log.md`: execution log for the
  `container-runtime-refactor` branch, including completed foundation slices
  and verification commands.
- `docs\container-runtime-refactor\v2-in-repo-kernel-rewrite-plan.md`: concrete
  execution plan for the MultiApp v2 in-repo kernel rewrite, including phases,
  module boundaries, verification matrix, and first implementation tasks.
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

## Architecture

- `ARCHITECTURE.md`: 项目架构图、模块职责说明、核心流程说明。
- `docs\architecture-review.md`: 架构审查报告、依赖分析、改进建议。

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
