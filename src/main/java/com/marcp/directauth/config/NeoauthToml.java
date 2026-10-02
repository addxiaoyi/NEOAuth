package com.marcp.directauth.config;

import com.marcp.directauth.data.MigrationMode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class NeoauthToml {
    private final Map<String, String> values = new LinkedHashMap<>();

    static NeoauthToml read(Path path) {
        NeoauthToml document = new NeoauthToml();
        if (!Files.exists(path)) return document;
        String section = "";
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) continue;
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                document.values.put(section.isEmpty() ? key : section + "." + key, parse(value));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read NEOauth TOML: " + path, exception);
        }
        return document;
    }

    void put(String key, Object value) {
        if (value != null) values.put(key, String.valueOf(value));
    }

    void putSection(String section, Object source) {
        for (Field field : source.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            try {
                Object value = field.get(source);
                if (value instanceof Map<?, ?>) continue;
                put(section + "." + field.getName(), value);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to serialize NEOauth config field " + field.getName(), exception);
            }
        }
    }

    void applySection(String section, Object target) {
        for (Field field : target.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            String raw = values.get(section + "." + field.getName());
            if (raw == null) continue;
            try {
                if (field.getType() == String.class) field.set(target, raw);
                else if (field.getType() == int.class) field.setInt(target, Integer.parseInt(raw));
                else if (field.getType() == long.class) field.setLong(target, Long.parseLong(raw));
                else if (field.getType() == boolean.class) field.setBoolean(target, Boolean.parseBoolean(raw));
            } catch (IllegalAccessException | NumberFormatException exception) {
                throw new IllegalStateException("Invalid NEOauth config value " + section + "." + field.getName(), exception);
            }
        }
    }

    void applyMigrationMap(Map<String, MigrationMode> target) {
        String prefix = "migrationMap.";
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) target.put(entry.getKey().substring(prefix.length()), MigrationMode.valueOf(entry.getValue()));
        }
    }

    void write(Path path) {
        StringBuilder out = new StringBuilder("# NEOauth unified configuration\n# Edit this file, then run /directauth reload.\n\n");
        String current = "";
        for (Map.Entry<String, String> entry : values.entrySet()) {
            int dot = entry.getKey().lastIndexOf('.');
            String section = dot < 0 ? "" : entry.getKey().substring(0, dot);
            String key = dot < 0 ? entry.getKey() : entry.getKey().substring(dot + 1);
            if (!section.equals(current)) {
                if (!section.isEmpty()) out.append('[').append(section).append("]\n");
                current = section;
            }
            out.append(key).append(" = ").append(format(entry.getValue())).append('\n');
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, out, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write NEOauth TOML: " + path, exception);
        }
    }

    private static String parse(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\n", "\n").replace("\\\"", "\"");
        }
        int comment = value.indexOf(" #");
        return comment >= 0 ? value.substring(0, comment).trim() : value;
    }

    private static String format(String value) {
        if (value.equals("true") || value.equals("false") || value.matches("-?\\d+")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
