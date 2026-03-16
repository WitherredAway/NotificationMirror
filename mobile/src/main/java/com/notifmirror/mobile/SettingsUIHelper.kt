package com.notifmirror.mobile

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Shared utility functions for settings UI patterns used by both
 * AppSettingsActivity (global) and PerAppSettingsActivity (per-app).
 */
object SettingsUIHelper {

    /**
     * Enable or disable all children of a RadioGroup.
     */
    fun setRadioGroupEnabled(group: RadioGroup, enabled: Boolean) {
        for (i in 0 until group.childCount) {
            group.getChildAt(i).isEnabled = enabled
        }
    }

    /**
     * Creates a TextWatcher that calls the given callback on any text change.
     */
    fun onTextChanged(callback: () -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { callback() }
        }
    }

    /**
     * Wire up a list of SwitchMaterial controls to show the save button when toggled.
     */
    fun wireSwitchesToShowSave(switches: List<SwitchMaterial>, showSave: () -> Unit) {
        for (sw in switches) {
            sw.setOnCheckedChangeListener { _, _ -> showSave() }
        }
    }

    /**
     * Wire up a list of RadioGroups to show the save button when changed.
     */
    fun wireRadioGroupsToShowSave(groups: List<RadioGroup>, showSave: () -> Unit) {
        for (group in groups) {
            group.setOnCheckedChangeListener { _, _ -> showSave() }
        }
    }

    /**
     * Wire up a list of EditTexts to show the save button when text changes.
     */
    fun wireEditTextsToShowSave(inputs: List<EditText>, showSave: () -> Unit) {
        val watcher = onTextChanged(showSave)
        for (input in inputs) {
            input.addTextChangedListener(watcher)
        }
    }

    /**
     * Wire up a per-app override checkbox: when checked, enables the value control
     * and shows the save button. When unchecked, disables the control and shows save.
     */
    fun wireOverrideCheckbox(checkbox: CheckBox, control: View, showSave: () -> Unit) {
        checkbox.setOnCheckedChangeListener { _, checked ->
            control.isEnabled = checked
            showSave()
        }
    }

    /**
     * Wire up a per-app override checkbox that controls a RadioGroup.
     */
    fun wireOverrideCheckboxForRadioGroup(checkbox: CheckBox, group: RadioGroup, showSave: () -> Unit) {
        checkbox.setOnCheckedChangeListener { _, checked ->
            setRadioGroupEnabled(group, checked)
            showSave()
        }
    }
}
