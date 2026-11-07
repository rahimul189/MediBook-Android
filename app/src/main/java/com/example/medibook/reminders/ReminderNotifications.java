package com.example.medibook.reminders;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentDetailsActivity;
import com.example.medibook.models.Appointment;
import com.example.medibook.utils.AppConstants;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

public final class ReminderNotifications {
    public static final String CHANNEL = "appointment_reminders";
    private ReminderNotifications() { }
    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.reminder_channel_description));
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    public static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = context.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }
    public static void cancel(Context context, String id) { NotificationManagerCompat.from(context).cancel("appointment:" + id, 0); }
    public static void show(Context context, String uid, String epoch, Appointment a) {
        createChannel(context);
        Intent intent = new Intent(context, AppointmentDetailsActivity.class)
                .setData(Uri.parse("medibook://reminder/" + epoch + "/" + Uri.encode(a.getId())))
                .putExtra("appointmentId", a.getId()).putExtra("reminderOwner", uid).putExtra("reminderEpoch", epoch);
        PendingIntent tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SimpleDateFormat date = new SimpleDateFormat("EEE, d MMM 'at' h:mm a", Locale.ENGLISH);
        date.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        String text = context.getString(R.string.reminder_body, a.getDoctorName(), date.format(a.getStartAt().toDate()));
        NotificationCompat.Builder publicVersion = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_calendar_cross).setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(context.getString(R.string.reminder_private_body));
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_calendar_cross).setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(text).setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion.build());
        try {
            if (allowed(context)) NotificationManagerCompat.from(context).notify("appointment:" + a.getId(), 0, notification.build());
        } catch (SecurityException revokedPermission) { /* Permission can be revoked between check and notify. */ }
    }
}
