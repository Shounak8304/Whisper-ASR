package com.whispertflite;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whispertflite.asr.IRecorderListener;
import com.whispertflite.asr.IWhisperListener;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.utils.WaveUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

import com.whispertflite.utils.ChatbotTFLiteHelper;

public class MainActivity extends AppCompatActivity {
    private final String TAG = "MainActivity";
    private final String WAKE_WORD = "marcus";
    private final int WAKE_WORD_CHECK_INTERVAL = 2000;

    private TextView tvStatus, tvResult;
    private FloatingActionButton fabCopy;

    private Whisper mWhisper;
    private Recorder mRecorder;
    private TextToSpeech tts;
    private JSONArray conversationData;

    private boolean isWaitingForWakeWord = true;
    private Handler wakeWordHandler = new Handler();
    private Runnable wakeWordChecker;
    private String wakeWordTempFile = "wake_word_temp.wav";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String conversationFile = WaveUtil.RECORDING_FILE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        fabCopy = findViewById(R.id.fabCopy);

        fabCopy.setOnClickListener(v -> {
            String textToCopy = tvResult.getText().toString();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Copied Text", textToCopy);
            clipboard.setPrimaryClip(clip);
            Log.d(TAG, "Text copied to clipboard");
        });

        checkRecordPermission();
        copyAssetsWithExtensionsToDataFolder(this, new String[]{"tflite", "bin", "wav", "pcm", "json"});

        initConversationEngine();
        initWhisperAndRecorder();

        startWakeWordDetection();
    }

    private void startWakeWordDetection() {
        isWaitingForWakeWord = true;
        tvStatus.setText("Say \"" + WAKE_WORD + "\" to start...");
        Log.d(TAG, "Starting wake word detection");

        mRecorder.setFilePath(getFilePath(wakeWordTempFile));
        mRecorder.start();

        wakeWordChecker = new Runnable() {
            @Override
            public void run() {
                if (isWaitingForWakeWord) {
                    Log.d(TAG, "Checking for wake word...");
                    mRecorder.stop();
                    startTranscription(getFilePath(wakeWordTempFile));

                    wakeWordHandler.postDelayed(() -> {
                        if (isWaitingForWakeWord) {
                            mRecorder.setFilePath(getFilePath(wakeWordTempFile));
                            mRecorder.start();
                        }
                    }, 300);

                    wakeWordHandler.postDelayed(this, WAKE_WORD_CHECK_INTERVAL);
                }
            }
        };
        wakeWordHandler.postDelayed(wakeWordChecker, WAKE_WORD_CHECK_INTERVAL);
    }

    private void startConversation() {
        tvStatus.setText("Listening...");
        mRecorder.setFilePath(getFilePath(conversationFile));
        mRecorder.start();
    }

    private void initConversationEngine() {
        try {
            InputStream is = getAssets().open("conversation.json");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            String json = new String(buffer, StandardCharsets.UTF_8);
            conversationData = new JSONArray(json);
            Log.d(TAG, "Conversation JSON loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load conversation.json", e);
        }

        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        mainHandler.post(() -> {
                            if (!isWaitingForWakeWord) {
                                mRecorder.stop();
                                Log.d(TAG, "Stopped recording for speech output");
                            }
                        });
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        mainHandler.post(() -> {
                            if (!isWaitingForWakeWord) {
                                startConversation();
                                Log.d(TAG, "Restarted recording after speech");
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        mainHandler.post(() -> {
                            if (!isWaitingForWakeWord) {
                                startConversation();
                            }
                        });
                    }
                });
                Log.d(TAG, "TextToSpeech initialized");
            } else {
                Log.e(TAG, "TextToSpeech initialization failed");
            }
        });
    }

    private void initWhisperAndRecorder() {
        String modelPath = getFilePath("whisper-tiny-en.tflite");
        String vocabPath = getFilePath("filters_vocab_en.bin");

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelPath, vocabPath, false);
        mWhisper.setListener(new IWhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                mainHandler.post(() -> {
                    tvStatus.setText(message);
                    Log.d(TAG, "Whisper update: " + message);
                });
            }

            @Override
            public void onResultReceived(String result) {
                mainHandler.post(() -> {
                    Log.d(TAG, "Processing result: " + result);

                    if (isWaitingForWakeWord) {
                        if (result.toLowerCase().contains(WAKE_WORD.toLowerCase())) {
                            Log.d(TAG, "Wake word detected!");
                            isWaitingForWakeWord = false;
                            wakeWordHandler.removeCallbacks(wakeWordChecker);
                            tvResult.setText("Wake word detected!\n");
                            startConversation();
                        }
                    } else {
                        tvResult.append("You: " + result + "\n");
                        String response = getResponse(result.toLowerCase());
                        tvResult.append("Bot: " + response + "\n");

                        // Stop recording before speaking
                        mRecorder.stop();
                        speak(response, "response_utterance");
                    }
                });
            }
        });

        mRecorder = new Recorder(this);
        mRecorder.setListener(new IRecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                mainHandler.post(() -> {
                    tvStatus.setText(message);
                    Log.d(TAG, "Recorder: " + message);

                    if (message.equals(Recorder.MSG_RECORDING_DONE) && !isWaitingForWakeWord) {
                        startTranscription(getFilePath(conversationFile));
                    }
                });
            }

            @Override
            public void onDataReceived(float[] samples) {
                // Not used
            }
        });
    }

    private void startTranscription(String filePath) {
        Log.d(TAG, "Starting transcription for: " + filePath);
        mWhisper.setFilePath(filePath);
        mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
        mWhisper.start();
    }

    private void speak(String text, String utteranceId) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            Log.d(TAG, "Speaking: " + text);
        }
    }

    private String getResponse(String userInput) {
        try {
            int minDistance = Integer.MAX_VALUE;
            JSONObject bestMatchIntent = null;

            for (int i = 0; i < conversationData.length(); i++) {
                JSONObject intentObj = conversationData.getJSONObject(i);
                JSONArray utterances = intentObj.getJSONArray("utterances");

                for (int j = 0; j < utterances.length(); j++) {
                    JSONArray tokenArray = utterances.getJSONArray(j);
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < tokenArray.length(); k++) {
                        sb.append(tokenArray.getString(k)).append(" ");
                    }
                    String candidate = sb.toString().trim();
                    int distance = levenshteinDistance(userInput, candidate);
                    if (distance < minDistance && distance < candidate.length() / 2) {
                        minDistance = distance;
                        bestMatchIntent = intentObj;
                    }
                }
            }

            if (bestMatchIntent != null) {
                JSONArray responses = bestMatchIntent.getJSONArray("responses");
                int index = new Random().nextInt(responses.length());
                return responses.getString(index);
            } else {
                return "I didn't understand that. Can you rephrase?";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing input", e);
            return "There was an error processing your request.";
        }
    }

    private int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length(), len2 = s2.length();
        int[][] dp = new int[len1 + 1][len2 + 1];
        for (int i = 0; i <= len1; i++) dp[i][0] = i;
        for (int j = 0; j <= len2; j++) dp[0][j] = j;
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                                dp[i - 1][j] + 1,
                                dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[len1][len2];
    }

    private void checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0);
            Log.d(TAG, "Requesting RECORD_AUDIO permission");
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (wakeWordHandler != null) {
            wakeWordHandler.removeCallbacks(wakeWordChecker);
        }
        if (mRecorder != null) {
            mRecorder.stop();
        }
        super.onDestroy();
    }

    private void copyAssetsWithExtensionsToDataFolder(Context context, String[] extensions) {
        AssetManager assetManager = context.getAssets();
        try {
            String destFolder = context.getFilesDir().getAbsolutePath();
            String[] assetFiles = assetManager.list("");

            for (String assetFileName : assetFiles) {
                for (String extension : extensions) {
                    if (assetFileName.endsWith("." + extension)) {
                        File outFile = new File(destFolder, assetFileName);
                        if (outFile.exists()) continue;

                        try (InputStream inputStream = assetManager.open(assetFileName);
                             OutputStream outputStream = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[1024];
                            int read;
                            while ((read = inputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, read);
                            }
                            Log.d(TAG, "Copied asset: " + assetFileName);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Asset copy error", e);
        }
    }

    private String getFilePath(String assetName) {
        File file = new File(getFilesDir(), assetName);
        return file.getAbsolutePath();
    }
}