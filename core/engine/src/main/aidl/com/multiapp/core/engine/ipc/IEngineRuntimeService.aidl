package com.multiapp.core.engine.ipc;

import android.os.Bundle;

interface IEngineRuntimeService {
    Bundle queryRuntime(String instanceId);
    Bundle authorizeActivityLaunch(String capabilityToken, String instanceId, long runtimeEpoch, String engineSessionId, String processSlot, String proxyActivityClassName, String guestActivityClassName);
    Bundle acknowledgeActivityResumed(String instanceId, long runtimeEpoch, String engineSessionId, String processSlot, String capabilityToken);
    Bundle queryEvidence(String instanceId);
    Bundle planActivity(String instanceId, in Bundle request);
    boolean recordActivityDispatch(String instanceId, in Bundle result);
    Bundle mutateActivity(String instanceId, String operation, in Bundle request);
    Bundle consumeActivity(String instanceId, String operation, in Bundle request);
    Bundle syncActivityTaskState(String instanceId, String reason, in Bundle snapshot);
    Bundle queryActivityTaskState(String instanceId);
    Bundle resolveProviderAuthority(String callerInstanceId, in Bundle request);
    Bundle planProvider(String instanceId, in Bundle request);
    boolean recordProviderDispatch(String instanceId, in Bundle result);
    Bundle queryProviderRuntimeState(String instanceId);
    Bundle grantProviderUriPermission(String ownerInstanceId, in Bundle request);
    Bundle revokeProviderUriPermission(String ownerInstanceId, in Bundle request);
    Bundle checkProviderUriPermission(String targetInstanceId, in Bundle request);
    Bundle takePersistableProviderUriPermission(String targetInstanceId, in Bundle request);
    Bundle releasePersistableProviderUriPermission(String targetInstanceId, in Bundle request);
    Bundle checkPermission(String instanceId, String permissionName);
    Bundle queryPermissionRuntimeState(String instanceId);
    Bundle queryAppOp(String instanceId, in Bundle request);
    Bundle planService(String instanceId, in Bundle request);
    boolean recordServiceDispatch(String instanceId, in Bundle result);
    Bundle queryServiceRuntimeState(String instanceId);
    Bundle planBroadcast(String instanceId, in Bundle request);
    boolean recordBroadcastDispatch(String instanceId, in Bundle result);
    Bundle queryBroadcastRuntimeState(String instanceId);
    boolean recordOperationEvidence(String instanceId, in Bundle evidence);
    boolean stopRuntime(String instanceId, long runtimeEpoch);
}
