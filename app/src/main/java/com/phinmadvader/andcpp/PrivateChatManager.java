package com.phinmadvader.andcpp;

import java.util.ArrayList;
import java.util.List;

public class PrivateChatManager {
    private MainActivity mainActivity;
    public List<PrivateChatEntry> chat_stack;

    public static class PrivateChatEntry {
        public String hubIp;
        public String nick;

        public PrivateChatEntry(String hubIp, String nick) {
            this.hubIp = hubIp;
            this.nick = nick;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PrivateChatEntry)) return false;
            PrivateChatEntry that = (PrivateChatEntry) o;
            String h1 = (hubIp != null) ? hubIp.toLowerCase() : "";
            String h2 = (that.hubIp != null) ? that.hubIp.toLowerCase() : "";
            String n1 = (nick != null) ? nick.toLowerCase() : "";
            String n2 = (that.nick != null) ? that.nick.toLowerCase() : "";
            return h1.equals(h2) && n1.equals(n2);
        }

        @Override
        public int hashCode() {
            String h1 = (hubIp != null) ? hubIp.toLowerCase() : "";
            String n1 = (nick != null) ? nick.toLowerCase() : "";
            return h1.hashCode() ^ n1.hashCode();
        }
    }

    public PrivateChatManager(MainActivity activity) {
        mainActivity = activity;
        chat_stack = new ArrayList<>();
    }

    /**
     * An incoming PM: make sure its chat tab exists, without switching to it or reordering tabs (that pulled
     * the user away from whatever they were doing, on every hub bot's welcome PM). True if a tab was added.
     */
    public boolean addChatQuietly(String hubIp, String nick) {
        if (nick == null || nick.trim().isEmpty()) return false;
        PrivateChatEntry entry = new PrivateChatEntry(hubIp, nick);
        if (chat_stack.contains(entry)) return false;
        chat_stack.add(entry);
        if (mainActivity != null && mainActivity.tab_page_adapter != null) {
            mainActivity.tab_page_adapter.notifyDataSetChanged();
        }
        return true;
    }

    public void openChat(String hubIp, String nick) {
        if (nick == null || nick.trim().isEmpty()) return;
        PrivateChatEntry entry = new PrivateChatEntry(hubIp, nick);
        // If already exists in stack, remove old instance so it moves to end (most recent tab)
        chat_stack.remove(entry);
        chat_stack.add(entry);

        if (mainActivity != null && mainActivity.tab_page_adapter != null) {
            mainActivity.tab_page_adapter.notifyDataSetChanged();
            mainActivity.tab_page_adapter.move_to_last_page();
        }
    }

    public void closeChat(int index) {
        if (index >= 0 && index < chat_stack.size()) {
            chat_stack.remove(index);
            if (mainActivity != null && mainActivity.tab_page_adapter != null) {
                mainActivity.tab_page_adapter.notifyDataSetChanged();
            }
        }
    }

    public int getStackSize() {
        return chat_stack.size();
    }

    public PrivateChatEntry getEntry(int index) {
        if (index >= 0 && index < chat_stack.size()) {
            return chat_stack.get(index);
        }
        return null;
    }
}
