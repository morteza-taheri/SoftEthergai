package vn.unlimit.softether.model

import android.os.Parcel
import android.os.Parcelable

data class ClientInfo(
    val productName: String,
    val productVersion: String,
    val productBuild: Int,
    val osName: String,
    val osVersion: String,
    val osProductId: String,
    val hostName: String,
    val clientIpAddress: String,
    val isIPv6: Boolean = clientIpAddress.contains(":"),
    val clientPort: Int,
    val serverHostName: String,
    val serverIpAddress: String,
    val serverPort: Int
) : Parcelable {

    constructor(parcel: Parcel) : this(
        productName = parcel.readString() ?: "",
        productVersion = parcel.readString() ?: "",
        productBuild = parcel.readInt(),
        osName = parcel.readString() ?: "",
        osVersion = parcel.readString() ?: "",
        osProductId = parcel.readString() ?: "",
        hostName = parcel.readString() ?: "",
        clientIpAddress = parcel.readString() ?: "",
        isIPv6 = parcel.readByte() != 0.toByte(),
        clientPort = parcel.readInt(),
        serverHostName = parcel.readString() ?: "",
        serverIpAddress = parcel.readString() ?: "",
        serverPort = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(productName)
        parcel.writeString(productVersion)
        parcel.writeInt(productBuild)
        parcel.writeString(osName)
        parcel.writeString(osVersion)
        parcel.writeString(osProductId)
        parcel.writeString(hostName)
        parcel.writeString(clientIpAddress)
        parcel.writeByte(if (isIPv6) 1 else 0)
        parcel.writeInt(clientPort)
        parcel.writeString(serverHostName)
        parcel.writeString(serverIpAddress)
        parcel.writeInt(serverPort)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ClientInfo> {
        override fun createFromParcel(parcel: Parcel): ClientInfo {
            return ClientInfo(parcel)
        }

        override fun newArray(size: Int): Array<ClientInfo?> {
            return arrayOfNulls(size)
        }
    }
}
