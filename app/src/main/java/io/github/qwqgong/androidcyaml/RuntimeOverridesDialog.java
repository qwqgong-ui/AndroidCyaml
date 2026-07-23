package io.github.qwqgong.androidcyaml;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

final class RuntimeOverridesDialog {
    interface Listener {
        void onOverridesSelected(
                TunStackMode tunStack,
                boolean processMatching,
                boolean ipv6Enabled
        );
    }

    private RuntimeOverridesDialog() {}

    static void show(
            Context context,
            TunStackMode currentStack,
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

        content.addView(sectionTitle(context, R.string.override_tun_stack), matchWidth());
        RadioGroup stackGroup = new RadioGroup(context);
        stackGroup.setOrientation(RadioGroup.VERTICAL);
        Map<Integer, TunStackMode> stackById = new HashMap<>();
        addStackOption(
                context,
                stackGroup,
                stackById,
                TunStackMode.SYSTEM,
                R.string.override_stack_system,
                currentStack == TunStackMode.SYSTEM
        );
        addStackOption(
                context,
                stackGroup,
                stackById,
                TunStackMode.GVISOR,
                R.string.override_stack_gvisor,
                currentStack == TunStackMode.GVISOR
        );
        addStackOption(
                context,
                stackGroup,
                stackById,
                TunStackMode.MIXED,
                R.string.override_stack_mixed,
                currentStack == TunStackMode.MIXED
        );
        content.addView(stackGroup, matchWidth());
        content.addView(summary(
                context,
                context.getString(R.string.override_tun_stack_summary)
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

        new AlertDialog.Builder(context)
                .setTitle(R.string.runtime_overrides)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, (dialog, which) -> {
                    TunStackMode selected = stackById.get(stackGroup.getCheckedRadioButtonId());
                    listener.onOverridesSelected(
                            selected == null ? TunStackMode.SYSTEM : selected,
                            process.isChecked(),
                            ipv6.isChecked()
                    );
                })
                .show();
    }

    private static void addStackOption(
            Context context,
            RadioGroup group,
            Map<Integer, TunStackMode> stackById,
            TunStackMode stack,
            int label,
            boolean checked
    ) {
        RadioButton button = new RadioButton(context);
        int id = View.generateViewId();
        button.setId(id);
        button.setText(label);
        button.setTextSize(15);
        button.setMinHeight(dp(context, 44));
        button.setChecked(checked);
        stackById.put(id, stack);
        group.addView(button, matchWidth());
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
