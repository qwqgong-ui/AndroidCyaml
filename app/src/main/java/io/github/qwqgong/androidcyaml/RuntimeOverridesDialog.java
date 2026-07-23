package io.github.qwqgong.androidcyaml;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

final class RuntimeOverridesDialog {
    interface Listener {
        void onOverridesSelected(boolean processMatching, boolean ipv6Enabled);
    }

    private RuntimeOverridesDialog() {}

    static void show(
            Context context,
            boolean processMatching,
            boolean ipv6Enabled,
            boolean ipv6Effective,
            Listener listener
    ) {
        int horizontalPadding = dp(context, 24);
        int verticalPadding = dp(context, 12);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        Switch gvisor = switchView(context, R.string.override_force_gvisor, true);
        gvisor.setEnabled(false);
        content.addView(gvisor, matchWidth());
        content.addView(summary(
                context,
                context.getString(R.string.override_force_gvisor_summary)
        ), matchWidth());

        Switch process = switchView(
                context,
                R.string.override_process_matching,
                processMatching
        );
        content.addView(process, topSpaced());
        content.addView(summary(
                context,
                context.getString(R.string.override_process_matching_summary)
        ), matchWidth());

        Switch ipv6 = switchView(context, R.string.override_ipv6, ipv6Enabled);
        content.addView(ipv6, topSpaced());
        TextView ipv6Status = summary(context, ipv6Status(context, ipv6Enabled, ipv6Effective));
        content.addView(ipv6Status, matchWidth());
        ipv6.setOnCheckedChangeListener((button, checked) -> ipv6Status.setText(
                checked
                        ? context.getString(R.string.override_ipv6_pending_environment)
                        : context.getString(R.string.override_ipv6_disabled)
        ));

        new AlertDialog.Builder(context)
                .setTitle(R.string.runtime_overrides)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, (dialog, which) -> listener.onOverridesSelected(
                        process.isChecked(),
                        ipv6.isChecked()
                ))
                .show();
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

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static LinearLayout.LayoutParams topSpaced() {
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = 12;
        return params;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
