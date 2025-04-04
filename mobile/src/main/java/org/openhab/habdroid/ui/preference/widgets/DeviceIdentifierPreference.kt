/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui.preference.widgets

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import org.openhab.habdroid.R
import org.openhab.habdroid.ui.preference.CustomDialogPreference
import org.openhab.habdroid.util.PrefKeys

class DeviceIdentifierPreference(context: Context, attrs: AttributeSet) :
    DialogPreference(context, attrs),
    CustomDialogPreference {
    private var value: String? = null

    init {
        dialogTitle = null
        setPositiveButtonText(android.R.string.ok)
        setNegativeButtonText(android.R.string.cancel)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedString(null) ?: defaultValue as String?
        updateSummary()
    }

    override fun getDialogLayoutResource(): Int = R.layout.pref_dialog_device_identifier

    private fun updateSummary() {
        summary = if (value.isNullOrEmpty()) {
            context.getString(R.string.device_identifier_summary_not_set)
        } else {
            value as String
        }
    }

    fun setValue(value: String = this.value.orEmpty()) {
        if (callChangeListener(value)) {
            if (shouldPersist()) {
                persistString(value)
            }
            this.value = value
            updateSummary()
        }
    }

    override fun createDialog(): DialogFragment = PrefFragment.newInstance(key, title)

    class PrefFragment :
        PreferenceDialogFragmentCompat(),
        TextWatcher {
        private lateinit var editorWrapper: TextInputLayout
        private lateinit var editor: EditText
        private lateinit var voiceButton: MaterialSwitch
        private lateinit var backgroundTasksButton: MaterialSwitch

        override fun onCreateDialogView(context: Context): View {
            val inflater = LayoutInflater.from(activity)
            val v = inflater.inflate(R.layout.pref_dialog_device_identifier, null)

            editorWrapper = v.findViewById(R.id.input_wrapper)
            editor = v.findViewById(android.R.id.edit)
            voiceButton = v.findViewById(R.id.voice_switch)
            backgroundTasksButton = v.findViewById(R.id.background_tasks_switch)

            editor.addTextChangedListener(this)
            editor.inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            arguments?.getCharSequence(KEY_TITLE)?.let { title ->
                editorWrapper.hint = title
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                editor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }

            val prefs = preference.sharedPreferences!!
            editor.setText((preference as DeviceIdentifierPreference).value)
            editor.setSelection(editor.text.length)
            voiceButton.isChecked = prefs.getBoolean(PrefKeys.DEV_ID_PREFIX_VOICE, false)
            backgroundTasksButton.isChecked = prefs.getBoolean(PrefKeys.DEV_ID_PREFIX_BG_TASKS, true)

            return v
        }

        override fun onDialogClosed(positiveResult: Boolean) {
            if (positiveResult) {
                val prefs = preference.sharedPreferences!!
                prefs.edit {
                    val pref = preference as DeviceIdentifierPreference
                    pref.setValue(editor.text.toString())
                    putBoolean(PrefKeys.DEV_ID_PREFIX_VOICE, voiceButton.isChecked)
                    putBoolean(PrefKeys.DEV_ID_PREFIX_BG_TASKS, backgroundTasksButton.isChecked)
                }
            }
        }

        override fun onStart() {
            super.onStart()
            updateOkButtonState()
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            // no-op
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            // no-op
        }

        override fun afterTextChanged(s: Editable) {
            val value = s.toString()
            if (value.contains(" ") || value.contains("\n")) {
                editorWrapper.error = context?.getString(R.string.error_no_valid_item_name)
            } else {
                editorWrapper.error = null
            }
            updateOkButtonState()
        }

        private fun updateOkButtonState() {
            val dialog = this.dialog
            if (dialog is AlertDialog) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                    !editor.isEnabled ||
                    editorWrapper.error == null
            }
        }

        companion object {
            private const val KEY_TITLE = "title"

            fun newInstance(key: String, title: CharSequence?): PrefFragment {
                val f = PrefFragment()
                f.arguments = bundleOf(ARG_KEY to key, KEY_TITLE to title)
                return f
            }
        }
    }
}
