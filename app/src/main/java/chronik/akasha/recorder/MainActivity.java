package chronik.akasha.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int AUDIO_PERMISSION = 7;
    private TextView status, inputStatus;
    private LinearLayout recordingsList;
    private Button recordButton, stopButton;
    private AudioRecord recorder;
    private Thread recordingThread;
    private volatile boolean recording = false;
    private File outputFile;
    private MediaPlayer player;
    private File playingFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 52, 32, 40);
        layout.setBackgroundColor(Color.rgb(17, 23, 33));
        scroll.addView(layout);

        TextView heading = new TextView(this);
        heading.setText("AKASHA RECORDER · 1.1");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(23);
        layout.addView(heading);

        status = makeText("Bereit · WAV 48 kHz / 16 Bit / 2 Kanäle", 16);
        status.setPadding(0, 26, 0, 18);
        layout.addView(status);

        inputStatus = makeText("Audioeingang wird beim Aufnahmestart geprüft.", 14);
        layout.addView(inputStatus);

        recordButton = new Button(this);
        recordButton.setText("● Aufnahme starten");
        layout.addView(recordButton);

        stopButton = new Button(this);
        stopButton.setText("■ Aufnahme stoppen");
        stopButton.setEnabled(false);
        layout.addView(stopButton);

        Button refresh = new Button(this);
        refresh.setText("↻ Aufnahmen aktualisieren");
        layout.addView(refresh);

        TextView listHeading = makeText("GESPEICHERTE AUFNAHMEN", 17);
        listHeading.setPadding(0, 24, 0, 12);
        layout.addView(listHeading);
        recordingsList = new LinearLayout(this);
        recordingsList.setOrientation(LinearLayout.VERTICAL);
        layout.addView(recordingsList);

        TextView note = makeText("USB-Stereo ist erst nach einem Links/Rechts-Test bestätigt. Teilen exportiert eine Kopie in den Musikordner.", 13);
        note.setPadding(0, 28, 0, 0);
        layout.addView(note);
        setContentView(scroll);

        recordButton.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            } else startRecording();
        });
        stopButton.setOnClickListener(v -> stopRecording());
        refresh.setOnClickListener(v -> refreshRecordings());
        refreshRecordings();
    }

    private TextView makeText(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(198, 216, 231));
        return view;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == AUDIO_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else if (code == AUDIO_PERMISSION) {
            status.setText("Mikrofonberechtigung erforderlich.");
        }
    }

    private AudioDeviceInfo findUsbInput() {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (manager == null) return null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) return device;
        }
        return null;
    }

    private String describeDevice(AudioDeviceInfo device) {
        if (device == null) return "Kein aktiver Audioeingang ermittelt";
        return device.getProductName() + " (Typ " + device.getType() + ")";
    }

    private AudioRecord createRecorder(int source, int bufferSize) {
        return new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);
    }

    private void startRecording() {
        if (recording || (recordingThread != null && recordingThread.isAlive())) return;
        stopPlayback();
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            status.setText("Stereoaufnahme mit 48 kHz wird nicht unterstützt.");
            return;
        }
        int bufferSize = Math.max(min * 4, 19200);
        try {
            recorder = createRecorder(MediaRecorder.AudioSource.UNPROCESSED, bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = createRecorder(MediaRecorder.AudioSource.DEFAULT, bufferSize);
            }
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord nicht initialisiert");
            }
            AudioDeviceInfo usb = findUsbInput();
            boolean preferred = usb != null && recorder.setPreferredDevice(usb);
            File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (dir == null) throw new IllegalStateException("Speicher nicht verfügbar");
            outputFile = new File(dir, "Akasha-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date()) + ".wav");
            recorder.startRecording();
            recording = true;
            recordButton.setEnabled(false);
            stopButton.setEnabled(true);
            status.setText("Aufnahme läuft · 48 kHz / Stereo / 16 Bit");
            AudioDeviceInfo routed = recorder.getRoutedDevice();
            inputStatus.setText("USB-Eingang: " + (usb == null ? "nicht gefunden" : describeDevice(usb))
                    + "\nUSB bevorzugt: " + (preferred ? "ja" : "nein")
                    + "\nAktive Route: " + describeDevice(routed));
            final AudioRecord activeRecorder = recorder;
            final File activeFile = outputFile;
            recordingThread = new Thread(() -> writeWav(activeRecorder, activeFile, bufferSize), "AkashaWavWriter");
            recordingThread.start();
        } catch (Exception e) {
            recording = false;
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
            recordButton.setEnabled(true);
            stopButton.setEnabled(false);
            status.setText("Aufnahmefehler: " + e.getMessage());
        }
    }

    private void writeWav(AudioRecord activeRecorder, File file, int bufferSize) {
        long bytes = 0;
        String error = null;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[44]);
            byte[] data = new byte[bufferSize];
            while (recording) {
                int count = activeRecorder.read(data, 0, data.length);
                if (count > 0) {
                    out.write(data, 0, count);
                    bytes += count;
                } else if (count < 0) {
                    throw new IllegalStateException("Audiofehler: " + count);
                }
            }
        } catch (Exception e) {
            error = e.getMessage();
        } finally {
            recording = false;
            try { activeRecorder.stop(); } catch (Exception ignored) {}
            activeRecorder.release();
            recorder = null;
            if (bytes > 0) {
                try { patchWavHeader(file, bytes); }
                catch (Exception e) { error = "WAV-Header: " + e.getMessage(); }
            } else if (file.exists()) {
                file.delete();
            }
            final String finalError = error;
            final long written = bytes;
            runOnUiThread(() -> {
                recordButton.setEnabled(true);
                stopButton.setEnabled(false);
                if (finalError != null) status.setText("Aufnahmefehler: " + finalError);
                else if (written > 0) status.setText("Gespeichert: " + file.getName()
                        + " · " + (written / (SAMPLE_RATE * CHANNELS * 2)) + " Sekunden");
                else status.setText("Keine Audiodaten aufgenommen.");
                refreshRecordings();
            });
        }
    }

    private void stopRecording() {
        recording = false;
        stopButton.setEnabled(false);
        status.setText("Aufnahme wird abgeschlossen …");
    }

    private void refreshRecordings() {
        recordingsList.removeAllViews();
        File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        File[] files = dir == null ? null : dir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".wav"));
        if (files == null || files.length == 0) {
            recordingsList.addView(makeText("Noch keine WAV-Aufnahmen vorhanden.", 14));
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            TextView title = makeText(file.getName() + " · " + (file.length() / 1024) + " KB", 14);
            title.setPadding(0, 16, 0, 2);
            recordingsList.addView(title);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button play = new Button(this);
            play.setText("▶ Anhören");
            actions.addView(play, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button share = new Button(this);
            share.setText("↗ Teilen");
            actions.addView(share, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            recordingsList.addView(actions);
            play.setOnClickListener(v -> playFile(file));
            share.setOnClickListener(v -> shareFile(file));
        }
    }

    private void playFile(File file) {
        if (recording) {
            status.setText("Bitte zuerst die Aufnahme stoppen.");
            return;
        }
        if (player != null && file.equals(playingFile)) {
            stopPlayback();
            status.setText("Wiedergabe beendet.");
            return;
        }
        stopPlayback();
        try {
            player = new MediaPlayer();
            playingFile = file;
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(mp -> {
                stopPlayback();
                status.setText("Wiedergabe abgeschlossen: " + file.getName());
            });
            player.start();
            status.setText("Wiedergabe: " + file.getName() + "\nZum Stoppen erneut auf Anhören tippen.");
        } catch (Exception e) {
            stopPlayback();
            status.setText("Wiedergabefehler: " + e.getMessage());
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        playingFile = null;
    }

    private void shareFile(File file) {
        if (recording) {
            status.setText("Bitte zuerst die Aufnahme stoppen.");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            status.setText("Teilen benötigt in dieser Version Android 10 oder neuer.");
            return;
        }
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Akasha Recorder");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Export konnte nicht erstellt werden");
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Exportdatei nicht beschreibbar");
                byte[] buffer = new byte[32768];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("audio/wav");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "WAV-Aufnahme teilen"));
            status.setText("Kopie exportiert nach Musik/Akasha Recorder: " + file.getName());
        } catch (Exception e) {
            if (uri != null) {
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            }
            status.setText("Teilen fehlgeschlagen: " + e.getMessage());
        }
    }

    private void patchWavHeader(File file, long dataBytes) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.writeBytes("RIFF");
            le32(raf, dataBytes + 36);
            raf.writeBytes("WAVEfmt ");
            le32(raf, 16);
            le16(raf, 1);
            le16(raf, CHANNELS);
            le32(raf, SAMPLE_RATE);
            le32(raf, SAMPLE_RATE * CHANNELS * 2);
            le16(raf, CHANNELS * 2);
            le16(raf, 16);
            raf.writeBytes("data");
            le32(raf, dataBytes);
        }
    }

    private void le16(RandomAccessFile f, long v) throws Exception {
        f.write((int) v & 255);
        f.write((int) (v >> 8) & 255);
    }

    private void le32(RandomAccessFile f, long v) throws Exception {
        le16(f, v);
        le16(f, v >> 16);
    }

    @Override protected void onDestroy() {
        if (recording) stopRecording();
        stopPlayback();
        super.onDestroy();
    }
}
