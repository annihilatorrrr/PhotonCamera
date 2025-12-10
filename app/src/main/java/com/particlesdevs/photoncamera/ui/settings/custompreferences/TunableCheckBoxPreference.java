package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Log;

/**
 * Checkbox preference for tunable parameters with min=0, max=1, step=1.
 * Stores values as int (0 or 1) to match the tunable system's numeric storage pattern.
 */
public class TunableCheckBoxPreference extends SwitchPreferenceCompat {
    private static final String TAG = "TunableCheckBoxPref";
    private int mDefaultValue = 0;

    public TunableCheckBoxPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.preference_tunable_checkbox); // Use custom layout with reduced margin
        setIconSpaceReserved(false); // Don't reserve icon space
    }

    public TunableCheckBoxPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_tunable_checkbox); // Use custom layout with reduced margin
        setIconSpaceReserved(false); // Don't reserve icon space
    }

    public TunableCheckBoxPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutResource(R.layout.preference_tunable_checkbox); // Use custom layout with reduced margin
        setIconSpaceReserved(false); // Don't reserve icon space
    }

    /**
     * Set the default value (0 or 1)
     */
    public void setDefaultValue(int defaultValue) {
        this.mDefaultValue = defaultValue;
        // Also set the parent's boolean default value
        super.setDefaultValue(defaultValue != 0);
        Log.d(TAG, "Set default value: " + defaultValue + " (boolean: " + (defaultValue != 0) + ")");
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        // Ensure checkbox state is correct based on persisted int value
        int currentValue = getPersistedInt(mDefaultValue);
        setChecked(currentValue != 0);
        Log.d(TAG, "onBindViewHolder - set checked: " + (currentValue != 0) + " (value: " + currentValue + ", default: " + mDefaultValue + ")");
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        // Check if value already persisted
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        boolean hasPersisted = prefs != null && prefs.contains(getKey());
        
        int currentValue;
        if (!hasPersisted) {
            // First time - use mDefaultValue if set, otherwise convert defaultValue parameter
            if (defaultValue != null) {
                // defaultValue might be Boolean from parent class
                if (defaultValue instanceof Boolean) {
                    currentValue = ((Boolean) defaultValue) ? 1 : 0;
                } else if (defaultValue instanceof Integer) {
                    currentValue = (Integer) defaultValue;
                } else {
                    currentValue = mDefaultValue;
                }
            } else {
                currentValue = mDefaultValue;
            }
            // Persist the default value
            persistInt(currentValue);
            Log.d(TAG, "First init - persisted exact default: " + currentValue + " (from mDefaultValue: " + mDefaultValue + ")");
        } else {
            // Load existing persisted value
            currentValue = getPersistedInt(mDefaultValue);
            Log.d(TAG, "Loading persisted: " + currentValue);
        }
        
        // Update UI without re-persisting
        setChecked(currentValue != 0);
        Log.d(TAG, "onSetInitialValue - set checked: " + (currentValue != 0) + " (value: " + currentValue + ", default: " + mDefaultValue + ")");
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        // Convert boolean to int (0 or 1) and persist as int
        int intValue = value ? 1 : 0;
        return persistInt(intValue);
    }

    @Override
    protected boolean getPersistedBoolean(boolean defaultReturnValue) {
        // Get persisted int value and convert to boolean
        int intDefault = defaultReturnValue ? 1 : 0;
        int persistedValue = getPersistedInt(intDefault);
        return persistedValue != 0;
    }

    /**
     * Get the current value as int (0 or 1)
     */
    public int getIntValue() {
        return getPersistedInt(mDefaultValue);
    }
}

