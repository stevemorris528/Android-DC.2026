package com.phinmadvader.andcpp;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatMessage implements Serializable {
    public final String sender;
    public final String message;
    public final long timestamp;
    public final boolean isIncoming;

    public ChatMessage(String sender, String message, boolean isIncoming) {
        this.sender = sender;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
        this.isIncoming = isIncoming;
    }

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}
