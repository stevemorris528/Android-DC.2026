package com.phinvader.libjdcpp.hub;

/** One of our shared files or directories, answering someone's search. Protocol-neutral. */
public final class SearchResult {
    /** Share path as NMDC writes it (backslash separated). */
    public final String path;
    public final long size;
    /** Base32 TTH, or null for a directory. */
    public final String tth;
    public final boolean dir;
    public final int freeSlots;
    public final int totalSlots;

    public SearchResult(String path, long size, String tth, boolean dir, int freeSlots, int totalSlots) {
        this.path = path;
        this.size = size;
        this.tth = tth;
        this.dir = dir;
        this.freeSlots = freeSlots;
        this.totalSlots = totalSlots;
    }
}
