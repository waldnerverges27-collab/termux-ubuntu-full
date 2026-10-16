package com.termux.app.terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;

/** Applies one of the saved accent presets to the premium app chrome and extra keys. */
public final class TermuxPremiumTheme {

    private static final String PREFERENCES = "termux_premium_ui";
    private static final String KEY_PALETTE = "accent_palette";

    private static final Palette[] PALETTES = new Palette[] {
        new Palette("midnight", R.string.premium_theme_midnight, Color.rgb(124, 92, 255), Color.rgb(61, 214, 245)),
        new Palette("ocean", R.string.premium_theme_ocean, Color.rgb(59, 130, 246), Color.rgb(34, 211, 238)),
        new Palette("violet", R.string.premium_theme_violet, Color.rgb(168, 85, 247), Color.rgb(244, 114, 182)),
        new Palette("forest", R.string.premium_theme_forest, Color.rgb(16, 185, 129), Color.rgb(52, 211, 153)),
        new Palette("amber", R.string.premium_theme_amber, Color.rgb(245, 158, 11), Color.rgb(251, 191, 36)),
        new Palette("rose", R.string.premium_theme_rose, Color.rgb(244, 63, 94), Color.rgb(251, 113, 133))
    };

    private TermuxPremiumTheme() {
    }

    public static void apply(TermuxActivity activity) {
        if (activity == null) return;
        Palette palette = getPalette(activity);

        View appBar = activity.findViewById(R.id.premium_appbar);
        if (appBar != null) {
            GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { blend(palette.primary, 0.16f, Color.rgb(23, 23, 35)), Color.rgb(11, 11, 18) });
            background.setStroke(dp(activity, 1), blend(palette.primary, 0.34f, Color.rgb(36, 36, 52)));
            appBar.setBackground(background);
        }

        tint(activity, R.id.premium_sessions_button, palette.secondary);
        tint(activity, R.id.premium_new_session_button, palette.secondary);
        tint(activity, R.id.premium_command_palette_button, palette.secondary);
        tint(activity, R.id.premium_file_explorer_button, palette.secondary);
        tint(activity, R.id.premium_settings_button, palette.secondary);

        ExtraKeysView extraKeysView = activity.getExtraKeysView();
        if (extraKeysView != null && activity.getTermuxTerminalExtraKeys() != null) {
            extraKeysView.setButtonTextColor(ContextCompat.getColor(activity, R.color.premium_muted));
            extraKeysView.setButtonActiveTextColor(palette.secondary);
            extraKeysView.setButtonBackgroundColor(ContextCompat.getColor(activity, R.color.premium_key_background));
            extraKeysView.setButtonActiveBackgroundColor(blend(palette.primary, 0.34f, Color.rgb(43, 36, 85)));
            extraKeysView.reload(activity.getTermuxTerminalExtraKeys().getExtraKeysInfo(), activity.getTerminalToolbarDefaultHeight());
        }
    }

    public static void showPicker(TermuxActivity activity) {
        Palette current = getPalette(activity);
        String[] names = new String[PALETTES.length];
        int selected = 0;
        for (int i = 0; i < PALETTES.length; i++) {
            names[i] = activity.getString(PALETTES[i].label);
            if (PALETTES[i].id.equals(current.id)) selected = i;
        }

        new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
            .setTitle(R.string.premium_theme_picker_title)
            .setSingleChoiceItems(names, selected, (dialog, which) -> {
                if (which >= 0 && which < PALETTES.length) {
                    activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putString(KEY_PALETTE, PALETTES[which].id).apply();
                    apply(activity);
                    activity.updatePremiumSessionHeader();
                    Toast.makeText(activity, R.string.premium_theme_changed, Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    public static RippleDrawable createSessionTabBackground(TermuxActivity activity, boolean active) {
        Palette palette = getPalette(activity);
        int fill = active ? blend(palette.primary, 0.24f, Color.rgb(41, 36, 59)) : Color.rgb(23, 23, 34);
        int stroke = active ? blend(palette.primary, 0.88f, Color.rgb(91, 82, 120)) : Color.rgb(43, 43, 59);
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setColor(fill);
        content.setCornerRadius(dp(activity, 9));
        content.setStroke(dp(activity, 1), stroke);
        return new RippleDrawable(ColorStateList.valueOf(blend(palette.primary, 0.38f, Color.TRANSPARENT)), content, null);
    }

    public static int getRunningColor(Context context) {
        return getPalette(context).secondary;
    }

    private static void tint(TermuxActivity activity, int viewId, int color) {
        ImageButton button = activity.findViewById(viewId);
        if (button != null) button.setColorFilter(color);
    }

    private static Palette getPalette(Context context) {
        String id = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_PALETTE, "midnight");
        for (Palette palette : PALETTES) if (palette.id.equals(id)) return palette;
        return PALETTES[0];
    }

    private static int blend(int foreground, float fraction, int background) {
        float inverse = 1f - fraction;
        int alpha = Math.round(Color.alpha(foreground) * fraction + Color.alpha(background) * inverse);
        int red = Math.round(Color.red(foreground) * fraction + Color.red(background) * inverse);
        int green = Math.round(Color.green(foreground) * fraction + Color.green(background) * inverse);
        int blue = Math.round(Color.blue(foreground) * fraction + Color.blue(background) * inverse);
        return Color.argb(alpha, red, green, blue);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Palette {
        final String id;
        final int label;
        final int primary;
        final int secondary;

        Palette(String id, int label, int primary, int secondary) {
            this.id = id;
            this.label = label;
            this.primary = primary;
            this.secondary = secondary;
        }
    }
}
