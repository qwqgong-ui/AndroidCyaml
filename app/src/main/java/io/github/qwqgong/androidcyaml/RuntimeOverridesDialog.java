package io.github.qwqgong.androidcyaml;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
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
            ProcessMatchingMode currentProcessMatchingMode,
            boolean ipv6Enabled,
            boolean ipv6Effective,
            RuntimeLogLevel currentLogLevel,
            boolean currentAdaptiveTcpConcurrent,
            boolean currentWebViewXhttp,
            boolean currentLanWebUiPublic,
            int controllerPort,
            Listener listener
    ) {
        int horizontalPadding = dp(context, 16);
        int verticalPadding = dp(context, 4);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        TextView logSummary = summary(
                context,
                context.getString(R.string.override_log_level_summary)
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
        logLevel.setMinimumHeight(dp(context, 40));
        content.addView(helpSpinnerRow(
                context,
                R.string.override_log_level,
                logSummary,
                logLevel
        ), matchWidth());
        content.addView(logSummary, matchWidth());

        TextView processSummary = summary(
                context,
                context.getString(R.string.override_process_matching_summary)
        );
        ProcessMatchingMode[] processModes = {
                ProcessMatchingMode.STRICT,
                ProcessMatchingMode.ALWAYS,
                ProcessMatchingMode.OFF
        };
        String[] processLabels = {
                context.getString(R.string.override_process_strict_compact),
                context.getString(R.string.override_process_always_compact),
                context.getString(R.string.override_process_off_compact)
        };
        Spinner processMode = new Spinner(context);
        ArrayAdapter<String> processAdapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_spinner_item,
                processLabels
        );
        processAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        processMode.setAdapter(processAdapter);
        processMode.setSelection(indexOf(
                processModes,
                currentProcessMatchingMode == null
                        ? ProcessMatchingMode.ALWAYS
                        : currentProcessMatchingMode
        ));
        processMode.setMinimumHeight(dp(context, 40));
        content.addView(helpSpinnerRow(
                context,
                R.string.override_process_matching,
                processSummary,
                processMode
        ), topSpaced(context));
        content.addView(processSummary, matchWidth());

        Switch ipv6 = switchView(context, ipv6Enabled);
        content.addView(switchRow(
                context,
                R.string.override_ipv6,
                ipv6
        ), topSpaced(context));
        TextView ipv6Status = summary(context, ipv6Status(context, ipv6Enabled, ipv6Effective));
        content.addView(ipv6Status, matchWidth());
        ipv6.setOnCheckedChangeListener((button, checked) -> ipv6Status.setText(
                checked
                        ? context.getString(R.string.override_ipv6_pending_environment)
                        : context.getString(R.string.override_ipv6_disabled)
        ));

        Switch adaptiveTcpConcurrent = switchView(context, currentAdaptiveTcpConcurrent);
        TextView adaptiveSummary = summary(
                context,
                context.getString(R.string.override_adaptive_tcp_concurrent_summary)
        );
        content.addView(helpSwitchRow(
                context,
                R.string.override_adaptive_tcp_concurrent,
                adaptiveTcpConcurrent,
                adaptiveSummary
        ), topSpaced(context));
        content.addView(adaptiveSummary, matchWidth());

        Switch webViewXhttp = switchView(context, currentWebViewXhttp);
        TextView webViewSummary = summary(
                context,
                context.getString(R.string.override_webview_xhttp_summary)
        );
        content.addView(helpSwitchRow(
                context,
                R.string.override_webview_xhttp,
                webViewXhttp,
                webViewSummary
        ), topSpaced(context));
        content.addView(webViewSummary, matchWidth());

        Switch lanWebUi = switchView(context, currentLanWebUiPublic);
        content.addView(switchRow(
                context,
                R.string.override_lan_webui_public,
                lanWebUi
        ), topSpaced(context));
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
        AlertDialog panel = new AlertDialog.Builder(context)
                .setTitle(R.string.runtime_overrides)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, (dialog, which) -> {
                    int processModeIndex = processMode.getSelectedItemPosition();
                    int logLevelIndex = logLevel.getSelectedItemPosition();
                    listener.onOverridesSelected(new RuntimeOverrideSettings(
                            TunStackMode.SYSTEM,
                            processModeIndex >= 0 && processModeIndex < processModes.length
                                    ? processModes[processModeIndex]
                                    : ProcessMatchingMode.ALWAYS,
                            ipv6.isChecked(),
                            logLevelIndex >= 0 && logLevelIndex < logLevels.length
                                    ? logLevels[logLevelIndex]
                                    : RuntimeLogLevel.WARNING,
                            adaptiveTcpConcurrent.isChecked(),
                            webViewXhttp.isChecked(),
                            lanWebUi.isChecked()
                    ));
                })
                .create();
        panel.getWindow().setBackgroundDrawableResource(R.drawable.popup_menu_background);
        panel.show();
    }

    private static TextView sectionTitle(Context context, int text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static LinearLayout helpSpinnerRow(
            Context context,
            int title,
            TextView helpText,
            Spinner spinner
    ) {
        LinearLayout row = horizontalRow(context);
        row.addView(sectionTitle(context, title), weightedWidth());
        row.addView(helpButton(context, helpText), helpButtonSize(context));
        row.addView(spinner, spinnerWidth(context));
        return row;
    }

    private static LinearLayout helpSwitchRow(
            Context context,
            int title,
            Switch control,
            TextView helpText
    ) {
        LinearLayout row = horizontalRow(context);
        row.addView(sectionTitle(context, title), weightedWidth());
        row.addView(helpButton(context, helpText), helpButtonSize(context));
        row.addView(control, switchWidth(context));
        return row;
    }

    private static LinearLayout switchRow(
            Context context,
            int title,
            Switch control
    ) {
        LinearLayout row = horizontalRow(context);
        row.addView(sectionTitle(context, title), weightedWidth());
        row.addView(control, switchWidth(context));
        return row;
    }

    private static LinearLayout horizontalRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private static TextView helpButton(Context context, TextView helpText) {
        TextView button = new TextView(context);
        button.setText("❓");
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setContentDescription(context.getString(R.string.override_show_help));
        helpText.setVisibility(View.GONE);
        button.setOnClickListener(ignored -> {
            boolean show = helpText.getVisibility() != View.VISIBLE;
            helpText.setVisibility(show ? View.VISIBLE : View.GONE);
            button.setContentDescription(context.getString(show
                    ? R.string.override_hide_help
                    : R.string.override_show_help));
        });
        return button;
    }

    private static Switch switchView(Context context, boolean checked) {
        Switch view = new Switch(context);
        view.setChecked(checked);
        view.setMinHeight(dp(context, 40));
        return view;
    }

    private static TextView summary(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13);
        view.setAlpha(0.72f);
        view.setPadding(0, 0, 0, dp(context, 2));
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

    private static int indexOf(ProcessMatchingMode[] values, ProcessMatchingMode target) {
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

    private static LinearLayout.LayoutParams weightedWidth() {
        return new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        );
    }

    private static LinearLayout.LayoutParams helpButtonSize(Context context) {
        return new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36));
    }

    private static LinearLayout.LayoutParams switchWidth(Context context) {
        return new LinearLayout.LayoutParams(dp(context, 52), dp(context, 40));
    }

    private static LinearLayout.LayoutParams spinnerWidth(Context context) {
        return new LinearLayout.LayoutParams(dp(context, 120), dp(context, 40));
    }

    private static LinearLayout.LayoutParams topSpaced(Context context) {
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(context, 8);
        return params;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
