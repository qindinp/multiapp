# PR-3 Install / Instance JVM Evidence

Date: 2026-06-29
Owner: hosted container runtime
Review model: Xiaomi-style role split + owner gate
Status: JVM evidence complete; device evidence pending

## 0. Purpose

PR-3 proves the install/instance fact-source chain with deterministic JVM tests before deeper runtime claims.

The owner decision is intentionally narrow:

```text
Prioritize deterministic JVM tests.
Prove InstallRecord is the fact source.
Prove InstanceRecord only references originPackageName.
Prove HostedRuntimeBootstrap resolves the APK from InstallRecord.originApkPath.
Do not touch device/androidTest in this slice.
Do not describe JVM evidence as device completion.
```

## 1. Xiaomi-style role split

| Role | Responsibility | Gate |
| --- | --- | --- |
| Runtime owner | Keep PR-3 scope narrow and prevent overclaiming. | This document and final wording. |
| Install/instance owner | Maintain `VirtualInstallService`, `InstallRecord`, `InstallRecordStore`, `DefaultInstanceManager`, and `VirtualInstanceRecord` facts. | JVM tests for import/create/persist behavior. |
| Bootstrap owner | Verify launch consumption uses install facts, not duplicated instance facts. | JVM test proving classloader APK path comes from `InstallRecord.originApkPath`. |
| Security owner | Intervene only on file/path/packageName trust-boundary changes. | Scoped security review when packageName/APK path behavior changes. |
| Evidence owner | Separate JVM, build, and device evidence. | No device claim without device/logcat/run-as evidence. |

## 2. Covered evidence

### 2.1 InstallRecord is the fact source

Covered by `core/model/src/test/java/com/multiapp/core/model/installer/VirtualInstallServiceTest.kt`:

```text
ensureInstallRecord persists VirtualApp manifest facts before instance creation
ensureInstallRecord refreshes stale record when VirtualApp version changes
ensureInstallRecord rejects unsafe packageName before creating artifact
importFromMetadata rejects unsafe packageName before store lookup
```

Facts covered:

```text
packageName
originApkPath copied into artifact storage
originApkSha256
versionCode
versionName
minSdk
targetSdk
applicationClassName
packageLabel
permissions
activities
services
receivers
providers
abiList
```

Notes:

```text
VirtualApp.nativeAbis is persisted as abiList.
InstallRecord.nativeLibraries is reserved for actual library filenames and is not populated from ABI names.
```

### 2.2 InstallRecordStore is path-safe for packageName input

Covered by `core/model/src/test/java/com/multiapp/core/model/installer/InstallRecordStoreTest.kt`:

```text
load rejects unsafe packageName before filesystem access
delete rejects unsafe packageName before filesystem access
```

Production guardrails:

```text
requireSafeInstallPackageName(packageName)
origin APK canonical regular-file check
artifact destination canonical containment under artifactDir
```

### 2.3 InstanceRecord only references originPackageName

Covered by `core/model/src/test/java/com/multiapp/core/model/instance/InstanceManagerTest.kt`:

```text
createInstance persists only originPackageName reference to InstallRecord facts
```

The persisted instance JSON is asserted not to duplicate install facts:

```text
originApkPath
originApkSha256
originCertSha256
activities
nativeLibraries
```

### 2.4 HostedRuntimeBootstrap consumes InstallRecord APK path

Covered by `core/loader/src/test/java/com/multiapp/core/loader/HostedRuntimeBootstrapTest.kt`:

```text
run uses InstallRecord originApkPath as classloader APK source
```

The test creates a decoy APK path under the instance data root and verifies the classloader factory receives `InstallRecord.originApkPath` instead.

## 3. Verification evidence

Command run from repository root:

```bash
./gradlew --no-parallel :core:model:testDebugUnitTest :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Observed result:

```text
BUILD SUCCESSFUL in 1m 12s
107 actionable tasks: 20 executed, 87 up-to-date
```

Non-blocking warning observed:

```text
Android Gradle Plugin 8.7.3 was tested up to compileSdk = 35, while this project uses compileSdk = 36.
```

## 4. Not covered by PR-3

This PR-3 evidence does **not** cover:

```text
device/androidTest evidence
UI select installed app on a physical/emulated device
manual QQ Reader hosted launch
15s logcat capture
run-as dataRoot inspection
delete cleanup for runtime cache/activity/service/provider/broadcast records on device
crash-free launch rate
ordinary/protected app compatibility matrix
```

## 5. Allowed wording

```text
PR-3 JVM/unit evidence added.
InstallRecord -> InstanceRecord -> HostedRuntimeBootstrap fact-source chain is covered by deterministic JVM tests.
Device evidence pending.
PR-3 remains JVM-evidence only.
```

## 6. Disallowed wording

Do not use these phrases based on PR-3 alone:

```text
PR-3 device E2E complete
UI install/create/launch/delete complete on device
QQ Reader fixed
container runtime complete
VirtualInstall / VirtualInstance device flow done
```

## 7. Owner verdict

```text
PR-3 JVM evidence: COMPLETE.
PR-3 device evidence: PENDING.
Next owner action: enter PR-4 RuntimeBootstrap stage pipeline planning without expanding PR-3.
```
