# Packed-App Compatibility Campaign - Status (2026-08-04)

## Goal
Make packed (加固) real apps (WeChat/QQ reader/WPS/Weibo + Kugou/Gaode/minimal) run in hosted virtual runtime on Xiaomi 2509FPN0BC (API 36 / Android 16, arm64-v8a).

## Confirmed root cause
- Legacy LoaderFactory ran PackerRuntimeDispatcher (shell adaptation); hosted new path bypassed LoaderFactory entirely, so packed apps reached Application creation with NO shell adaptation.
- PackerRuntimeDispatcher default only registered JiaguRuntime (360, libjiagu_vip.so). Non-360 shells were "NO_PACKER_DETECTED -> skip".

## Changes landed (all JVM tests green)
- core/loader: RuntimeStage.PACKER_RUNTIME(55) IRREVERSIBLE
- core/loader: PackerRuntimeStage (detect -> execute, non-blocking, evidence)
- core/loader: HostedRuntimeBootstrap.attachAndLaunch inserts Packer stage after ClassLoader, before Application
- core/engine: HostedRuntimeEngine passes packerRuntimeEnabled = providerHookEnabled
- core/hook: PackerRuntimeAdaptation interface (detect + execute); PackerRuntimeDispatcher implements it
- core/hook: GenericPackerRuntime (PackerDetector-based, generic StubApp/ApplicationWrapper load + registerAllMissingNativeMethods fallback); registered after Jiagu360
- Tests: PackerRuntimeStageTest 6/6, GenericPackerRuntimeTest 3/3, loader+hook full suites green

## APK evidence
- app-hosted-debug.apk rebuilt 2026-08-04 with PackerRuntimeStage (31 hits), GenericPackerRuntime (18), PackerRuntimeAdaptation (3), PackerRuntimeDispatcher (26) - verified via apkanalyzer dex packages
- app-hosted-debug-androidTest.apk rebuilt (contains loader classes; test drives main app)

## Device status / blocker
- Device went offline mid-smoke-test (adb-8abd276a-IP9XgB._adb-tls-connect._tcp). TCP 192.168.2.41:38817 refuses; mdns empty; pair needs code on phone.
- Recovery: user re-opens Developer Options -> Wireless debugging (or USB once); then adb mdns services -> connect; reinstall both APKs; rerun RealAppCompatibilitySmokeTest.

## Next after device online
1. Install app-hosted-debug.apk + app-hosted-debug-androidTest.apk
2. Run RealAppCompatibilitySmokeTest; check Packer stage in stage chain + shell evidence per app
3. Per-shell adaptation (D-S-L/W order): Weibo libslib.so nativeInit; Qidian/WPS GuestAppCreate targetException stack; WeChat bootstrap timeout vs verification failure
4. Full 7-app smoke + all-module unit regression
## Evidence visibility fix (2026-08-04, offline)
- HostedRuntimeBootstrap.buildDiagnosticsEvidence now emits packer_stage_status / packer_name / packer_skip_reason / packer_jiagu_loaded / packer_stub_verified
- ProtectedDiagnosticsEvidenceFormatter writes packerStageStatus / packerName / packerSkipReason / packerJiaguLoaded / packerStubVerified / packerMessage into all 4 per-instance evidence files
- So next device dossier will show per-app shell detection/adaptation state directly
- FormatterTest extended; :core:loader:testDebugUnitTest green; app-hosted-debug.apk rebuilt
## Critical finding: registerAllMissingNativeMethods is a no-op (2026-08-04, offline)
- NativeHookBridge.registerAllMissingNativeMethods -> nativeRegisterAllMissingNativeMethods iterates only knownNativeClasses[] in native-hook.cpp
- That whitelist contains ONLY "__multiapp.noop.NativeClass" (intentionally emptied: blanket null/false stubs break QQ Reader network content)
- Therefore GenericPackerRuntime's "missing native fallback" currently registers ~0 methods; Weibo SLib.nativeInit will NOT be covered by it
- Correct fix depends on why SLib.nativeInit is unbound:
  (a) libslib.so exists but not loaded -> namespace/load fix (stub would mask real function)
  (b) lib absent/optional -> per-class stub registration is acceptable
- Decision deferred until device evidence: need APK native lib list + logcat for Weibo
## WPS engine_authority root-cause analysis (2026-08-04, offline)
- Manifest DOES declare engine provider (EngineBinderProvider @ :engine, authority host.multiapp.engine.server) and 24 bootstrap providers v0-v23 - not a manifest gap
- WPS smoke row: create=PASS, launch=FAIL, detail=engine_authority_unavailable_or_unknown_result, evidenceCount=5
- evidenceCount=5 means guest bootstrap DID write evidence files -> guest launch ran; failure is host-side engine IPC result loss (EngineVirtualizationIpc.complete() returns engine_authority_unavailable when remote==null)
- Most likely: engine IPC connection dropped mid-WPS-launch (after WeChat/Qidian/Weibo heavy launches); needs device logcat to confirm engine process death vs provider call failure
- WeChat: bootstrap provider malformed/stale - separate provider-validation path; also needs device evidence
## WeChat main-process System.exit(1) root cause + fix (2026-08-04, device logcat verified)
- Instrumentation run smoke-logcat7.txt ended normally at 16:39:52 (app_process System.exit status: 0) - NOT a hang. The 7-app sweep completed; WeChat was the FAIL that took the analysis time.
- WeChat main process (slot com.multiapp.app:v7, pid 22128) reached APPLICATION_FINALIZED, then during srvinit started its own :sandbox ExceptionMonitorService; our AMS dispatcher remapped it cross-process to StubServiceV1 (com.multiapp.app:v1, pid 22407). At 16:38:10.748 thread 22362 called System.exit(1): ART logged "System.exit called, status: 1" then "VM exiting with result code 1, cleanup skipped."
- StubServiceV1 later (16:38:11.021) logged runtimeBindStatus=FAILED with componentProcessAttachFailed / SecurityException component_process_launch_capability_not_found, but that was AFTER v7 already died, so the service-bind attach failure is a symptom/secondary path, not the immediate exit trigger.
- Why exit suppression did NOT save WeChat: native-hook.cpp hooked_exit/hooked__exit (and GOT variants) suppress status==1 unconditionally, but exit/_exit are gated on HOOK_PROFILE_FULL, and FULL hooks are installed only by JiaguRuntime.prepareFiles -> NativeHookBridge.initNativeHooks (nativeInit, profiles=0x2). QQReader survived its own System.exit(1) only because libjiagu_vip.so was detected (Jiagu360). WeChat = NO_PACKER_DETECTED -> PackerRuntimeStage returned SKIPPED -> only path-redirect hooks (profiles=0x1) were installed -> exit(1) passed through.
- Fix landed: PackerRuntimeStage now installs initNativeHooks(policy=compatibilityHookPolicy) BEFORE packer detection when packerEnabled=true, so every guest (packed or not) gets the FULL profile (exit/_exit suppression, dlopen, ptrace, property spoofing) before Application creation. Evidence preDetectNativeHooks recorded in both detected and NO_PACKER_DETECTED outcomes. :core:loader:testDebugUnitTest 814/814 green.
- WeChat secondary findings: (1) process-name getter in5.f1.a() was called in a tight loop (2 logcat lines per call, tens of thousands of entries) in the ~0.8s before exit on threads 22362/22390 - likely WeChat srvinit spinning; suppress exit then re-observe. (2) Old WeChat instance v22 (pid 22485) failed on a DIFFERENT path with no MicroMsgProcessNameCompat hook installed (ProcessDescriptor suffix :v22 -> [GT]HotPool NPE -> System.exit(1)); needs separate process-bootstrap coverage. (3) Weibo/WPS remain FAIL for their own reasons (SLib.nativeInit unbound; engine IPC result loss) - unchanged this round.
- Next device step: rebuild app-hosted-debug.apk, rerun RealAppCompatibilitySmokeTest, confirm v7 no longer dies at srvinit and next blocker appears in logcat/dossier.
## WeChat exit-suppression fix VERIFIED on-device + next blocker (2026-08-04, second smoke run)
- Rebuilt + reinstalled app-hosted-debug.apk (PackerRuntimeStage pre-detect initNativeHooks) and reran RealAppCompatibilitySmokeTest on the same device: INSTRUMENTATION OK (1 test), Time 573.8s. Per-app dossier: QQReader/Kugou/Amap/Minimal BOOTSTRAPPED; Weibo FAIL (SLib.nativeInit, unchanged); WPS FAIL (engine IPC stale response, unchanged); WeChat FAIL.
- Fix verified: 6 processes logged "PackerRuntimeStage: pre-detect native hooks (exit/_exit suppression): true" and "Hooked exit in libc.so via ShadowHook". In slot v15 (pid 5824), WeChat-family fatal path called System.exit(1); ART logged "System.exit called, status: 1" and our hook logged "_exit intercepted: suppressing status=1 self-exit" - process SURVIVED. Exit suppression no longer depends on packer detection.
- WeChat next blocker (regression in failure mode): BOTH WeChat instances (48b9f70e@v7 pid 4638, 2283109c@v22 pid 5006) crashed ~7-8s after start with uncaught [GT]HotPool NPE: java.lang.NullPointerException reading com.tencent.mm.app.t5.e/f (ProcessDescriptor t5 == null) in x75.a$$b/c.run. Uncaught exception death is NOT suppressible by the exit hook.
- v22 (5006) full log available: guest runtime init -> path redirect (profiles=0x1) -> package-manager proxies -> VirtualInstrumentation newApplication -> "MicroMsg.ProcessDescriptor: Fail to get current process descriptor, curr_process_suffix: :v22" x4 -> HotPool NPE. NO PackerRuntimeStage logs (no LSPlant init, no MicroMsgProcessNameCompat hook, no pre-detect hooks, no PackerDetector) -> the stage did NOT run in WeChat processes this run, while it ran in all 6 other apps. Root cause of stage-skip still open (logcat rotation hid v7 early logs; v22 logs are complete and show the skip).
- Next diagnostic (single WeChat instance rerun with logcat persisted to device file, no rotation) to answer: (a) does PackerRuntimeStage execute in WeChat processes, and if skipped, at which gate (packerEnabled/providerHookEnabled vs WeChat hook exception); (b) whether in5.f1.a hook returns com.tencent.mm before t5 init; (c) whether t5-null comes from the suffix lookup failing despite the hook.## providerHookEnabled=true fix for UI launch path (2026-08-04, offline code analysis)

- Root cause confirmed: LauncherViewModel.launchInstance() passed LaunchInstanceRequest(instanceId=id) WITHOUT providerHookEnabled=true
- This meant HostedRuntimeEngine received providerHookEnabled=false -> packerRuntimeEnabled=false -> PackerRuntimeStage SKIPPED entirely
- Smoke test (RealAppCompatibilitySmokeTest) explicitly passed providerHookEnabled=true, so it worked; UI manual path did not
- Fix committed: 45e87d4 - LauncherViewModel now passes providerHookEnabled=true, tests synced
- Expected impact on WeChat HotPool NPE: PackerRuntimeStage will now execute -> MicroMsgProcessNameCompat.installProcessNameHook() hooks in5.f1.a() -> returns "com.tencent.mm" -> ProcessDescriptor t5 resolves correctly -> no NPE
- Expected impact on exit suppression: preDetectNativeHooks now installs FULL profile (exit/_exit suppression) for all guest apps including WeChat
- NOT expected to fix Weibo SLib.nativeInit (separate issue: native lib loading/namespace)
- NOT expected to fix WPS engine_authority (separate issue: engine IPC connection stability)
- Next: rebuild APK, rerun RealAppCompatibilitySmokeTest, verify WeChat passes

