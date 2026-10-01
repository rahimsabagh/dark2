package com.v2ray.helper.aidl

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable data class representing the real-time operational status
 * and telemetry of the mining helper service.
 */
data class MiningStatus(
    val isRunning: Boolean = false,
    val cpuLimitPercent: Int = 50,
    val hashrateHps: Long = 0L,
    val statusMessage: String = "STOPPED",
    val uptimeSeconds: Long = 0L,
    val isCharging: Boolean = false,
    val isWifiConnected: Boolean = false,
    val isThrottled: Boolean = false
) : Parcelable {

    constructor(parcel: Parcel) : this(
        isRunning = parcel.readByte() != 0.toByte(),
        cpuLimitPercent = parcel.readInt(),
        hashrateHps = parcel.readLong(),
        statusMessage = parcel.readString() ?: "STOPPED",
        uptimeSeconds = parcel.readLong(),
        isCharging = parcel.readByte() != 0.toByte(),
        isWifiConnected = parcel.readByte() != 0.toByte(),
        isThrottled = parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (isRunning) 1 else 0)
        parcel.writeInt(cpuLimitPercent)
        parcel.writeLong(hashrateHps)
        parcel.writeString(statusMessage)
        parcel.writeLong(uptimeSeconds)
        parcel.writeByte(if (isCharging) 1 else 0)
        parcel.writeByte(if (isWifiConnected) 1 else 0)
        parcel.writeByte(if (isThrottled) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<MiningStatus> {
        override fun createFromParcel(parcel: Parcel): MiningStatus = MiningStatus(parcel)
        override fun newArray(size: Int): Array<MiningStatus?> = arrayOfNulls(size)
    }
}
