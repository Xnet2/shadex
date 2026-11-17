package com.shadex.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.concurrent.CopyOnWriteArrayList;

/** Gives the panel the live notification list and media sessions. */
public class NotifService extends NotificationListenerService {
    static NotifService instance;
    /** Views that want to hear about changes (the list, the status bar icons). */
    static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void onListenerConnected() {
        instance = this;
        ping();
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        ping();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        ping();
    }

    private static void ping() {
        for (Runnable r : listeners) r.run();
    }
}
