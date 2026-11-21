package com.phinvader.libjdcpp.adc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One ADC message (ADC 1.0 base spec): a type letter, a 3-letter command, a header that depends on the type,
 * then parameters. Named parameters are a 2-character code followed by the value ({@code NIbob}).
 * <pre>
 *   B  broadcast         BINF &lt;mySID&gt; ...         D, E  direct / echo   DMSG &lt;fromSID&gt; &lt;toSID&gt; ...
 *   F  feature broadcast FSCH &lt;mySID&gt; +TCP4 ...   H  client to hub      HSUP ADBASE ...
 *   I  hub to client     ISID AAAB              C  client to client  CINF ...
 *   U  UDP               URES &lt;myCID&gt; ...
 * </pre>
 * Values use ADC escaping: {@code \s} space, {@code \n} newline, {@code \\} backslash. This is not the
 * NMDC / $ADCGET escaping in DCMessage.escapeAdc.
 * Pure Java, so it can be unit-tested on the JVM.
 */
public final class AdcCommand {

    public final char type;
    /** The three-letter command, e.g. "INF". */
    public final String cmd;
    /** B, D, E, F: sender SID. */
    public final String fromSid;
    /** D, E: recipient SID. */
    public final String toSid;
    /** F: feature selector, e.g. "+TCP4-NAT0". U: the sender's CID. */
    public final String extra;
    /** Parameters after the header, unescaped. */
    public final List<String> params;

    private AdcCommand(char type, String cmd, String fromSid, String toSid, String extra, List<String> params) {
        this.type = type;
        this.cmd = cmd;
        this.fromSid = fromSid;
        this.toSid = toSid;
        this.extra = extra;
        this.params = params;
    }

    // ---- building ----

    /** Starts a command; add header and parameters with the builder methods, then {@link Builder#build()}. */
    public static Builder of(char type, String cmd) {
        return new Builder(type, cmd);
    }

    public static final class Builder {
        private final char type;
        private final String cmd;
        private String from, to, extra;
        private final List<String> params = new ArrayList<>();

        Builder(char type, String cmd) {
            if (cmd == null || cmd.length() != 3) throw new IllegalArgumentException("ADC command must be 3 letters");
            this.type = type;
            this.cmd = cmd;
        }

        public Builder from(String sid) {
            this.from = sid;
            return this;
        }

        public Builder to(String sid) {
            this.to = sid;
            return this;
        }

        /** F: the feature selector; U: our CID. */
        public Builder extra(String s) {
            this.extra = s;
            return this;
        }

        /** A positional parameter (escaped on output). */
        public Builder param(String value) {
            params.add(value);
            return this;
        }

        /** A named parameter: code + value, e.g. named("NI", "bob"). Skipped when value is null. */
        public Builder named(String code, String value) {
            if (value != null) params.add(code + value);
            return this;
        }

        public AdcCommand build() {
            return new AdcCommand(type, cmd, from, to, extra, Collections.unmodifiableList(new ArrayList<>(params)));
        }
    }

    // ---- reading ----

    /**
     * Parses one line (without the trailing newline). Throws IllegalArgumentException for something that
     * isn't an ADC command (callers log and skip it).
     */
    public static AdcCommand parse(String line) {
        if (line == null || line.length() < 4) throw new IllegalArgumentException("Too short: " + line);
        char type = line.charAt(0);
        String cmd = line.substring(1, 4);
        if (line.length() > 4 && line.charAt(4) != ' ') throw new IllegalArgumentException("Bad command: " + line);
        String[] tokens = line.length() > 5 ? line.substring(5).split(" ", -1) : new String[0];
        int i = 0;
        String from = null, to = null, extra = null;
        switch (type) {
            case 'B':
                from = token(tokens, i++, line);
                break;
            case 'D':
            case 'E':
                from = token(tokens, i++, line);
                to = token(tokens, i++, line);
                break;
            case 'F':
                from = token(tokens, i++, line);
                extra = token(tokens, i++, line);
                break;
            case 'U':
                extra = token(tokens, i++, line);
                break;
            case 'C':
            case 'H':
            case 'I':
                break;
            default:
                throw new IllegalArgumentException("Unknown message type '" + type + "': " + line);
        }
        List<String> params = new ArrayList<>();
        for (; i < tokens.length; i++) {
            if (tokens[i].isEmpty()) continue; // tolerate doubled spaces
            params.add(unescape(tokens[i]));
        }
        return new AdcCommand(type, cmd, from, to, extra, Collections.unmodifiableList(params));
    }

    private static String token(String[] tokens, int i, String line) {
        if (i >= tokens.length || tokens[i].isEmpty()) throw new IllegalArgumentException("Missing header: " + line);
        return tokens[i];
    }

    /** Positional parameter i, or null. */
    public String param(int i) {
        return i < params.size() ? params.get(i) : null;
    }

    /**
     * The value of named parameter {@code code}, looking from parameter index {@code from} on (skip the
     * positional ones, so free text such as a chat message can't be mistaken for a code). Null if absent.
     */
    public String named(String code, int from) {
        for (int i = from; i < params.size(); i++) {
            String p = params.get(i);
            if (p.length() >= 2 && p.startsWith(code)) return p.substring(2);
        }
        return null;
    }

    public String named(String code) {
        return named(code, 0);
    }

    /** All values of a named parameter that may repeat (e.g. AN search terms). */
    public List<String> namedAll(String code, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < params.size(); i++) {
            String p = params.get(i);
            if (p.length() >= 2 && p.startsWith(code)) out.add(p.substring(2));
        }
        return out;
    }

    /** E.g. "BINF". */
    public String name() {
        return type + cmd;
    }

    /** The wire form, escaped, without the trailing newline. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64).append(type).append(cmd);
        if (fromSid != null) sb.append(' ').append(fromSid);
        if (type == 'F' || type == 'U') {
            if (extra != null) sb.append(' ').append(extra);
        }
        if (toSid != null) sb.append(' ').append(toSid);
        for (String p : params) sb.append(' ').append(escape(p));
        return sb.toString();
    }

    // ---- ADC escaping ----

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case ' ':
                    sb.append("\\s");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 's') sb.append(' ');
                else if (n == 'n') sb.append('\n');
                else if (n == '\\') sb.append('\\');
                else sb.append('\\').append(n); // unknown escape: keep as is
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
