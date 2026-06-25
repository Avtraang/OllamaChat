package com.example.ollamachat;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Minimal Ollama chat client.
 *
 * Talks to a local Ollama server over its HTTP /api/chat endpoint, keeping the
 * running conversation in memory so the model has context across turns.
 * Pure Android framework only — no Compose, no support/AndroidX libraries.
 */
public class MainActivity extends Activity {

    /** Ollama server endpoint and model. Edit these two lines to point elsewhere. */
    private static final String SERVER_URL = "http://192.168.1.186:11434/api/chat";
    private static final String MODEL = "qwen3:30b-a3b-instruct-2507-q4_K_M";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final JSONArray history = new JSONArray();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView conversation;
    private EditText input;
    private Button send;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        conversation = (TextView) findViewById(R.id.conversation);
        input = (EditText) findViewById(R.id.input);
        send = (Button) findViewById(R.id.send);
        scroll = (ScrollView) findViewById(R.id.scroll);

        conversation.setMovementMethod(new ScrollingMovementMethod());

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSend();
            }
        });

        // Let the keyboard "Send" action submit too.
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    onSend();
                    return true;
                }
                return false;
            }
        });

        appendLine("Connected to:\n" + SERVER_URL + "\nModel: " + MODEL + "\n");
    }

    private void onSend() {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        input.setText("");
        setBusy(true);
        appendLine("You: " + text);

        // Record the user turn in the conversation history.
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", text);
            history.put(msg);
        } catch (Exception e) {
            // JSONObject.put only throws on null keys; cannot happen here.
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String reply;
                boolean ok;
                try {
                    reply = requestChat();
                    ok = true;
                } catch (Exception e) {
                    reply = "[error] " + e.getMessage();
                    ok = false;
                }
                final String finalReply = reply;
                final boolean finalOk = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finalOk) {
                            appendLine("Ollama: " + finalReply + "\n");
                            try {
                                JSONObject msg = new JSONObject();
                                msg.put("role", "assistant");
                                msg.put("content", finalReply);
                                history.put(msg);
                            } catch (Exception ignored) {
                            }
                        } else {
                            appendLine(finalReply + "\n");
                        }
                        setBusy(false);
                    }
                });
            }
        }).start();
    }

    /** Performs a blocking (stream:false) /api/chat request and returns the reply text. */
    private String requestChat() throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("messages", history);
        body.put("stream", false);

        HttpURLConnection conn = (HttpURLConnection) new URL(SERVER_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            // Large local models can take a while to generate; allow up to 5 min.
            conn.setReadTimeout(300000);

            byte[] payload = body.toString().getBytes(UTF8);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
            } finally {
                os.close();
            }

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String raw = readAll(in);

            if (code < 200 || code >= 400) {
                return "[HTTP " + code + "] " + raw;
            }

            JSONObject obj = new JSONObject(raw);
            if (obj.has("message")) {
                return obj.getJSONObject("message").optString("content", "").trim();
            }
            // Fallback for the /api/generate shape, just in case.
            return obj.optString("response", raw).trim();
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        try {
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private void setBusy(boolean busy) {
        send.setEnabled(!busy);
        send.setText(busy ? "…" : getString(R.string.send));
        input.setEnabled(!busy);
    }

    private void appendLine(String line) {
        conversation.append(line + "\n");
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
