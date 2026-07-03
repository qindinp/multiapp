# PR-9 Provider Method Evidence Execution Plan

Date: 2026-07-03
Owner: hosted container runtime
Status: executing

## Goal

Move Blueprint Phase 7 / PR-9 from Provider MVP toward method-level provider dispatcher evidence.

This PR is narrow:

- prove hosted provider method dispatch for `query`, `insert`, `update`, `delete`, `call`, and `openFile`;
- add explicit method evidence for `bulkInsert` and `openAssetFile`;
- keep the ordinary hosted baseline on instance-scoped proxy provider routing;
- do not enable process-wide provider hooks by default;
- do not claim complete provider virtualization.

## Scope

Runtime changes:

- keep the existing `<instanceId>.provider-proxy.properties` compatibility evidence;
- add method-scoped files under `files/hosted_launch_evidence`:
  - `<instanceId>.provider-query.properties`
  - `<instanceId>.provider-insert.properties`
  - `<instanceId>.provider-update.properties`
  - `<instanceId>.provider-delete.properties`
  - `<instanceId>.provider-call.properties`
  - `<instanceId>.provider-open-file.properties`
  - `<instanceId>.provider-open-asset-file.properties`
  - `<instanceId>.provider-bulk-insert.properties`
- route `StubContentProvider.openFile`, `openAssetFile`, and `bulkInsert` through `VirtualProviderDispatcher`;
- make `call` dispatch to the guest provider when a proxy URI is provided.

Fixture changes:

- extend `ProbeProvider` with deterministic `insert`, `update`, `delete`, `bulkInsert`, `call`, `openFile`, and `openAssetFile` behavior;
- extend `MainActivity` hosted probe to invoke all PR-9 provider methods through the stub URI.

Tests:

- unit-test new provider evidence operation mapping;
- unit-test method evidence component naming;
- add a focused androidTest that launches a hosted minimal instance and asserts all PR-9 provider method evidence files.

## Acceptance

Fresh verification must include:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:testDebugUnitTest :test-fixtures:minimal-app:testDebugUnitTest :test-fixtures:minimal-app:assembleDebug :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Device evidence should be captured through the hosted guest production path when the device install/start policy allows it. If MIUI/HyperOS blocks instrumentation, the accepted manual fallback is the same pattern used for PR-8: install/launch manually, then `run-as com.multiapp.app` dump `files/hosted_launch_evidence`.

## Actual 2026-07-03 Execution

Local verification:

```text
command=.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:testDebugUnitTest :test-fixtures:minimal-app:testDebugUnitTest :test-fixtures:minimal-app:assembleDebug :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
result=BUILD SUCCESSFUL
unitTestXmlSummary=471 tests, 0 failures, 0 errors
```

Device status:

```text
adb devices -l => no online devices
adb connect 192.168.2.42:44113 => 10060 connection timeout
ping 192.168.2.42 => request timed out / destination host unreachable
adb mdns services => no discovered services
```

Current PR-9 status:

```text
PR-9 provider method evidence implementation and local verification are complete.
Device evidence is pending because the previous wireless debugging endpoint is no longer reachable.
Next device action: re-enable Wireless debugging on the phone, confirm the workstation and phone are on the same Wi-Fi network, and use the phone's current IP:port for adb pair/connect before installing the new APKs.
```

## Non-Goals

- no default process-wide provider hook;
- no cross-process provider Binder service;
- no ContentObserver/grant URI permission completion claim;
- no statement that provider virtualization is complete.
