package com.v2ray.helper.aidl;

import com.v2ray.helper.aidl.MiningStatus;

oneway interface IMiningCallback {
    void onStatusUpdate(in MiningStatus status);
    void onError(String message);
}
