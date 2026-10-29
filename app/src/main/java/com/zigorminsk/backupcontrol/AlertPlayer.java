package com.zigorminsk.backupcontrol;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.res.AssetFileDescriptor;

/**
 * Plays the reminder on the phone itself.
 *
 * A notification's sound follows the current audio route, so with headphones or
 * a Bluetooth headset connected Android plays it in the headphones and the phone
 * stays silent. This player uses the alarm stream and pins the output to the
 * built-in speaker, so the phone always rings, and it vibrates on its own so the
 * reminder can be felt even with the sound turned down.
 */
final class AlertPlayer {
    private static final long RING_MS = 6000;
    private static MediaPlayer player;
    private static long lastPlayedAt;

    private AlertPlayer() { }

    /** Timestamp of the last playback; used by the device check. */
    static long lastPlayedAt() { return lastPlayedAt; }

    static synchronized void play(Context context) {
        stop();
        try {
            MediaPlayer media = new MediaPlayer();
            AssetFileDescriptor file = context.getResources().openRawResourceFd(R.raw.notification_chime);
            try {
                media.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            } finally {
                file.close();
            }
            media.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            media.setLooping(true);
            media.prepare();
            AudioDeviceInfo speaker = builtinSpeaker(context);
            if (speaker != null) media.setPreferredDevice(speaker);
            media.start();
            player = media;
            lastPlayedAt = System.currentTimeMillis();
            // Ring long enough to be noticed, then stop by itself.
            new Handler(Looper.getMainLooper()).postDelayed(AlertPlayer::stop, RING_MS);
        } catch (Exception ignored) {
            // The notification (and the vibration below) still alert the user.
        }
        vibrate(context);
    }

    static synchronized void stop() {
        if (player == null) return;
        try {
            player.stop();
        } catch (IllegalStateException ignored) {
            // Already stopped.
        }
        player.release();
        player = null;
    }

    /** With headphones attached the default route is not the phone speaker. */
    static AudioDeviceInfo builtinSpeaker(Context context) {
        if (Build.VERSION.SDK_INT < 23) return null;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return null;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
        }
        return null;
    }

    private static void vibrate(Context context) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 450, 250, 450, 250, 700};
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (SecurityException ignored) {
            // Vibration is a nicety; the sound and the notification remain.
        }
    }
}
