package com.phinmadvader.andcpp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class ChatMessagesAdapter extends ArrayAdapter<ChatMessage> {
    private static final int TYPE_INCOMING = 0;
    private static final int TYPE_OUTGOING = 1;

    private final Context context;
    private final LayoutInflater inflater;
    private final int accentColor;

    public ChatMessagesAdapter(Context context, List<ChatMessage> messages) {
        super(context, 0, messages);
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.accentColor = getThemeAccentColor();
    }

    private int getThemeAccentColor() {
        String accent = ThemeUtils.getColorAccent(context);
        if (ThemeUtils.COLOR_BLUE.equals(accent)) return Color.parseColor("#1976D2");
        if (ThemeUtils.COLOR_ORANGE.equals(accent)) return Color.parseColor("#E65100");
        if (ThemeUtils.COLOR_PURPLE.equals(accent)) return Color.parseColor("#512DA8");
        if (ThemeUtils.COLOR_GREEN.equals(accent)) return Color.parseColor("#2E7D32");
        return Color.parseColor("#00796B"); // Default teal
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage msg = getItem(position);
        if (msg != null && !msg.isIncoming) {
            return TYPE_OUTGOING;
        }
        return TYPE_INCOMING;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ChatMessage msg = getItem(position);
        int viewType = getItemViewType(position);

        if (convertView == null) {
            if (viewType == TYPE_OUTGOING) {
                convertView = inflater.inflate(R.layout.chat_item_outgoing, parent, false);
            } else {
                convertView = inflater.inflate(R.layout.chat_item_incoming, parent, false);
            }
        }

        TextView messageText = (TextView) convertView.findViewById(R.id.chat_message_text);
        TextView messageTime = (TextView) convertView.findViewById(R.id.chat_message_time);

        if (msg != null) {
            if (messageText != null) {
                messageText.setText(msg.message);
            }
            if (messageTime != null) {
                messageTime.setText(msg.getFormattedTime());
            }

            if (viewType == TYPE_OUTGOING) {
                View bubble = convertView.findViewById(R.id.bubble_outgoing_layout);
                if (bubble != null) {
                    GradientDrawable gd = new GradientDrawable();
                    float density = context.getResources().getDisplayMetrics().density;
                    float r14 = 14 * density;
                    float r4 = 4 * density;
                    // top-left, top-right, bottom-right, bottom-left (pairs of x, y)
                    gd.setCornerRadii(new float[]{r14, r14, r4, r4, r14, r14, r14, r14});
                    gd.setColor(accentColor);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                        bubble.setBackground(gd);
                    } else {
                        bubble.setBackgroundDrawable(gd);
                    }
                }
            }
        }

        return convertView;
    }
}
