package com.v2ray.helper.aidl;

import com.v2ray.helper.aidl.MiningStatus;
import com.v2ray.helper.aidl.IMiningCallback;

interface IMiningService {
    boolean startMining();
    boolean stopMining();
    boolean setCpuLimit(int percent);
    MiningStatus getStatus();
    long getHashrate();
    void registerCallback(IMiningCallback callback);
    void unregisterCallback(IMiningCallback callback);
}
