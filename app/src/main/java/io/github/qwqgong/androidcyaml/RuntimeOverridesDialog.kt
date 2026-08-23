package io.github.qwqgong.androidcyaml

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
object RuntimeOverridesDialog {
    fun interface Listener {
        fun onOverridesSelected(settings: RuntimeOverrideSettings)
    }

    fun show(
        context: Context,
        currentProcessMatchingMode: ProcessMatchingMode?,
        ipv6Enabled: Boolean,
        ipv6Effective: Boolean,
        currentLogLevel: RuntimeLogLevel?,
        currentAdaptiveTcpConcurrent: Boolean,
        currentWebViewXhttp: Boolean,
        currentLanWebUiPublic: Boolean,
        controllerPort: Int,
        listener: Listener,
    ) {
        val horizontalPadding = dp(context, 16)
        val verticalPadding = dp(context, 4)
        val content = LinearLayout(context)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

        val logSummary = summary(context, context.getString(R.string.override_log_level_summary))
        val logLevels = RuntimeLogLevel.values()
        val logLabels = Array(logLevels.size) { index -> logLevelLabel(context, logLevels[index]) }
        val logLevel = Spinner(context)
        val logAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            logLabels,
        )
        logAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        logLevel.adapter = logAdapter
        logLevel.setSelection(indexOf(logLevels, currentLogLevel ?: RuntimeLogLevel.WARNING))
        logLevel.minimumHeight = dp(context, 40)
        content.addView(
            helpSpinnerRow(context, R.string.override_log_level, logSummary, logLevel),
            matchWidth(),
        )
        content.addView(logSummary, matchWidth())

        val processSummary = summary(
            context,
            context.getString(R.string.override_process_matching_summary),
        )
        val processModes = arrayOf(
            ProcessMatchingMode.STRICT,
            ProcessMatchingMode.ALWAYS,
            ProcessMatchingMode.OFF,
        )
        val processLabels = arrayOf(
            context.getString(R.string.override_process_strict_compact),
            context.getString(R.string.override_process_always_compact),
            context.getString(R.string.override_process_off_compact),
        )
        val processMode = Spinner(context)
        val processAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            processLabels,
        )
        processAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        processMode.adapter = processAdapter
        processMode.setSelection(
            indexOf(processModes, currentProcessMatchingMode ?: ProcessMatchingMode.ALWAYS),
        )
        processMode.minimumHeight = dp(context, 40)
        content.addView(
            helpSpinnerRow(
                context,
                R.string.override_process_matching,
                processSummary,
                processMode,
            ),
            topSpaced(context),
        )
        content.addView(processSummary, matchWidth())

        val ipv6 = switchView(context, ipv6Enabled)
        content.addView(switchRow(context, R.string.override_ipv6, ipv6), topSpaced(context))
        val ipv6Status = summary(context, ipv6Status(context, ipv6Enabled, ipv6Effective))
        content.addView(ipv6Status, matchWidth())
        ipv6.setOnCheckedChangeListener { _, checked ->
            ipv6Status.text = if (checked) {
                context.getString(R.string.override_ipv6_pending_environment)
            } else {
                context.getString(R.string.override_ipv6_disabled)
            }
        }

        val adaptiveTcpConcurrent = switchView(context, currentAdaptiveTcpConcurrent)
        val adaptiveSummary = summary(
            context,
            context.getString(R.string.override_adaptive_tcp_concurrent_summary),
        )
        content.addView(
            helpSwitchRow(
                context,
                R.string.override_adaptive_tcp_concurrent,
                adaptiveTcpConcurrent,
                adaptiveSummary,
            ),
            topSpaced(context),
        )
        content.addView(adaptiveSummary, matchWidth())

        val webViewXhttp = switchView(context, currentWebViewXhttp)
        val webViewSummary = summary(
            context,
            context.getString(R.string.override_webview_xhttp_summary),
        )
        content.addView(
            helpSwitchRow(
                context,
                R.string.override_webview_xhttp,
                webViewXhttp,
                webViewSummary,
            ),
            topSpaced(context),
        )
        content.addView(webViewSummary, matchWidth())

        val lanWebUi = switchView(context, currentLanWebUiPublic)
        content.addView(
            switchRow(context, R.string.override_lan_webui_public, lanWebUi),
            topSpaced(context),
        )
        val lanWebUiStatus = summary(
            context,
            lanWebUiStatus(context, currentLanWebUiPublic, controllerPort),
        )
        content.addView(lanWebUiStatus, matchWidth())
        lanWebUi.setOnCheckedChangeListener { _, checked ->
            lanWebUiStatus.text = lanWebUiStatus(
                context,
                checked,
                if (checked == currentLanWebUiPublic) controllerPort else 0,
            )
        }

        val scroll = ScrollView(context)
        scroll.addView(content, matchWidth())
        val panel = AlertDialog.Builder(context)
            .setTitle(R.string.runtime_overrides)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ ->
                val processModeIndex = processMode.selectedItemPosition
                val logLevelIndex = logLevel.selectedItemPosition
                listener.onOverridesSelected(
                    RuntimeOverrideSettings(
                        processModes.getOrElse(processModeIndex) { ProcessMatchingMode.ALWAYS },
                        ipv6.isChecked,
                        logLevels.getOrElse(logLevelIndex) { RuntimeLogLevel.WARNING },
                        adaptiveTcpConcurrent.isChecked,
                        webViewXhttp.isChecked,
                        lanWebUi.isChecked,
                    ),
                )
            }
            .create()
        panel.window?.setBackgroundDrawableResource(R.drawable.popup_menu_background)
        panel.show()
    }

    private fun sectionTitle(context: Context, text: Int): TextView {
        val view = TextView(context)
        view.setText(text)
        view.textSize = 16f
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return view
    }

    private fun helpSpinnerRow(
        context: Context,
        title: Int,
        helpText: TextView,
        spinner: Spinner,
    ): LinearLayout {
        val row = horizontalRow(context)
        row.addView(sectionTitle(context, title), weightedWidth())
        row.addView(helpButton(context, helpText), helpButtonSize(context))
        row.addView(spinner, spinnerWidth(context))
        return row
    }

    private fun helpSwitchRow(
        context: Context,
        title: Int,
        control: Switch,
        helpText: TextView,
    ): LinearLayout {
        val row = horizontalRow(context)
        row.addView(sectionTitle(context, title), weightedWidth())
        row.addView(helpButton(context, helpText), helpButtonSize(context))
        row.addView(control, switchWidth(context))
        return row
    }

    private fun switchRow(context: Context, title: Int, control: Switch): LinearLayout {
        val row = horizontalRow(context)
        row.addView(sectionTitle(context, title), weightedWidth())
        row.addView(control, switchWidth(context))
        return row
    }

    private fun horizontalRow(context: Context): LinearLayout {
        val row = LinearLayout(context)
        row.gravity = Gravity.CENTER_VERTICAL
        row.orientation = LinearLayout.HORIZONTAL
        return row
    }

    private fun helpButton(context: Context, helpText: TextView): TextView {
        val button = TextView(context)
        button.text = "❓"
        button.textSize = 16f
        button.gravity = Gravity.CENTER
        button.isClickable = true
        button.isFocusable = true
        button.setBackgroundResource(android.R.drawable.list_selector_background)
        button.contentDescription = context.getString(R.string.override_show_help)
        helpText.visibility = View.GONE
        button.setOnClickListener {
            val show = helpText.visibility != View.VISIBLE
            helpText.visibility = if (show) View.VISIBLE else View.GONE
            button.contentDescription = context.getString(
                if (show) R.string.override_hide_help else R.string.override_show_help,
            )
        }
        return button
    }

    private fun switchView(context: Context, checked: Boolean): Switch {
        val view = Switch(context)
        view.isChecked = checked
        view.minHeight = dp(context, 40)
        return view
    }

    private fun summary(context: Context, text: String): TextView {
        val view = TextView(context)
        view.text = text
        view.textSize = 13f
        view.alpha = 0.72f
        view.setPadding(0, 0, 0, dp(context, 2))
        return view
    }

    private fun ipv6Status(context: Context, ipv6Enabled: Boolean, ipv6Effective: Boolean): String {
        if (!ipv6Enabled) {
            return context.getString(R.string.override_ipv6_disabled)
        }
        return context.getString(
            if (ipv6Effective) {
                R.string.override_ipv6_available
            } else {
                R.string.override_ipv6_auto_disabled
            },
        )
    }

    private fun lanWebUiStatus(
        context: Context,
        lanWebUiPublic: Boolean,
        controllerPort: Int,
    ): String {
        if (lanWebUiPublic) {
            return if (controllerPort > 0) {
                context.getString(R.string.override_lan_webui_public_running, controllerPort)
            } else {
                context.getString(R.string.override_lan_webui_public_pending)
            }
        }
        return if (controllerPort > 0) {
            context.getString(R.string.override_lan_webui_local_running, controllerPort)
        } else {
            context.getString(R.string.override_lan_webui_local_pending)
        }
    }

    private fun logLevelLabel(context: Context, logLevel: RuntimeLogLevel): String =
        context.getString(
            when (logLevel) {
                RuntimeLogLevel.SILENT -> R.string.override_log_silent
                RuntimeLogLevel.ERROR -> R.string.override_log_error
                RuntimeLogLevel.WARNING -> R.string.override_log_warning
                RuntimeLogLevel.INFO -> R.string.override_log_info
                RuntimeLogLevel.DEBUG -> R.string.override_log_debug
            },
        )

    private fun <T> indexOf(values: Array<T>, target: T): Int {
        val index = values.indexOfFirst { it === target }
        return if (index < 0) 0 else index
    }

    private fun matchWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    )

    private fun helpButtonSize(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(context, 36), dp(context, 36))

    private fun switchWidth(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(context, 52), dp(context, 40))

    private fun spinnerWidth(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(context, 120), dp(context, 40))

    private fun topSpaced(context: Context): LinearLayout.LayoutParams {
        val params = matchWidth()
        params.topMargin = dp(context, 8)
        return params
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
