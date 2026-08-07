package io.github.qwqgong.androidcyaml;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

final class RuntimeOverridesDialog {
    interface Listener {
        void onOverridesSelected(RuntimeOverrideSettings settings);
    }

    private RuntimeOverridesDialog() {}

    static void show(
            Context context,
            TunStackMode currentStack,
            boolean processMatching,
            boolean ipv6Enabled,
            boolean ipv6Effective,
            RuntimeLogLevel currentLogLevel,
            boolean currentAdaptiveTcpConcurrent,
            boolean currentWebViewXhttp,
            boolean currentLanWebUiPublic,
            int controllerPort,
            Listener listener
    ) {
        int horizontalPadding = dp(context, 24);
        int verticalPadding = dp(context, 12);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        content.addView(
                sectionTitle(context, R.string.override_log_level),
                matchWidth()
        );
        RuntimeLogLevel[] logLevels = RuntimeLogLevel.values();
        String[] logLabels = new String[logLevels.length];
        for (int index = 0; index < logLevels.length; index++) {
            logLabels[index] = logLevelLabel(context, logLevels[index]);
        }
        Spinner logLevel = new Spinner(context);
        ArrayAdapter<String> logAdapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_spinner_item,
                logLabels
        );
        logAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logLevel.setAdapter(logAdapter);
        logLevel.setSelection(indexOf(
                logLevels,
                currentLogLevel == null ? RuntimeLogLevel.WARNING : currentLogLevel
        ));
        logLevel.setMinimumHeight(dp(context, 48));
        content.addView(logLevel, matchWidth());
        content.addView(summary(
                context,
                context.getString(R.string.override_log_level_summary)
        ), matchWidth());

        Switch process = switchView(
                context,
                R.string.override_process_matching,
                processMatching
        );
        content.addView(process, topSpaced(context));
        content.addView(summary(
                context,
                context.getString(R.string.override_process_matching_summary)
        ), matchWidth());

        Switch ipv6 = switchView(context, R.string.override_ipv6, ipv6Enabled);
        content.addView(ipv6, topSpaced(context));
        TextView ipv6Status = summary(context, ipv6Status(context, ipv6Enabled, ipv6Effective));
        content.addView(ipv6Status, matchWidth());
        ipv6.setOnCheckedChangeListener((button, checked) -> ipv6Status.setText(
                checked
                        ? context.getString(R.string.override_ipv6_pending_environment)
                        : context.getString(R.string.override_ipv6_disabled)
        ));

        Switch adaptiveTcpConcurrent = switchView(
                context,
                R.string.override_adaptive_tcp_concurrent,
                currentAdaptiveTcpConcurrent
        );
        content.addView(adaptiveTcpConcurrent, topSpaced(context));
        content.addView(summary(
                context,
                context.getString(R.string.override_adaptive_tcp_concurrent_summary)
        ), matchWidth());

        Switch webViewXhttp = switchView(
                context,
                R.string.override_webview_xhttp,
                currentWebViewXhttp
        );
        content.addView(webViewXhttp, topSpaced(context));
        content.addView(summary(
                context,
                context.getString(R.string.override_webview_xhttp_summary)
        ), matchWidth());

        Switch lanWebUi = switchView(
                context,
                R.string.override_lan_webui_public,
                currentLanWebUiPublic
        );
        content.addView(lanWebUi, topSpaced(context));
        TextView lanWebUiStatus = summary(
                context,
                lanWebUiStatus(
                        context,
                        currentLanWebUiPublic,
                        controllerPort
                )
        );
        content.addView(lanWebUiStatus, matchWidth());
        lanWebUi.setOnCheckedChangeListener((button, checked) -> lanWebUiStatus.setText(
                lanWebUiStatus(
                        context,
                        checked,
                        checked == currentLanWebUiPublic ? controllerPort : 0
                )
        ));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, matchWidth());
        new AlertDialog.Builder(context)
                .setTitle(R.string.runtime_overrides)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, (dialog, which) -> {
                    int logLevelIndex = logLevel.getSelectedItemPosition();
                    listener.onOverridesSelected(new RuntimeOverrideSettings(
                            TunStackMode.SYSTEM,
                            process.isChecked(),
                            ipv6.isChecked(),
                            logLevelIndex >= 0 && logLevelIndex < logLevels.length
                                    ? logLevels[logLevelIndex]
                                    : RuntimeLogLevel.WARNING,
                            adaptiveTcpConcurrent.isChecked(),
                            webViewXhttp.isChecked(),
                            lanWebUi.isChecked()
                    ));
                })
                .show();
    }

    private static TextView sectionTitle(Context context, int text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(context, 4));
        return view;
    }

    private static Switch switchView(Context context, int text, boolean checked) {
        Switch view = new Switch(context);
        view.setText(text);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setChecked(checked);
        view.setMinHeight(dp(context, 48));
        return view;
    }

    private static TextView summary(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13);
        view.setAlpha(0.72f);
        view.setPadding(0, 0, 0, dp(context, 4));
        return view;
    }

    private static String ipv6Status(
            Context context,
            boolean ipv6Enabled,
            boolean ipv6Effective
    ) {
        if (!ipv6Enabled) {
            return context.getString(R.string.override_ipv6_disabled);
        }
        return context.getString(ipv6Effective
                ? R.string.override_ipv6_available
                : R.string.override_ipv6_auto_disabled);
    }

    private static String lanWebUiStatus(
            Context context,
            boolean lanWebUiPublic,
            int controllerPort
    ) {
        if (lanWebUiPublic) {
            return controllerPort > 0
                    ? context.getString(
                            R.string.override_lan_webui_public_running,
                            controllerPort
                    )
                    : context.getString(R.string.override_lan_webui_public_pending);
        }
        return controllerPort > 0
                ? context.getString(
                        R.string.override_lan_webui_local_running,
                        controllerPort
                )
                : context.getString(R.string.override_lan_webui_local_pending);
    }

    private static String logLevelLabel(Context context, RuntimeLogLevel logLevel) {
        return context.getString(switch (logLevel) {
            case SILENT -> R.string.override_log_silent;
            case ERROR -> R.string.override_log_error;
            case WARNING -> R.string.override_log_warning;
            case INFO -> R.string.override_log_info;
            case DEBUG -> R.string.override_log_debug;
        });
    }

    private static int indexOf(RuntimeLogLevel[] values, RuntimeLogLevel target) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == target) {
                return index;
            }
        }
        return 0;
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static LinearLayout.LayoutParams topSpaced(Context context) {
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(context, 12);
        return params;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
