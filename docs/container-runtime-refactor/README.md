# Container Runtime Refactor Workbench

This directory is the planning and evidence workbench for the
`container-runtime-refactor` branch.

## Role

- Keep refactor plans, migration checklists, evidence notes, and draft patches
  together.
- Preserve one buildable source of truth in the existing Gradle module paths.
- Avoid copying full production source trees here unless a snapshot is needed
  for review or comparison.

## Source Rule

Runtime code that must compile stays in its canonical module, for example:

- `core/model`
- `core/loader`
- `core/hook`
- `app`

This workbench can hold design drafts or patch notes, but code here is not
treated as shipped runtime code unless it is moved into the canonical module
and verified by Gradle.

## Current Direction

1. Build the app-level virtualization/container baseline first.
2. Validate protected apps in hook-free mode first, with QQ Reader as the first
   hard target.
3. Keep LSPlant/Xposed/native business stubs/no-op patches as optional
   diagnostic or compatibility layers, not baseline dependencies.
4. Record evidence at every runtime boundary before changing behavior.

## Current Planning Entry Points

- [v2 Container 成熟化执行蓝图](v2-container-maturity-execution-blueprint-2026-06-29.md) — execution roadmap for using open-source and commercial multi-app references without losing context across sessions; uses Blueprint Phase 0-10 slices and maps them to the authoritative Roadmap Phase A-G labels.
- [v2 Reference Architecture Mapping](v2-reference-architecture-mapping-2026-06-29.md) — clean-room mapping from VirtualApp / BlackBox / DroidPlugin-style concepts to current MultiApp modules and evidence gates.
- [v2 Current-State Refresh](v2-current-state-refresh-2026-06-29.md) — owner-gated current status, verification commands, missing device evidence, and anti-false-DONE checklist.
- [v2 PR-2 Legacy Freeze + Comment Cleanup](v2-pr2-legacy-freeze-comment-cleanup-2026-06-29.md) — owner scope note for comment-only legacy freeze work; records that current mixed runtime diffs must be split or reclassified before PR-2 can be approved as clean comment cleanup.
- [v2 PR-3 Install / Instance JVM Evidence](v2-pr3-install-instance-jvm-evidence-2026-06-29.md) — deterministic JVM evidence that InstallRecord is the fact source, InstanceRecord only references originPackageName, and HostedRuntimeBootstrap consumes InstallRecord.originApkPath; device evidence remains pending.
- [v2 PR-4 RuntimeBootstrap Stage Pipeline Plan](v2-pr4-runtimebootstrap-stage-pipeline-plan-2026-06-29.md) — implementation plan for extracting HostedRuntimeBootstrap into stage contracts and stage-level JVM tests without absorbing PR-5/PR-6 device work.
- [v2 Seven-Kernel-Gap Execution](v2-seven-kernel-gap-execution-2026-06-29.md) — pre-existing current evidence gate source; all seven gates remain PARTIAL until direct device/runtime evidence proves otherwise.
- [v2 Hosted Container Audit Remediation](v2-hosted-container-audit-remediation-2026-06-27.md) — historical audit and remediation notes; use with the newer seven-gate document for current status.
