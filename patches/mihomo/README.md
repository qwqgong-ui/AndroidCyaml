# AndroidCyaml mihomo patches

AndroidCyaml checks out the exact `qwqgong-ui/mihomo` commit declared in
`app/build.gradle.kts`, resets that temporary checkout, and applies the files in
`series` in order before compiling the Android `c-shared` library.

These patches are AndroidCyaml implementation details. They must not be merged
into `qwqgong-ui/mihomo/Alpha`, because that branch is also used to build the
desktop core.

The current patch only adds the AndroidCyaml JNI entry package. It does not
replace or edit mihomo's existing desktop entry points, process resolver,
tunnel dispatcher, or configuration parser. Android integration uses exported
mihomo hooks and the `cmfa` build tag.
