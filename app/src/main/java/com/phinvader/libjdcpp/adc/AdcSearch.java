package com.phinvader.libjdcpp.adc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An ADC search (BSCH / DSCH / FSCH) from another user, and how it maps onto our share search.
 * <pre>
 *   AN term (all must match)   NO term (must not)   EX extension   TR TTH
 *   GE / LE / EQ size          TY 1 file, 2 directory              TO token (echoed in results)
 * </pre>
 * Pure Java, so it can be unit-tested on the JVM.
 */
public final class AdcSearch {
    public final String fromSid;
    public final String token;
    public final List<String> include = new ArrayList<>();
    public final List<String> exclude = new ArrayList<>();
    public final List<String> extensions = new ArrayList<>();
    public final String tth;
    public final long minSize;   // -1 = none
    public final long maxSize;   // -1 = none
    /** 0 any, 1 files only, 2 directories only. */
    public final int type;

    private AdcSearch(String fromSid, String token, String tth, long minSize, long maxSize, int type) {
        this.fromSid = fromSid;
        this.token = token;
        this.tth = tth;
        this.minSize = minSize;
        this.maxSize = maxSize;
        this.type = type;
    }

    /** Parses a BSCH / DSCH / FSCH. Null if it isn't a search we can answer. */
    public static AdcSearch parse(AdcCommand c) {
        if (c == null || !"SCH".equals(c.cmd) || c.fromSid == null) return null;
        long ge = -1, le = -1;
        String eq = c.named("EQ", 0);
        if (eq != null) {
            ge = parseLong(eq);
            le = ge;
        } else {
            String g = c.named("GE", 0);
            String l = c.named("LE", 0);
            if (g != null) ge = parseLong(g);
            if (l != null) le = parseLong(l);
        }
        String ty = c.named("TY", 0);
        int type = "1".equals(ty) ? 1 : "2".equals(ty) ? 2 : 0;
        String tr = c.named("TR", 0);
        AdcSearch s = new AdcSearch(c.fromSid, c.named("TO", 0), tr != null ? tr.trim().toUpperCase(Locale.ROOT) : null,
                ge, le, type);
        for (String a : c.namedAll("AN", 0)) if (!a.trim().isEmpty()) s.include.add(a.trim());
        for (String n : c.namedAll("NO", 0)) if (!n.trim().isEmpty()) s.exclude.add(n.trim().toLowerCase(Locale.ROOT));
        for (String e : c.namedAll("EX", 0)) {
            String x = e.trim().toLowerCase(Locale.ROOT);
            if (x.startsWith(".")) x = x.substring(1);
            if (!x.isEmpty()) s.extensions.add(x);
        }
        if (s.tth == null && s.include.isEmpty()) return null; // nothing to look for
        return s;
    }

    /** NMDC-style type for UploadManager.searchShare: 9 TTH, 8 folder, 1 any. */
    public int nmdcType() {
        if (tth != null) return 9;
        return type == 2 ? 8 : 1;
    }

    /** The pattern searchShare takes: "TTH:..." or the terms joined with '$'. */
    public String nmdcPattern() {
        if (tth != null) return "TTH:" + tth;
        StringBuilder sb = new StringBuilder();
        for (String t : include) {
            if (sb.length() > 0) sb.append('$');
            sb.append(t);
        }
        return sb.toString();
    }

    /** True if a hit from the share search also passes the ADC-only filters (NO, EX, both size bounds, TY). */
    public boolean accepts(String path, long size, boolean dir) {
        if (tth != null) return !dir;
        if (type == 1 && dir) return false;
        if (type == 2 && !dir) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String n : exclude) if (lower.contains(n)) return false;
        if (!dir) {
            if (minSize >= 0 && size < minSize) return false;
            if (maxSize >= 0 && size > maxSize) return false;
            if (!extensions.isEmpty()) {
                int dot = lower.lastIndexOf('.');
                String ext = dot >= 0 ? lower.substring(dot + 1) : "";
                if (!extensions.contains(ext)) return false;
            }
        }
        return true;
    }

    /** ADC FN for one of our share paths ("Share\dir\file" → "/Share/dir/file", directories end with '/'). */
    public static String adcPath(String sharePath, boolean dir) {
        String p = sharePath.replace('\\', '/');
        if (!p.startsWith("/")) p = "/" + p;
        if (dir && !p.endsWith("/")) p += "/";
        return p;
    }

    /** A result's FN as the NMDC-style path the Search tab and downloads use ("/a/b/" → "a\b"). */
    public static String nmdcPath(String fn) {
        String p = fn;
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.replace('/', '\\');
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
