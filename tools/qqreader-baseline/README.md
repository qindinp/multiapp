# QQ Reader Baseline Capture

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
