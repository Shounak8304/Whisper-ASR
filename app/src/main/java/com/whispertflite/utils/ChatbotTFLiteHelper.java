package com.whispertflite.utils;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ChatbotTFLiteHelper {
    private Interpreter interpreter;
    private List<String> words;
    private List<String> labels;
    private JSONObject intentsJson;
    private Context context;
    private Random random;

    public ChatbotTFLiteHelper(Context ctx) throws Exception {
        context = ctx;
        random = new Random();

        // Copy model and data assets from assets folder to internal files directory (if not exists)
        copyAssetIfNotExists("chatbot_model.tflite");
        copyAssetIfNotExists("words.txt");
        copyAssetIfNotExists("labels.txt");
        copyAssetIfNotExists("intents.json");

        // Load TensorFlow Lite model interpreter
        interpreter = new Interpreter(loadModelFile("chatbot_model.tflite"));

        // Load words and labels from assets files
        words = readLinesFromFile("words.txt");
        labels = readLinesFromFile("labels.txt");

        // Load intents json
        intentsJson = new JSONObject(readAssetFileAsString("intents.json"));
    }

    private void copyAssetIfNotExists(String assetName) throws Exception {
        File file = new File(context.getFilesDir(), assetName);
        if (!file.exists()) {
            AssetManager assetManager = context.getAssets();
            InputStream in = assetManager.open(assetName);
            FileOutputStream out = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();
        }
    }

    private MappedByteBuffer loadModelFile(String modelFileName) throws Exception {
        File file = new File(context.getFilesDir(), modelFileName);
        FileInputStream inputStream = new FileInputStream(file);
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = 0;
        long declaredLength = file.length();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private List<String> readLinesFromFile(String filename) throws Exception {
        List<String> lines = new ArrayList<>();
        File file = new File(context.getFilesDir(), filename);
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line.trim());
        }
        reader.close();
        return lines;
    }

    private String readAssetFileAsString(String filename) throws Exception {
        StringBuilder sb = new StringBuilder();
        File file = new File(context.getFilesDir(), filename);
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    // Tokenizer: Lowercase, remove punctuation, split by spaces
    private List<String> tokenize(String text) {
        text = text.toLowerCase().replaceAll("[^a-zA-Z0-9\\s]", "");
        String[] tokens = text.split("\\s+");
        List<String> tokenList = new ArrayList<>();
        for (String token : tokens) {
            if (!token.isEmpty()) {
                tokenList.add(token);
            }
        }
        return tokenList;
    }

    // Basic Porter stemmer implementation (for better matching)
    private String stem(String word) {
        // This is a simplified stemmer example
        // For full Porter stemmer, consider using an existing library or porting one to Java
        if (word.endsWith("ing") && word.length() > 4) {
            return word.substring(0, word.length() - 3);
        }
        if (word.endsWith("ed") && word.length() > 3) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && word.length() > 3 && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    // Preprocess user input text into bag of words float array expected by model
    public float[] preprocessInput(String inputText) {
        List<String> tokens = tokenize(inputText);
        for (int i = 0; i < tokens.size(); i++) {
            tokens.set(i, stem(tokens.get(i)));
        }

        float[] bag = new float[words.size()];
        for (int i = 0; i < words.size(); i++) {
            bag[i] = tokens.contains(words.get(i)) ? 1f : 0f;
        }
        return bag;
    }

    // Run inference on preprocessed input and predict intent tag
    public String predictIntent(float[] inputVector) {
        float[][] input = new float[1][inputVector.length];
        input[0] = inputVector;

        float[][] output = new float[1][labels.size()];
        interpreter.run(input, output);

        int maxIdx = 0;
        float maxProb = output[0][0];
        for (int i = 1; i < output[0].length; i++) {
            if (output[0][i] > maxProb) {
                maxProb = output[0][i];
                maxIdx = i;
            }
        }

        // Confidence threshold fallback
        if (maxProb < 0.5f) {
            return "no_match";
        }

        return labels.get(maxIdx);
    }

    // Return a random appropriate response for the predicted intent
    public String getResponse(String intentTag) {
        try {
            if ("no_match".equals(intentTag)) {
                // Default fallback response
                return "Sorry, I didn't understand that. Could you please rephrase?";
            }

            JSONArray intentsArray = intentsJson.getJSONArray("intents");
            for (int i = 0; i < intentsArray.length(); i++) {
                JSONObject intentObj = intentsArray.getJSONObject(i);
                if (intentObj.getString("tag").equals(intentTag)) {
                    JSONArray responses = intentObj.getJSONArray("responses");
                    int idx = random.nextInt(responses.length());
                    return responses.getString(idx);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return "Sorry, I don't have an answer for that.";
    }

    // Close the interpreter to free resources when done
    public void close() {
        if (interpreter != null) interpreter.close();
    }
}