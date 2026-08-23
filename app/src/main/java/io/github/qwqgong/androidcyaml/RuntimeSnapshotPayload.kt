package io.github.qwqgong.androidcyaml

import android.os.Parcel
import android.os.Parcelable

/** Typed replacement for IControlCallback's previous 13 positional parameters. */
class RuntimeSnapshotPayload(
    wireState: Int,
    detail: String?,
    val alwaysOn: Boolean,
    val lockdown: Boolean,
    dashboardUrl: String?,
    val controllerPort: Int,
    wireProcessMatchingMode: String?,
    val ipv6Enabled: Boolean,
    val ipv6Effective: Boolean,
    wireLogLevel: String?,
    val adaptiveTcpConcurrent: Boolean,
    val webViewXhttp: Boolean,
    val lanWebUiPublic: Boolean,
) : Parcelable {
    val state: RuntimeState = RuntimeState.values().getOrElse(wireState) { RuntimeState.FAILED }

    val detail: String = detail ?: ""

    val dashboardUrl: String = dashboardUrl ?: ""

    val processMatchingMode: ProcessMatchingMode =
        parseProcessMatchingMode(wireProcessMatchingMode)

    val logLevel: RuntimeLogLevel = parseLogLevel(wireLogLevel)

    private constructor(source: Parcel) : this(
        source.readInt(),
        source.readString(),
        source.readBoolean(),
        source.readBoolean(),
        source.readString(),
        source.readInt(),
        source.readString(),
        source.readBoolean(),
        source.readBoolean(),
        source.readString(),
        source.readBoolean(),
        source.readBoolean(),
        source.readBoolean(),
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(state.ordinal)
        dest.writeString(detail)
        dest.writeBoolean(alwaysOn)
        dest.writeBoolean(lockdown)
        dest.writeString(dashboardUrl)
        dest.writeInt(controllerPort)
        dest.writeString(processMatchingMode.wireValue)
        dest.writeBoolean(ipv6Enabled)
        dest.writeBoolean(ipv6Effective)
        dest.writeString(logLevel.wireValue)
        dest.writeBoolean(adaptiveTcpConcurrent)
        dest.writeBoolean(webViewXhttp)
        dest.writeBoolean(lanWebUiPublic)
    }

    companion object {
        private fun parseProcessMatchingMode(wireValue: String?): ProcessMatchingMode = try {
            ProcessMatchingMode.fromWireValue(wireValue)
        } catch (ignored: IllegalArgumentException) {
            ProcessMatchingMode.ALWAYS
        }

        private fun parseLogLevel(wireValue: String?): RuntimeLogLevel = try {
            RuntimeLogLevel.fromWireValue(wireValue)
        } catch (ignored: IllegalArgumentException) {
            RuntimeLogLevel.WARNING
        }

        @JvmField
        val CREATOR: Parcelable.Creator<RuntimeSnapshotPayload> =
            object : Parcelable.Creator<RuntimeSnapshotPayload> {
                override fun createFromParcel(source: Parcel): RuntimeSnapshotPayload =
                    RuntimeSnapshotPayload(source)

                override fun newArray(size: Int): Array<RuntimeSnapshotPayload?> =
                    arrayOfNulls(size)
            }
    }
}
