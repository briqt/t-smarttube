package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Strict bounded JSON only: no JSON5, duplicate keys, coercion, repair, or source-bearing errors. */
final class BoundedJson {
    private int mValues;

    static JSONObject object(String source) {
        if (source == null || source.length() > 2 * 1024 * 1024) throw TranslationBatch.invalid();
        validateStringSyntax(source);
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setLenient(false);
            Object value = new BoundedJson().read(reader, 0);
            if (!(value instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT) throw TranslationBatch.invalid();
            return (JSONObject) value;
        } catch (IOException | JSONException | RuntimeException ignored) {
            throw TranslationBatch.invalid();
        }
    }

    // Android JsonReader's strict mode does not enforce all JSON string escape/control rules.
    private static void validateStringSyntax(String source) {
        boolean quoted = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                if (c < 0x20) throw TranslationBatch.invalid();
                if (c != '\\') continue;
                if (++i >= source.length()) throw TranslationBatch.invalid();
                char escaped = source.charAt(i);
                if (escaped == 'u') {
                    for (int digit = 0; digit < 4; digit++) {
                        if (++i >= source.length()) throw TranslationBatch.invalid();
                        char hex = source.charAt(i);
                        if (!(hex >= '0' && hex <= '9' || hex >= 'a' && hex <= 'f' || hex >= 'A' && hex <= 'F')) {
                            throw TranslationBatch.invalid();
                        }
                    }
                } else if ("\"\\/bfnrt".indexOf(escaped) < 0) throw TranslationBatch.invalid();
            }
        }
        if (quoted) throw TranslationBatch.invalid();
    }

    private Object read(JsonReader reader, int depth) throws IOException, JSONException {
        if (depth > 32 || ++mValues > 65536) throw TranslationBatch.invalid();
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                Set<String> keys = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!TranslationBatch.validText(key) || !keys.add(key)) throw TranslationBatch.invalid();
                    object.put(key, read(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) array.put(read(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING:
                String text = reader.nextString();
                if (!TranslationBatch.validText(text)) throw TranslationBatch.invalid();
                return text;
            case NUMBER:
                String number = reader.nextString();
                if (number.length() > 64 || !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                    throw TranslationBatch.invalid();
                }
                return new java.math.BigDecimal(number);
            case BOOLEAN:
                return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return JSONObject.NULL;
            default:
                throw TranslationBatch.invalid();
        }
    }

    static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private BoundedJson() { }
}
