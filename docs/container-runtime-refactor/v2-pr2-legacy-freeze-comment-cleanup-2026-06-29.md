# PR-2 Legacy Stub Freeze + Comment Cleanup

Date: 2026-06-29
Owner: hosted container runtime
Review model: Xiaomi-style role split + owner gate
Status: comment/doc cleanup with runtime-diff separation

## 0. Purpose

PR-2 freezes the legacy Stub clone APK / LoaderFactory / QQ Reader special-hook route as transitional diagnostic or comparison infrastructure only.

This PR must not change runtime behavior. It may update:

```text
- docs
- KDoc
- source comments
- manifest XML comments
```

It must not update:

```text
- Kotlin/Java executable logic
- hook defaults or profile behavior
- manifest attributes or component semantics
- Gradle/config/resource behavior
- seven-gate status from PARTIAL to DONE
```

## 1. Owner scope decision

The current working tree contains pre-existing or out-of-scope runtime diffs in files also touched by PR-2 comment cleanup. The owner decision is:

```text
Keep PR-2 comment/doc cleanup conceptually separate.
Do not treat unrelated runtime diffs as part of PR-2.
Do not approve PR-2 as a clean comment-only PR until the runtime diffs are split or explicitly reclassified into another runtime PR.
```

## 2. Comment/doc cleanup targets

PR-2 comment-only targets:

| File | Allowed PR-2 change | Purpose |
| --- | --- | --- |
| `core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt` | Top-level KDoc only | Mark LoaderFactory as legacy transitional loader, not v2 kernel. |
| `core/loader/src/main/java/com/multiapp/core/loader/QqReaderProfile.kt` | KDoc/function comments only | Mark QQ Reader hooks as explicit legacy/diagnostic comparison only. |
| `core/stub/src/main/java/com/multiapp/core/stub/StubBuilder.kt` | KDoc/comments only | Mark StubBuilder as legacy transitional builder; remove unsafe 鈥渋ntegrity deception鈥?wording. |
| `app/src/main/AndroidManifest.xml` | XML comments only | Mark Service/Provider dispatch as partial/evidence-gated instead of 鈥渘ot dispatched yet.鈥?|
| `docs/container-runtime-refactor/*` | Docs only | Record PR-2 scope, anti-overclaim, and verification rules. |

## 3. Out-of-scope runtime diffs currently visible

The following diffs are **not PR-2 comment cleanup** and require a separate runtime PR or explicit reclassification:

```text
1. app/src/main/AndroidManifest.xml
   - android:theme="@style/Theme.MultiApp.Proxy" added to ContainerActivity and proxy Activity slots.
   - This is a manifest semantic/runtime behavior change.

2. core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
   - QQ Reader diagnostics routing changed from lsplantOk-only behavior to diagnosticsGate/property-gated behavior.
   - This changes hook/profile behavior.

3. core/loader/src/main/java/com/multiapp/core/loader/QqReaderProfile.kt
   - DiagnosticsGate API and default gating behavior exist in the working diff.
   - This changes public/runtime profile behavior if included in PR-2.
```

Owner rule:

```text
These runtime diffs must not be reviewed as PR-2 comment cleanup.
If included, the PR must be renamed/re-scoped and verified as a runtime behavior PR.
```

## 4. PR-2 wording rules

Allowed wording:

```text
legacy stub path labeled transitional
legacy Stub route frozen as non-v2 default
stale comments refreshed
manual diagnostic / legacy comparison only
no runtime behavior change
PARTIAL remains
device evidence pending
seven-gate status unchanged
```

Disallowed wording without direct runtime evidence:

```text
container complete
v2 container complete
QQ Reader fixed
QQ Reader works
protected apps compatible
default v2 launch proven not to generate stub APK
default v2 launch proven not to enable QQ Reader hook
Virtual PMS complete
Virtual AMS complete
LoadedApk sandbox complete
native redirect complete
provider dispatcher complete
```

## 5. Verification rules

### 5.1 If PR-2 is docs-only

```powershell
git diff --check -- docs/container-runtime-refactor
```

Gradle can be skipped with reason:

```text
documentation-only change
```

### 5.2 If PR-2 touches Kotlin/Java comments

Run after code review:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

### 5.3 If PR-2 touches manifest comments

Run after code review:

```powershell
.\gradlew.bat --no-parallel :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

### 5.4 If runtime behavior is included

Stop treating this as comment cleanup. Required baseline becomes:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

If any seven-gate or launch-behavior claim is made, device/runtime evidence is also required.

## 6. Owner current verdict

```text
PR-2 direction: APPROVED.
Current mixed working diff: NOT APPROVED as clean comment-only PR.
Required next step: split runtime diffs or reclassify them into a separate runtime PR.
```

This keeps the Xiaomi-style owner gate intact: documentation and comments can align the team, but runtime behavior changes need their own implementation review and verification path.
