package chronik.akasha.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView status;
    private Button recordButton, stopButton;
    private AudioRecord recorder;
    private Thread recordingThread;
    private volatile boolean recording = false;
    private File outputFile;
    private final int sampleRate = 48000;
    private final int channels = 2;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(38, 70, 38, 38);
        layout.setBackgroundColor(Color.rgb(17, 23, 33));

        TextView heading = new TextView(this);
        heading.setText("AKASHA RECORDER · Android 1.0");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(22);
        layout.addView(heading);

        status = new TextView(this);
        status.setText("USB-Sidekick anschließen. Aufnahmeformat: 48 kHz / Stereo / 16 Bit.\nAchtung: Android kann einen anderen Eingang wählen.");
        status.setTextColor(Color.rgb(190, 210, 225));
        status.setTextSize(16);
        status.setPadding(0, 40, 0, 40);
        layout.addView(status);

        recordButton = new Button(this);
        recordButton.setText("● Aufnahme starten");
        layout.addView(recordButton);

        stopButton = new Button(this);
        stopButton.setText("■ Aufnahme stoppen");
        stopButton.setEnabled(false);
        layout.addView(stopButton);

        TextView note = new TextView(this);
        note.setText("Testversion: AudioRecord über Android. Noch kein direkter USB-Mehrkanalzugriff. WAV wird im App-Musikordner gespeichert.");
        note.setTextColor(Color.LTGRAY);
        note.setPadding(0, 36, 0, 0);
        layout.addView(note);

        setContentView(layout);

        recordButton.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            } else startRecording();
        });

        stopButton.setOnClickListener(v -> stopRecording());
    }

    @Override public void onRequestPermissionsResult(
            int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 7 && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED)
            startRecording();
        else status.setText("Mikrofonberechtigung erforderlich.");
    }

    private void startRecording() {
        int format = AudioFormat.ENCODING_PCM_16BIT;
        int mask = AudioFormat.CHANNEL_IN_STEREO;
        int min = AudioRecord.getMinBufferSize(
            sampleRate, mask, format);

        if (min <= 0) {
            status.setText("Stereoaufnahme wird von Android nicht unterstützt.");
            return;
        }

        int bufferSize = Math.max(min * 4, 19200);

        try {
            recorder = new AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRate, mask, format, bufferSize);

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = new AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    sampleRate, mask, format, bufferSize);
            }

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException(
                    "AudioRecord nicht initialisiert");

            File dir = getExternalFilesDir(
                Environment.DIRECTORY_MUSIC);

            if (dir == null)
                throw new IllegalStateException(
                    "Speicher nicht verfügbar");

            outputFile = new File(dir,
                "Akasha-" + new SimpleDateFormat(
                    "yyyyMMdd-HHmmss", Locale.US
                ).format(new Date()) + ".wav");

            recorder.startRecording();
            recording = true;

            recordButton.setEnabled(false);
            stopButton.setEnabled(true);
            status.setText(
                "Aufnahme läuft · 48 kHz Stereo PCM\nEingang wird von Android ausgewählt.");

            recordingThread = new Thread(
                () -> writeWav(bufferSize));
            recordingThread.start();

        } catch (Exception e) {
            recording = false;
            if (recorder != null) {
                try { recorder.release(); }
                catch (Exception ignored) {}
                recorder = null;
            }
            status.setText("Fehler: " + e.getMessage());
        }
    }

    private void writeWav(int bufferSize) {
        long bytes = 0;

        try (FileOutputStream out =
                 new FileOutputStream(outputFile)) {

            out.write(new byte[44]);
            byte[] data = new byte[bufferSize];

            while (recording) {
                int count = recorder.read(
                    data, 0, data.length);

                if (count > 0) {
                    out.write(data, 0, count);
                    bytes += count;
                } else if (count < 0) {
                    throw new IllegalStateException(
                        "Audiofehler: " + count);
                }
            }

        } catch (Exception e) {
            final String message = e.getMessage();
            runOnUiThread(() ->
                status.setText("Aufnahmefehler: " + message));

        } finally {
            try {
                if (bytes > 0)
                    patchWavHeader(outputFile, bytes);

                final long written = bytes;

                runOnUiThread(() -> {
                    if (written > 0)
                        status.setText(
                            "Gespeichert: "
                            + outputFile.getAbsolutePath()
                            + "\n" + (written / 192000)
                            + " Sekunden · Stereo-Dateiformat (Inhalt noch zu prüfen)");

                    recordButton.setEnabled(true);
                    stopButton.setEnabled(false);
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                    status.setText(
                        "WAV-Fehler: " + e.getMessage()));
            }

            if (recorder != null) {
                try { recorder.stop(); }
                catch (Exception ignored) {}
                recorder.release();
                recorder = null;
            }
        }
    }

    private void patchWavHeader(
            File file, long dataBytes) throws Exception {

        try (RandomAccessFile raf =
                 new RandomAccessFile(file, "rw")) {

            raf.seek(0);
            raf.writeBytes("RIFF");
            le32(raf, dataBytes + 36);
            raf.writeBytes("WAVEfmt ");
            le32(raf, 16);
            le16(raf, 1);
            le16(raf, channels);
            le32(raf, sampleRate);
            le32(raf, sampleRate * channels * 2);
            le16(raf, channels * 2);
            le16(raf, 16);
            raf.writeBytes("data");
            le32(raf, dataBytes);
        }
    }

    private void le16(
            RandomAccessFile f, long v) throws Exception {
        f.write((int) v & 255);
        f.write((int) (v >> 8) & 255);
    }

    private void le32(
            RandomAccessFile f, long v) throws Exception {
        le16(f, v);
        le16(f, v >> 16);
    }

    private void stopRecording() {
        recording = false;
        stopButton.setEnabled(false);
    }

    @Override protected void onDestroy() {
        if (recording) stopRecording();
        super.onDestroy();
    }
}
