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
