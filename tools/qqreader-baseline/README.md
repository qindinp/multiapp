# QQ Reader Baseline Capture

## Hosted v2 diagnostics capture

For the v2 hosted container route, use the hosted diagnostics capture tool after
creating and launching a QQ Reader hosted instance from MultiApp manually:

```powershell
.\tools\qqreader-baseline\run-hosted-diagnostics-capture.ps1 -Device 192.168.2.122:33811
```

If you already know the hosted `instanceId`, pass it to limit hosted evidence
collection to that instance:

```powershell
.\tools\qqreader-baseline\run-hosted-diagnostics-capture.ps1 -Device 192.168.2.122:33811 -InstanceId <instance-id>
```

This mode is observe-only. It does not install LSPlant/Xposed, does not enable
business native stubs/wrappers, does not add no-op patches, and does not modify
the protected shell. The summary records those gates explicitly:

```text
mode=hosted-register-natives-only-diagnostics
lsplantEnabled=false
xposedEnabled=false
businessNativeStubsEnabled=false
businessNativeWrappersEnabled=false
noOpPatchesEnabled=false
interface20Verdict=<verdict>
interface20VerdictReason=<reason>
```

Outputs are written under `.tmp/qqreader-hosted-diagnostics-<timestamp>/`:

- `summary.txt`
- `logcat.txt`
- `crash.txt`
- `exit-info.txt`
- `hosted-launch-evidence.txt`
- `instances.txt`
- `storage-files.txt`
- `host-package.txt`
- `origin-package.txt`

The verdict is diagnostic evidence, not a compatibility claim. A failure verdict
should point to a container/native-loading gap to fix next.

## Legacy Stub baseline capture

This tool captures first-run evidence for the QQ Reader hook-free baseline path.
It does not create the clone. Generate and install the QQ Reader baseline clone
from the MultiApp UI first, then run this script.

## Usage

```powershell
.\tools\qqreader-baseline\run-baseline-capture.ps1
```

If multiple devices are online:

```powershell
.\tools\qqreader-baseline\run-baseline-capture.ps1 -Device <serial>
```

If multiple QQ Reader clone packages are installed:

```powershell
.\tools\qqreader-baseline\run-baseline-capture.ps1 -ClonePackage com.qq.reader.clonestub_xxx
```

If the current `com.multiapp.app` build is already installed and only the clone
needs to be launched:

```powershell
.\tools\qqreader-baseline\run-baseline-capture.ps1 -SkipInstall
```

## Outputs

Files are written under `.tmp` with a `qqreader-baseline-<timestamp>` prefix:

- `*-devices.txt`
- `*-multiapp-install.txt`
- `*-multiapp-package.txt`
- `*-clone-package.txt`
- `*-start.txt`
- `*-logcat.txt`
- `*-crash.txt`
- `*-exit-info.txt`
- `*-process.txt`
- `*-summary.txt`

The first pass should inspect:

- `BOOTSTRAP stage=` markers in `*-logcat.txt`
- `AndroidRuntime`, `FATAL EXCEPTION`, `SIGSEGV`, or `UnsatisfiedLinkError`
- `dumpsys activity exit-info` reason and trace data

The baseline result is valid only if the clone was generated with the default
hook-free profile, not the legacy QQ Reader special experiment profile.
