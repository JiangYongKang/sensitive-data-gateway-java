package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 字段路径工具。
 *
 * <p>路径语法：点号分隔 Map 键，方括号表示 List 下标；{@code [*]} 为通配，匹配任意下标。
 * 例如 {@code contact.phones[*].number} 可命中 {@code contact.phones[0].number}。
 */
public final class PathPatterns {

    private PathPatterns() {
    }

    /**
     * 归一化路径：去除空白与空段，统一 {@code a.b[0].c} 形式。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        String path = raw.trim();
        List<String> segments = new ArrayList<>();
        int i = 0;
        StringBuilder key = new StringBuilder();
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                flushKey(segments, key);
                i++;
            } else if (c == '[') {
                String pending = key.toString().trim();
                key.setLength(0);
                int end = path.indexOf(']', i);
                if (end < 0) {
                    throw new IllegalArgumentException("非法路径，缺少 ]: " + raw);
                }
                String index = path.substring(i + 1, end).trim();
                if (!(index.equals("*") || index.matches("\\d+"))) {
                    throw new IllegalArgumentException("非法数组下标: " + index);
                }
                // 形如 contacts[0]：键与下标在同一段；[0] 直接跟在点分键后
                String bracket = "[" + index + "]";
                segments.add(pending.isEmpty() ? bracket : pending + bracket);
                i = end + 1;
            } else {
                key.append(c);
                i++;
            }
        }
        flushKey(segments, key);
        return String.join(".", segments);
    }

    private static void flushKey(List<String> segments, StringBuilder key) {
        if (!key.isEmpty()) {
            segments.add(key.toString().trim());
            key.setLength(0);
        }
    }

    /**
     * 判断具体路径（含数字下标）是否匹配模式（可能含 {@code [*]}）。
     */
    public static boolean matches(String pattern, String concretePath) {
        String[] p = split(pattern);
        String[] c = split(concretePath);
        if (p.length != c.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (p[i].equals("[*]")) {
                if (!c[i].startsWith("[") || !c[i].endsWith("]")) {
                    return false;
                }
            } else if (!p[i].equals(c[i])) {
                return false;
            }
        }
        return true;
    }

    private static String[] split(String normalized) {
        List<String> out = new ArrayList<>();
        for (String seg : normalized.split(Pattern.quote("."), -1)) {
            // 形如 contacts[0][1]：拆成 contacts / [0] / [1]
            int i = 0;
            int keyStart = 0;
            while (i < seg.length()) {
                if (seg.charAt(i) == '[') {
                    if (i > keyStart) {
                        out.add(seg.substring(keyStart, i));
                    }
                    int end = seg.indexOf(']', i);
                    out.add(seg.substring(i, end + 1));
                    i = end + 1;
                    keyStart = i;
                } else {
                    i++;
                }
            }
            if (keyStart < seg.length()) {
                out.add(seg.substring(keyStart));
            }
        }
        return out.toArray(new String[0]);
    }
}
