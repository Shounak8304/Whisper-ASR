package com.whispertflite.asr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.whispertflite.utils.WaveUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

public class Recorder {
    public static final String TAG = "Recorder";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private String mWavFilePath = null;
    private Thread mExecutorThread = null;
    private IRecorderListener mListener = null;

    // Silence detection parameters
    private static final int SILENCE_THRESHOLD = 3000; // Increased for robustness
    private static final long SILENCE_DURATION_MS = 3000; // 3 seconds of silence
    private static final int SAMPLE_RATE = 16000;
    private static final long MAX_RECORDING_DURATION_MS = 20000; // 20 seconds max

    public Recorder(Context context) {
        mContext = context;
    }

    public void setListener(IRecorderListener listener) {
        mListener = listener;
    }

    public void setFilePath(String wavFile) {
        mWavFilePath = wavFile;
        Log.d(TAG, "WAV file path set: " + mWavFilePath);
    }

    public void start() {
        if (mInProgress.get()) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }

        mExecutorThread = new Thread(() -> {
            mInProgress.set(true);
            threadFunction();
            mInProgress.set(false);
        });
        mExecutorThread.start();
    }

    public void stop() {
        mInProgress.set(false);
        try {
            if (mExecutorThread != null) {
                mExecutorThread.join();
                mExecutorThread = null;
                Log.d(TAG, "Recording thread stopped");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Error stopping recording thread", e);
            throw new RuntimeException(e);
        }
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        if (mListener != null) {
            mListener.onUpdateReceived(message);
            Log.d(TAG, "Sent update: " + message);
        }
    }

    private void sendData(float[] samples) {
        if (mListener != null) {
            mListener.onDataReceived(samples);
        }
    }

    private void threadFunction() {
        try {
            if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "AudioRecord permission is not granted");
                sendUpdate("Permission denied for audio recording");
                return;
            }

            sendUpdate(MSG_RECORDING);

            int channels = 1;
            int bytesPerSample = 2;
            int sampleRateInHz = 16000;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int audioSource = MediaRecorder.AudioSource.MIC;

            int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
            AudioRecord audioRecord = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, bufferSize);
            audioRecord.startRecording();
            Log.d(TAG, "Audio recording started, buffer size: " + bufferSize);

            int bufferSize30Sec = sampleRateInHz * bytesPerSample * channels * 30;
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize30Sec);
            int totalBytesRead = 0;

            byte[] audioData = new byte[bufferSize];
            long silenceStartTime = -1;
            boolean isSilent = false;
            long recordingStartTime = System.currentTimeMillis();

            while (mInProgress.get()) {
                // Check for maximum recording duration
                if (System.currentTimeMillis() - recordingStartTime >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "Maximum recording duration reached, stopping");
                    mInProgress.set(false);
                    break;
                }

                int bytesRead = audioRecord.read(audioData, 0, bufferSize);
                if (bytesRead > 0) {
                    buffer.put(audioData, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    Log.v(TAG, "Read " + bytesRead + " bytes, total: " + totalBytesRead);

                    // Convert byte array to short array for amplitude calculation
                    short[] samples = new short[bytesRead / 2];
                    ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);

                    // Calculate RMS amplitude
                    double rms = 0;
                    for (short sample : samples) {
                        rms += sample * sample;
                    }
                    rms = Math.sqrt(rms / samples.length);
                    Log.v(TAG, "RMS amplitude: " + rms);

                    // Silence detection
                    if (rms < SILENCE_THRESHOLD) {
                        if (!isSilent) {
                            silenceStartTime = System.currentTimeMillis();
                            isSilent = true;
                            Log.d(TAG, "Silence detected, starting timer at " + silenceStartTime);
                        } else if (System.currentTimeMillis() - silenceStartTime >= SILENCE_DURATION_MS) {
                            Log.d(TAG, "3 seconds of silence detected, stopping recording");
                            mInProgress.set(false);
                            break; // Exit loop immediately
                        }
                    } else {
                        isSilent = false;
                        silenceStartTime = -1;
                        sendUpdate(MSG_RECORDING);
                        Log.v(TAG, "Speech detected, resetting silence timer");
                    }
                } else {
                    Log.e(TAG, "AudioRecord error, bytes read: " + bytesRead);
                    sendUpdate("Error reading audio data");
                    break;
                }
            }

            audioRecord.stop();
            audioRecord.release();
            Log.d(TAG, "Audio recording stopped, total bytes: " + totalBytesRead);

            // Trim buffer to actual size
            if (totalBytesRead > 0) {
                byte[] trimmedBuffer = new byte[totalBytesRead];
                buffer.rewind();
                buffer.get(trimmedBuffer, 0, totalBytesRead);

                // Save recording buffer in WAV file
                WaveUtil.createWaveFile(mWavFilePath, trimmedBuffer, sampleRateInHz, channels, bytesPerSample);
                Log.d(TAG, "WAV file saved: " + mWavFilePath);

                sendUpdate(MSG_RECORDING_DONE);
            } else {
                Log.e(TAG, "No audio data recorded");
                sendUpdate("Error: No audio data recorded");
            }
        } catch (Exception e) {
            Log.e(TAG, "Recording error", e);
            sendUpdate("Error: " + e.getMessage());
        }
    }
}