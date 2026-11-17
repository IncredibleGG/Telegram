/*
 * LuminaGram - minimal JSON reader for the AdShield checker (dev tool, not part of the app).
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.messenger.lumina;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Objects -> LinkedHashMap, arrays -> ArrayList, integers -> Long, other numbers -> Double. */
final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing data at " + p.i);
        }
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(String w) {
        if (!s.startsWith(w, i)) {
            throw new IllegalArgumentException("expected " + w + " at " + i);
        }
        i += w.length();
    }

    private Map<String, Object> object() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i) != ':') {
                throw new IllegalArgumentException("expected : at " + i);
            }
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , at " + (i - 1));
            }
        }
    }

    private List<Object> array() {
        ArrayList<Object> a = new ArrayList<Object>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , at " + (i - 1));
            }
        }
    }

    private String string() {
        if (s.charAt(i) != '"') {
            throw new IllegalArgumentException("expected string at " + i);
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    throw new IllegalArgumentException("bad escape at " + i);
            }
        }
    }

    private Object number() {
        int start = i;
        boolean frac = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                frac = true;
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(start, i);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("bad value at " + start);
        }
        return frac ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
    }
}
