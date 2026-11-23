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
 * Panel still binds to the service across processes. C# only polls/sends via String CallStatic.
 */
public final class ModSettingsModBridge {
    private static final String TAG = "BugtopiaModSettings";

    private static final ConcurrentLinkedQueue<String> incoming = new ConcurrentLinkedQueue<>();
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
        String next = incoming.poll();
        return next != null ? next : "";
    }

    public static String sendJson(String whatStr, String bundleJson, String attachReplyToStr) {
        Messenger svc = serviceMessenger;
        if (svc == null) {
            return "0";
        }
        try {
            int what = Integer.parseInt(whatStr);
            boolean attachReplyTo = "1".equals(attachReplyToStr);
            ensureReplyMessenger();
            Message msg = Message.obtain(null, what);
            msg.setData(jsonToBundle(bundleJson));
            if (attachReplyTo) {
                msg.replyTo = replyMessenger;
            }
            svc.send(msg);
            return "1";
        } catch (Throwable t) {
            Log.w(TAG, "sendJson failed what=" + whatStr, t);
            return "0";
        }
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
