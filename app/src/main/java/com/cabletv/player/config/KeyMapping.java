package com.cabletv.player.config;

import android.view.KeyEvent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orhanobut.hawk.Hawk;

public class KeyMapping {
    private static final String KEY_MAPPING_KEY = "app_key_mapping";

    public enum Action {
        CHANNEL_UP,
        CHANNEL_DOWN,
        VOLUME_UP,
        VOLUME_DOWN,
        OK,
        MENU,
        BACK,
        LEFT,
        RIGHT,
        NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9
    }

    private static final int[] DEFAULT_KEYCODES = {
            KeyEvent.KEYCODE_DPAD_UP,      // CHANNEL_UP
            KeyEvent.KEYCODE_DPAD_DOWN,    // CHANNEL_DOWN
            KeyEvent.KEYCODE_VOLUME_UP,    // VOLUME_UP (was DPAD_RIGHT)
            KeyEvent.KEYCODE_VOLUME_DOWN,  // VOLUME_DOWN (was DPAD_LEFT)
            KeyEvent.KEYCODE_DPAD_CENTER,  // OK
            KeyEvent.KEYCODE_MENU,         // MENU
            KeyEvent.KEYCODE_BACK,         // BACK
            KeyEvent.KEYCODE_DPAD_LEFT,    // LEFT (new)
            KeyEvent.KEYCODE_DPAD_RIGHT,   // RIGHT (new)
            KeyEvent.KEYCODE_0,            // NUM_0
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9
    };

    static {
        ensureDefaults();
    }

    private static final String KEY_MAPPING_VERSION_KEY = "app_key_mapping_version";
    private static final int MAPPING_SCHEMA_VERSION = 2;

    private static void ensureDefaults() {
        String mapping = Hawk.get(KEY_MAPPING_KEY, "");
        if (mapping.isEmpty()) {
            resetToDefaults();
        } else if (Hawk.get(KEY_MAPPING_VERSION_KEY, 1) < MAPPING_SCHEMA_VERSION) {
            // Migrate old mapping: update volume and focus keys to new defaults
            JsonObject obj = parseMapping();
            obj.addProperty(Action.VOLUME_UP.name(), DEFAULT_KEYCODES[Action.VOLUME_UP.ordinal()]);
            obj.addProperty(Action.VOLUME_DOWN.name(), DEFAULT_KEYCODES[Action.VOLUME_DOWN.ordinal()]);
            obj.addProperty(Action.LEFT.name(), DEFAULT_KEYCODES[Action.LEFT.ordinal()]);
            obj.addProperty(Action.RIGHT.name(), DEFAULT_KEYCODES[Action.RIGHT.ordinal()]);
            Hawk.put(KEY_MAPPING_KEY, obj.toString());
        }
        Hawk.put(KEY_MAPPING_VERSION_KEY, MAPPING_SCHEMA_VERSION);
    }

    public static void resetToDefaults() {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < Action.values().length && i < DEFAULT_KEYCODES.length; i++) {
            obj.addProperty(Action.values()[i].name(), DEFAULT_KEYCODES[i]);
        }
        Hawk.put(KEY_MAPPING_KEY, obj.toString());
    }

    public static Action resolve(int keyCode) {
        JsonObject obj = parseMapping();
        for (Action action : Action.values()) {
            if (obj.has(action.name()) && obj.get(action.name()).getAsInt() == keyCode) {
                return action;
            }
        }
        return null;
    }

    public static int getKeyCode(Action action) {
        JsonObject obj = parseMapping();
        if (obj.has(action.name())) {
            return obj.get(action.name()).getAsInt();
        }
        return DEFAULT_KEYCODES[action.ordinal()];
    }

    public static void setKeyCode(Action action, int keyCode) {
        JsonObject obj = parseMapping();
        obj.addProperty(action.name(), keyCode);
        Hawk.put(KEY_MAPPING_KEY, obj.toString());
    }

    public static String getMappingJson() {
        return Hawk.get(KEY_MAPPING_KEY, "{}");
    }

    public static void setMappingJson(String json) {
        try {
            JsonParser.parseString(json);
            Hawk.put(KEY_MAPPING_KEY, json);
        } catch (Exception e) {
            // Invalid JSON, keep existing
        }
    }

    private static JsonObject parseMapping() {
        String json = Hawk.get(KEY_MAPPING_KEY, "{}");
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
