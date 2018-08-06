/*  Copyright (C) 2015-2018 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti, Frank Slezak, Hasan Ammar, Julien Pivotto, Kevin Richter, Normano64,
    Steffen Liebergeld, Taavi Eomäe, Zhong Jianxin

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.RemoteInput;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.graphics.Palette;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.pebble.PebbleColor;
import nodomain.freeyourgadget.gadgetbridge.model.AppNotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService;
import nodomain.freeyourgadget.gadgetbridge.util.BitmapUtil;
import nodomain.freeyourgadget.gadgetbridge.util.LimitedQueue;
import nodomain.freeyourgadget.gadgetbridge.util.PebbleUtils;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

import static android.support.v4.media.app.NotificationCompat.MediaStyle.getMediaSession;

public class NotificationListener extends NotificationListenerService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationListener.class);

    public static final String ACTION_DISMISS
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.dismiss";
    public static final String ACTION_DISMISS_ALL
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.dismiss_all";
    public static final String ACTION_OPEN
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.open";
    public static final String ACTION_MUTE
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.mute";
    public static final String ACTION_REPLY
            = "nodomain.freeyourgadget.gadgetbridge.notificationlistener.action.reply";

    private LimitedQueue mActionLookup = new LimitedQueue(16);

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case GBApplication.ACTION_QUIT:
                    stopSelf();
                    break;
                case ACTION_MUTE:
                case ACTION_OPEN: {
                    StatusBarNotification[] sbns = NotificationListener.this.getActiveNotifications();
                    int handle = intent.getIntExtra("handle", -1);
                    for (StatusBarNotification sbn : sbns) {
                        if ((int) sbn.getPostTime() == handle) {
                            if (action.equals(ACTION_OPEN)) {
                                try {
                                    PendingIntent pi = sbn.getNotification().contentIntent;
                                    if (pi != null) {
                                        pi.send();
                                    }
                                } catch (PendingIntent.CanceledException e) {
                                    e.printStackTrace();
                                }
                            } else {
                                // ACTION_MUTE
                                LOG.info("going to mute " + sbn.getPackageName());
                                GBApplication.addAppToNotifBlacklist(sbn.getPackageName());
                            }
                        }
                    }
                    break;
                }
                case ACTION_DISMISS: {
                    StatusBarNotification[] sbns = NotificationListener.this.getActiveNotifications();
                    int handle = intent.getIntExtra("handle", -1);
                    for (StatusBarNotification sbn : sbns) {
                        if ((int) sbn.getPostTime() == handle) {
                            if (GBApplication.isRunningLollipopOrLater()) {
                                String key = sbn.getKey();
                                NotificationListener.this.cancelNotification(key);
                            } else {
                                int id = sbn.getId();
                                String pkg = sbn.getPackageName();
                                String tag = sbn.getTag();
                                NotificationListener.this.cancelNotification(pkg, tag, id);
                            }
                        }
                    }
                    break;
                }
                case ACTION_DISMISS_ALL:
                    NotificationListener.this.cancelAllNotifications();
                    break;
                case ACTION_REPLY:
                    int id = intent.getIntExtra("handle", -1);
                    String reply = intent.getStringExtra("reply");
                    NotificationCompat.Action replyAction = (NotificationCompat.Action) mActionLookup.lookup(id);
                    if (replyAction != null && replyAction.getRemoteInputs() != null) {
                        RemoteInput[] remoteInputs = replyAction.getRemoteInputs();
                        PendingIntent actionIntent = replyAction.getActionIntent();
                        Intent localIntent = new Intent();
                        localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        Bundle extras = new Bundle();
                        extras.putCharSequence(remoteInputs[0].getResultKey(), reply);
                        RemoteInput.addResultsToIntent(remoteInputs, localIntent, extras);

                        try {
                            LOG.info("will send reply intent to remote application");
                            actionIntent.send(context, 0, localIntent);
                            mActionLookup.remove(id);
                        } catch (PendingIntent.CanceledException e) {
                            LOG.warn("replyToLastNotification error: " + e.getLocalizedMessage());
                        }
                    }
                    break;
            }

        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filterLocal = new IntentFilter();
        filterLocal.addAction(GBApplication.ACTION_QUIT);
        filterLocal.addAction(ACTION_OPEN);
        filterLocal.addAction(ACTION_DISMISS);
        filterLocal.addAction(ACTION_DISMISS_ALL);
        filterLocal.addAction(ACTION_MUTE);
        filterLocal.addAction(ACTION_REPLY);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filterLocal);
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (shouldIgnore(sbn))
            return;

        switch (GBApplication.getGrantedInterruptionFilter()) {
            case NotificationManager.INTERRUPTION_FILTER_ALL:
                break;
            case NotificationManager.INTERRUPTION_FILTER_ALARMS:
            case NotificationManager.INTERRUPTION_FILTER_NONE:
                return;
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY:
                // FIXME: Handle Reminders and Events if they are enabled in Do Not Disturb
                return;
        }

        String source = sbn.getPackageName().toLowerCase();
        Notification notification = sbn.getNotification();
        NotificationSpec notificationSpec = new NotificationSpec();
        notificationSpec.id = (int) sbn.getPostTime(); //FIXME: a truly unique id would be better

        // determinate Source App Name ("Label")
        PackageManager pm = getPackageManager();
        ApplicationInfo ai = null;
        try {
            ai = pm.getApplicationInfo(source, 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        if (ai != null) {
            notificationSpec.sourceName = (String) pm.getApplicationLabel(ai);
        }

        boolean preferBigText = false;

        // Get the app ID that generated this notification. For now only used by pebble color, but may be more useful later.
        notificationSpec.sourceAppId = source;

        notificationSpec.type = AppNotificationType.getInstance().get(source);

        if (source.startsWith("com.fsck.k9")) {
            preferBigText = true;
        }

        if (notificationSpec.type == null) {
            notificationSpec.type = NotificationType.UNKNOWN;
        }

        // Get color
        notificationSpec.pebbleColor = getPebbleColorForNotification(notificationSpec);

        LOG.info("Processing notification " + notificationSpec.id + " from source " + source + " with flags: " + notification.flags);

        dissectNotificationTo(notification, notificationSpec, preferBigText);

        // ignore Gadgetbridge's very own notifications, except for those from the debug screen
        if (getApplicationContext().getPackageName().equals(source)) {
            if (!getApplicationContext().getString(R.string.test_notification).equals(notificationSpec.title)) {
                return;
            }
        }

        NotificationCompat.WearableExtender wearableExtender = new NotificationCompat.WearableExtender(notification);
        List<NotificationCompat.Action> actions = wearableExtender.getActions();

        for (NotificationCompat.Action act : actions) {
            if (act != null && act.getRemoteInputs() != null) {
                LOG.info("found wearable action: " + act.getTitle() + "  " + sbn.getTag());
                mActionLookup.add(notificationSpec.id, act);
                notificationSpec.flags |= NotificationSpec.FLAG_WEARABLE_REPLY;
                break;
            }
        }

        if ((notificationSpec.flags & NotificationSpec.FLAG_WEARABLE_REPLY) == 0 && NotificationCompat.isGroupSummary(notification)) { //this could cause #395 to come back
            LOG.info("Not forwarding notification, FLAG_GROUP_SUMMARY is set and no wearable action present. Notification flags: " + notification.flags);
            return;
        }

        GBApplication.deviceService().onNotification(notificationSpec);
    }

    private void dissectNotificationTo(Notification notification, NotificationSpec notificationSpec, boolean preferBigText) {

        Bundle extras = NotificationCompat.getExtras(notification);

        //dumpExtras(extras);

        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title != null) {
            notificationSpec.title = title.toString();
        }

        CharSequence contentCS = null;
        if (preferBigText && extras.containsKey(Notification.EXTRA_BIG_TEXT)) {
            contentCS = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT);
        } else if (extras.containsKey(Notification.EXTRA_TEXT)) {
            contentCS = extras.getCharSequence(NotificationCompat.EXTRA_TEXT);
        }
        if (contentCS != null) {
            notificationSpec.body = contentCS.toString();
        }

    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (DeviceCommunicationService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Try to handle media session notifications that tell info about the current play state.
     *
     * @param mediaSession The mediasession to handle.
     * @return true if notification was handled, false otherwise
     */
    public boolean handleMediaSessionNotification(MediaSessionCompat.Token mediaSession) {



        MediaControllerCompat c;
        try {
            c = new MediaControllerCompat(getApplicationContext(), mediaSession);

            PlaybackStateCompat s = c.getPlaybackState();



            MediaMetadataCompat d = c.getMetadata();
            if (d == null)
                return false;

            // finally, tell the device about it

            return true;
        } catch (NullPointerException | RemoteException e) {
            return false;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (shouldIgnore(sbn))
            return;

        Prefs prefs = GBApplication.getPrefs();
        if (prefs.getBoolean("autoremove_notifications", false)) {
            LOG.info("notification removed, will ask device to delete it");
            GBApplication.deviceService().onDeleteNotification((int) sbn.getPostTime());
        }
    }

    private void dumpExtras(Bundle bundle) {
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value == null) {
                continue;
            }
            LOG.debug(String.format("Notification extra: %s %s (%s)", key, value.toString(), value.getClass().getName()));
        }
    }

    private boolean shouldIgnore(StatusBarNotification sbn) {
        /*
        * return early if DeviceCommunicationService is not running,
        * else the service would get started every time we get a notification.
        * unfortunately we cannot enable/disable NotificationListener at runtime like we do with
        * broadcast receivers because it seems to invalidate the permissions that are
        * necessary for NotificationListenerService
        */
        if (!isServiceRunning() || sbn == null) {
            return true;
        }

        return shouldIgnoreSource(sbn.getPackageName()) || shouldIgnoreNotification(
                sbn.getNotification(), sbn.getPackageName());

    }

    private boolean shouldIgnoreSource(String source) {
        Prefs prefs = GBApplication.getPrefs();

        /* do not display messages from "android"
         * This includes keyboard selection message, usb connection messages, etc
         * Hope it does not filter out too much, we will see...
         */

        if (source.equals("android") ||
                source.equals("com.android.systemui") ||
                source.equals("com.android.dialer") ||
                source.equals("com.cyanogenmod.eleven")) {
            LOG.info("Ignoring notification, is a system event");
            return true;
        }

        if (source.equals("com.moez.QKSMS") ||
                source.equals("com.android.mms") ||
                source.equals("com.sonyericsson.conversations") ||
                source.equals("com.android.messaging") ||
                source.equals("org.smssecure.smssecure")) {
            if (!"never".equals(prefs.getString("notification_mode_sms", "when_screen_off"))) {
                return true;
            }
        }

        if (GBApplication.appIsNotifBlacklisted(source)) {
            LOG.info("Ignoring notification, application is blacklisted");
            return true;
        }

        return false;
    }

    private boolean shouldIgnoreNotification(Notification notification, String source) {

        MediaSessionCompat.Token mediaSession = getMediaSession(notification);
        //try to handle media session notifications
        if (mediaSession != null && handleMediaSessionNotification(mediaSession))
            return true;

        NotificationType type = AppNotificationType.getInstance().get(source);
        //ignore notifications marked as LocalOnly https://developer.android.com/reference/android/app/Notification.html#FLAG_LOCAL_ONLY
        //some Apps always mark their notifcations as read-only
        if (NotificationCompat.getLocalOnly(notification) &&
                type != NotificationType.WECHAT &&
                type != NotificationType.OUTLOOK &&
                type != NotificationType.SKYPE) { //see https://github.com/Freeyourgadget/Gadgetbridge/issues/1109
            return true;
        }

        Prefs prefs = GBApplication.getPrefs();
        if (!prefs.getBoolean("notifications_generic_whenscreenon", false)) {
            PowerManager powermanager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powermanager.isScreenOn()) {
//                LOG.info("Not forwarding notification, screen seems to be on and settings do not allow this");
                return true;
            }
        }

        return (notification.flags & Notification.FLAG_ONGOING_EVENT) == Notification.FLAG_ONGOING_EVENT;

    }


    /**
     * Get the notification color that should be used for this Pebble notification.
     *
     * Note that this method will *not* edit the NotificationSpec passed in. It will only evaluate the PebbleColor.
     *
     * See Issue #815 on GitHub to see how notification colors are set.
     *
     * @param notificationSpec The NotificationSpec to read from.
     * @return Returns a PebbleColor that best represents this notification.
     */
    private byte getPebbleColorForNotification(NotificationSpec notificationSpec) {
        String appId = notificationSpec.sourceAppId;
        NotificationType existingType = notificationSpec.type;

        // If the notification type is known, return the associated color.
        if (existingType != NotificationType.UNKNOWN) {
            return existingType.color;
        }

        // Otherwise, we go and attempt to find the color from the app icon.
        Drawable icon;
        try {
            icon = getApplicationContext().getPackageManager().getApplicationIcon(appId);
            Objects.requireNonNull(icon);
        } catch (Exception ex) {
            // If we can't get the icon, we go with the default defined above.
            LOG.warn("Could not get icon for AppID " + appId, ex);
            return PebbleColor.IslamicGreen;
        }

        Bitmap bitmapIcon = BitmapUtil.convertDrawableToBitmap(icon);
        int iconPrimaryColor = new Palette.Builder(bitmapIcon)
                .generate()
                .getVibrantColor(Color.parseColor("#aa0000"));

        return PebbleUtils.getPebbleColor(iconPrimaryColor);
    }
}
