package com.bepinex.android.remote;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-process mod side for {@link ModSettingsService} (both in {@code :game}).
 * Panel still binds to the service across processes. C# polls with zero-arg CallStatic and
 * sends replies through {@link #enqueueKv(String)} (raw JNI), which writes Bundle strings directly.
 */
public final class ModSettingsModBridge {
    private static final String TAG = "BugtopiaModSettings";

    private static final ConcurrentLinkedQueue<String> incoming = new ConcurrentLinkedQueue<>();
    /** Packed as whatStr + "\\u0001" + attachStr + "\\u0001" + bundleJson */
    private static final ConcurrentLinkedQueue<String> outgoing = new ConcurrentLinkedQueue<>();
    /** Packed as what + "\\u0001" + attach + "\\u0001" + reqId + "\\u0001" + key + "\\u0001" + value ... */
    private static final ConcurrentLinkedQueue<String> outgoingKv = new ConcurrentLinkedQueue<>();
    private static Messenger replyMessenger;
    private static Messenger serviceMessenger;
    private static boolean attached;

    private ModSettingsModBridge() {}

    public static String ping() {
        return "pong";
    }

    /**
     * Start/bind the :game ModSettingsService, then attach the mod reply Messenger
     * once onServiceConnected fires (avoids racing attach before onCreate).
     */
    public static synchronized String bind(Context context) {
        if (context == null) {
            return "0";
        }
        ensureReplyMessenger();
        if (attached && serviceMessenger != null) {
            return "1";
        }
        try {
            Intent intent = new Intent(context, ModSettingsService.class);
            intent.setAction(ModSettingsProtocol.ACTION_BIND);
            try {
                context.startService(intent);
            } catch (Throwable t) {
                Log.w(TAG, "startService failed", t);
            }
            ensureServiceMessenger(context);
            return "1";
        } catch (Throwable t) {
            Log.w(TAG, "bind failed", t);
            return "0";
        }
    }

    public static synchronized String isReady() {
        return (attached && serviceMessenger != null) ? "1" : "0";
    }

    public static String pollIncomingJson() {
        drainOutgoing();
        drainOutgoingKv();
        String next = incoming.poll();
        return next != null ? next : "";
    }

    /**
     * IL2CPP-safe single-string enqueue. Packed:
     * {@code whatStr + "\u0001" + attachStr + "\u0001" + bundleJson}
     */
    public static String enqueueOutgoing(String packed) {
        if (packed == null || packed.isEmpty()) {
            return "0";
        }
        outgoing.offer(packed);
        int left = drainOutgoing();
        if (serviceMessenger == null) {
            Log.w(TAG, "enqueueOutgoing queued (serviceMessenger null) q=" + outgoing.size());
            return "0";
        }
        if (left > 0) {
            Log.w(TAG, "enqueueOutgoing partial drain left=" + left);
            return "0";
        }
        return "1";
    }

    /**
     * Direct Bundle reply. Packed fields, separated by U+0001 (not present in schema JSON):
     * {@code what + "\u0001" + attach + "\u0001" + reqId + "\u0001" + key + "\u0001" + value ...}.
     * Values are stored with {@link Bundle#putString}. {@code ok} is stored with
     * {@link Bundle#putBoolean} when the value is {@code true} or {@code false}.
     * Returns {@code "1"} only when the message was handed to the service Messenger.
     */
    public static String enqueueKv(String packed) {
        if (packed == null || packed.isEmpty()) {
            return "0";
        }
        outgoingKv.offer(packed);
        int left = drainOutgoingKv();
        if (left > 0) {
            if (serviceMessenger == null) {
                Log.w(TAG, "enqueueKv queued (serviceMessenger null) q=" + left);
            } else {
                Log.w(TAG, "enqueueKv partial drain left=" + left);
            }
            return "0";
        }
        return "1";
    }

    public static String sendJson(String whatStr, String bundleJson, String attachReplyToStr) {
        return enqueueOutgoing(whatStr + "\u0001" + attachReplyToStr + "\u0001" + (bundleJson != null ? bundleJson : "{}"));
    }

    /** @return remaining queued items (0 = fully drained) */
    private static int drainOutgoing() {
        Messenger svc = serviceMessenger;
        if (svc == null) {
            return outgoing.size();
        }
        ensureReplyMessenger();
        String packed;
        while ((packed = outgoing.poll()) != null) {
            try {
                int a = packed.indexOf('\u0001');
                int b = a >= 0 ? packed.indexOf('\u0001', a + 1) : -1;
                if (a < 0 || b < 0) {
                    Log.w(TAG, "bad outgoing pack");
                    continue;
                }
                String whatStr = packed.substring(0, a);
                String attachStr = packed.substring(a + 1, b);
                String bundleJson = packed.substring(b + 1);
                int what = Integer.parseInt(whatStr);
                Message msg = Message.obtain(null, what);
                msg.setData(jsonToBundle(bundleJson));
                if ("1".equals(attachStr)) {
                    msg.replyTo = replyMessenger;
                }
                svc.send(msg);
                Log.i(TAG, "drainOutgoing sent what=" + what + " bytes=" + bundleJson.length());
            } catch (Throwable t) {
                Log.w(TAG, "drainOutgoing failed", t);
                // Re-queue failed pack at head so a later poll can retry.
                ConcurrentLinkedQueue<String> rest = new ConcurrentLinkedQueue<>();
                rest.offer(packed);
                String n;
                while ((n = outgoing.poll()) != null) {
                    rest.offer(n);
                }
                outgoing.addAll(rest);
                break;
            }
        }
        return outgoing.size();
    }

    /** @return remaining queued kv items (0 = fully drained) */
    private static int drainOutgoingKv() {
        Messenger svc = serviceMessenger;
        if (svc == null) {
            return outgoingKv.size();
        }
        ensureReplyMessenger();
        String packed;
        while ((packed = outgoingKv.poll()) != null) {
            int what = -1;
            try {
                String[] parts = splitUnitSep(packed);
                if (parts.length < 3 || ((parts.length - 3) & 1) != 0) {
                    Log.w(TAG, "bad kv pack parts=" + parts.length);
                    continue;
                }
                what = Integer.parseInt(parts[0]);
                Bundle bundle = new Bundle();
                if (!parts[2].isEmpty()) {
                    bundle.putString(ModSettingsProtocol.KEY_REQ_ID, parts[2]);
                }
                int bytes = 0;
                for (int i = 3; i + 1 < parts.length; i += 2) {
                    String key = parts[i];
                    String value = parts[i + 1] != null ? parts[i + 1] : "";
                    if (key == null || key.isEmpty()) {
                        continue;
                    }
                    bytes += value.length();
                    if (ModSettingsProtocol.KEY_OK.equals(key)) {
                        bundle.putBoolean(key, "true".equals(value));
                    } else {
                        bundle.putString(key, value);
                    }
                }
                Message msg = Message.obtain(null, what);
                msg.setData(bundle);
                if ("1".equals(parts[1])) {
                    msg.replyTo = replyMessenger;
                }
                svc.send(msg);
                Log.i(TAG, "drainOutgoing sent what=" + what + " bytes=" + bytes);
            } catch (Throwable t) {
                Log.w(TAG, "drainOutgoing failed what=" + what, t);
                ConcurrentLinkedQueue<String> rest = new ConcurrentLinkedQueue<>();
                rest.offer(packed);
                String next;
                while ((next = outgoingKv.poll()) != null) {
                    rest.offer(next);
                }
                outgoingKv.addAll(rest);
                break;
            }
        }
        return outgoingKv.size();
    }

    /** Split on U+0001 and keep empty fields, including a trailing empty value. */
    private static String[] splitUnitSep(String packed) {
        int count = 1;
        for (int i = 0; i < packed.length(); i++) {
            if (packed.charAt(i) == '\u0001') {
                count++;
            }
        }
        String[] parts = new String[count];
        int index = 0;
        int start = 0;
        for (int i = 0; i < packed.length(); i++) {
            if (packed.charAt(i) == '\u0001') {
                parts[index++] = packed.substring(start, i);
                start = i + 1;
            }
        }
        parts[index] = packed.substring(start);
        return parts;
    }

    private static void ensureServiceMessenger(Context context) {
        if (serviceMessenger != null) {
            return;
        }
        // Same-process bind to cache the service Messenger for send().
        try {
            Intent intent = new Intent(context, ModSettingsService.class);
            intent.setAction(ModSettingsProtocol.ACTION_BIND);
            boolean ok = context.bindService(intent, new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
                    serviceMessenger = new Messenger(service);
                    ModSettingsService.attachModReply(replyMessenger);
                    attached = true;
                    enqueueEvent(ModSettingsProtocol.MSG_HELLO, "HELLO_OK");
                    Log.i(TAG, "in-process service Messenger cached + mod attached");
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName name) {
                    serviceMessenger = null;
                    attached = false;
                    enqueueEvent(-1, "DISCONNECTED");
                }
            }, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "in-process bindService ok=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "in-process bindService failed", t);
        }
    }

    private static void ensureReplyMessenger() {
        if (replyMessenger != null) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper(), msg -> {
            Bundle data = msg.getData();
            if (data != null) {
                data.setClassLoader(ModSettingsModBridge.class.getClassLoader());
            }
            enqueueMessage(msg.what, data);
            return true;
        });
        replyMessenger = new Messenger(handler);
    }

    private static void enqueueEvent(int what, String note) {
        try {
            JSONObject o = new JSONObject();
            o.put("what", what);
            o.put("note", note);
            incoming.offer(o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void enqueueMessage(int what, Bundle data) {
        try {
            JSONObject o = new JSONObject();
            o.put("what", what);
            if (data != null) {
                put(o, data, ModSettingsProtocol.KEY_REQ_ID);
                put(o, data, ModSettingsProtocol.KEY_KEY);
                put(o, data, ModSettingsProtocol.KEY_KIND);
                put(o, data, ModSettingsProtocol.KEY_VALUE_JSON);
                put(o, data, ModSettingsProtocol.KEY_ARGS_JSON);
                put(o, data, ModSettingsProtocol.KEY_LIST_ID);
                put(o, data, ModSettingsProtocol.KEY_KEYS_JSON);
                put(o, data, ModSettingsProtocol.KEY_ERROR);
            }
            incoming.offer(o.toString());
        } catch (Throwable t) {
            Log.w(TAG, "enqueueMessage failed", t);
        }
    }

    private static void put(JSONObject o, Bundle data, String key) throws Exception {
        String v = data.getString(key);
        if (v != null) {
            o.put(key, v);
        }
    }

    private static Bundle jsonToBundle(String json) {
        Bundle b = new Bundle();
        if (json == null || json.isEmpty()) {
            return b;
        }
        try {
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = o.opt(k);
                if (v == null || JSONObject.NULL.equals(v)) {
                    continue;
                }
                if (v instanceof Boolean) {
                    b.putBoolean(k, (Boolean) v);
                } else if (v instanceof Integer) {
                    b.putInt(k, (Integer) v);
                } else if (v instanceof Long) {
                    b.putLong(k, (Long) v);
                } else if (v instanceof Double) {
                    b.putDouble(k, (Double) v);
                } else {
                    b.putString(k, String.valueOf(v));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "jsonToBundle failed", t);
        }
        return b;
    }
}
