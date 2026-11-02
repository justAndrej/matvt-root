package io.github.virresh.matvt.gui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.List;

import io.github.virresh.matvt.R;
import io.github.virresh.matvt.helper.CursorAppearance;
import io.github.virresh.matvt.view.CursorDrawable;

// This isn't a generic re-usable adapter
// it should be refactored to become a generic adapter
// in case similar dropdowns are needed in future

public class IconStyleSpinnerAdapter extends ArrayAdapter<String> {
    private List<String> objects;
    private Context context;
    public IconStyleSpinnerAdapter(@NonNull Context context, int resource, int textViewId, @NonNull List<String> objects) {
        super(context, resource, textViewId, objects);
        this.objects = objects;
        this.context = context;
    }

    public static List<String> getResourceList () {
        return CursorAppearance.STYLES;
    }

    @Override
    public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        return getCustomView(position, convertView, parent, true);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        return getCustomView(position, convertView, parent, false);
    }

    @Nullable
    @Override
    public String getItem(int position) {
        return objects.get(position);
    }

    @Override
    public int getPosition(@Nullable String item) {
        return objects.indexOf(CursorAppearance.normalizeStyle(item));
    }

    public View getCustomView(int position, View convertView, ViewGroup parent, boolean setBackcolor) {

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View row = convertView != null ? convertView : inflater.inflate(R.layout.spinner_icon_text_gui, parent, false);
        TextView label = (TextView) row.findViewById(R.id.textView);
        ImageView icon = (ImageView) row.findViewById(R.id.imageView);

        String selection = objects.get(position);

        label.setText(CursorAppearance.LIGHT_STYLE.equals(selection)
                ? R.string.cursor_style_light : R.string.cursor_style_dark);
        icon.setImageDrawable(new CursorDrawable(CursorAppearance.DEFAULT_SIZE_STEP, selection));
        row.setBackground(setBackcolor ? ContextCompat.getDrawable(context, R.drawable.focus_selector) : null);

        return row;
    }
}
