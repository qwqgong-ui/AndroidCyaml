package io.github.qwqgong.androidcyaml;

import io.github.qwqgong.androidcyaml.RuntimeSnapshotPayload;

oneway interface IControlCallback {
    void onStateChanged(in RuntimeSnapshotPayload payload);
}
