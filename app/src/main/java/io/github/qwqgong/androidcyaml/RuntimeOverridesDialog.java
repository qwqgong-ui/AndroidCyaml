package io.github.qwqgong.androidcyaml;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

final class RuntimeOverridesDialog {
    interface Listener {
        void onTunStackSelected(TunStackOverride override);
    }

    private static final int CONFIG_INDEX = 0;
    private static final int GVISOR_INDEX = 1;
    private static final int SYSTEM_INDEX = 2;

    private RuntimeOverridesDialog() {}

    static void show(
            Context context,
            TunStackOverride current,
            Listener listener
    ) {
        String[] labels = {
                context.getString(R.string.tun_stack_follow_config),
                context.getString(R.string.tun_stack_gvisor),
                context.getString(R.string.tun_stack_system_unavailable)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_list_item_single_choice,
                labels
        ) {
            @Override
            public boolean isEnabled(int position) {
                return position != SYSTEM_INDEX;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                boolean enabled = isEnabled(position);
                view.setEnabled(enabled);
                view.setAlpha(enabled ? 1.0f : 0.45f);
                return view;
            }
        };

        int[] selected = {
                current == TunStackOverride.GVISOR ? GVISOR_INDEX : CONFIG_INDEX
        };
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.runtime_overrides)
                .setSingleChoiceItems(adapter, selected[0], (ignored, which) -> {
                    if (adapter.isEnabled(which)) {
                        selected[0] = which;
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    TunStackOverride override = selected[0] == GVISOR_INDEX
                            ? TunStackOverride.GVISOR
                            : TunStackOverride.CONFIG;
                    dialog.dismiss();
                    listener.onTunStackSelected(override);
                }));
        dialog.show();
    }
}
