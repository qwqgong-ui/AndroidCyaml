package io.github.qwqgong.androidcyaml;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed replacement for IControlCallback's previous 13 positional parameters. */
final class RuntimeSnapshotPayload implements Parcelable {
    private final RuntimeState state;
    private final String detail;
    private final boolean alwaysOn;
    private final boolean lockdown;
    private final String dashboardUrl;
    private final int controllerPort;
    private final ProcessMatchingMode processMatchingMode;
    private final boolean ipv6Enabled;
    private final boolean ipv6Effective;
    private final RuntimeLogLevel logLevel;
    private final boolean adaptiveTcpConcurrent;
    private final boolean webViewXhttp;
    private final boolean lanWebUiPublic;

    RuntimeSnapshotPayload(
            int wireState,
            String detail,
            boolean alwaysOn,
            boolean lockdown,
            String dashboardUrl,
            int controllerPort,
            String wireProcessMatchingMode,
            boolean ipv6Enabled,
            boolean ipv6Effective,
            String wireLogLevel,
            boolean adaptiveTcpConcurrent,
            boolean webViewXhttp,
            boolean lanWebUiPublic
    ) {
        RuntimeState[] states = RuntimeState.values();
        this.state = wireState >= 0 && wireState < states.length
                ? states[wireState]
                : RuntimeState.FAILED;
        this.detail = detail == null ? "" : detail;
        this.alwaysOn = alwaysOn;
        this.lockdown = lockdown;
        this.dashboardUrl = dashboardUrl == null ? "" : dashboardUrl;
        this.controllerPort = controllerPort;
        this.processMatchingMode = parseProcessMatchingMode(wireProcessMatchingMode);
        this.ipv6Enabled = ipv6Enabled;
        this.ipv6Effective = ipv6Effective;
        this.logLevel = parseLogLevel(wireLogLevel);
        this.adaptiveTcpConcurrent = adaptiveTcpConcurrent;
        this.webViewXhttp = webViewXhttp;
        this.lanWebUiPublic = lanWebUiPublic;
    }

    private RuntimeSnapshotPayload(Parcel in) {
        this(
                in.readInt(),
                in.readString(),
                in.readBoolean(),
                in.readBoolean(),
                in.readString(),
                in.readInt(),
                in.readString(),
                in.readBoolean(),
                in.readBoolean(),
                in.readString(),
                in.readBoolean(),
                in.readBoolean(),
                in.readBoolean()
        );
    }

    RuntimeState state() {
        return state;
    }

    String detail() {
        return detail;
    }

    boolean alwaysOn() {
        return alwaysOn;
    }

    boolean lockdown() {
        return lockdown;
    }

    String dashboardUrl() {
        return dashboardUrl;
    }

    int controllerPort() {
        return controllerPort;
    }

    ProcessMatchingMode processMatchingMode() {
        return processMatchingMode;
    }

    boolean ipv6Enabled() {
        return ipv6Enabled;
    }

    boolean ipv6Effective() {
        return ipv6Effective;
    }

    RuntimeLogLevel logLevel() {
        return logLevel;
    }

    boolean adaptiveTcpConcurrent() {
        return adaptiveTcpConcurrent;
    }

    boolean webViewXhttp() {
        return webViewXhttp;
    }

    boolean lanWebUiPublic() {
        return lanWebUiPublic;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(state.ordinal());
        dest.writeString(detail);
        dest.writeBoolean(alwaysOn);
        dest.writeBoolean(lockdown);
        dest.writeString(dashboardUrl);
        dest.writeInt(controllerPort);
        dest.writeString(processMatchingMode.wireValue());
        dest.writeBoolean(ipv6Enabled);
        dest.writeBoolean(ipv6Effective);
        dest.writeString(logLevel.wireValue());
        dest.writeBoolean(adaptiveTcpConcurrent);
        dest.writeBoolean(webViewXhttp);
        dest.writeBoolean(lanWebUiPublic);
    }

    private static ProcessMatchingMode parseProcessMatchingMode(String wireValue) {
        try {
            return ProcessMatchingMode.fromWireValue(wireValue);
        } catch (IllegalArgumentException ignored) {
            return ProcessMatchingMode.ALWAYS;
        }
    }

    private static RuntimeLogLevel parseLogLevel(String wireValue) {
        try {
            return RuntimeLogLevel.fromWireValue(wireValue);
        } catch (IllegalArgumentException ignored) {
            return RuntimeLogLevel.WARNING;
        }
    }

    public static final Creator<RuntimeSnapshotPayload> CREATOR = new Creator<>() {
        @Override
        public RuntimeSnapshotPayload createFromParcel(Parcel in) {
            return new RuntimeSnapshotPayload(in);
        }

        @Override
        public RuntimeSnapshotPayload[] newArray(int size) {
            return new RuntimeSnapshotPayload[size];
        }
    };
}
