package com.example.ollamachat;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
 * The chat bubbles are built programmatically (rounded GradientDrawables) so
 * the project ships without any drawable assets.
 */
public class MainActivity extends Activity {

    /** Ollama server endpoint and model. Edit these two lines to point elsewhere. */
    private static final String SERVER_URL = "http://192.168.1.186:11434/api/chat";
    private static final String MODEL = "qwen3:30b-a3b-instruct-2507-q4_K_M";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // Palette
    private static final int COLOR_USER = 0xFF0B5FFF;
    private static final int COLOR_USER_TEXT = 0xFFFFFFFF;
    private static final int COLOR_BOT = 0xFFFFFFFF;
    private static final int COLOR_BOT_TEXT = 0xFF1A1A1A;
    private static final int COLOR_BOT_STROKE = 0xFFE2E6EC;
    private static final int COLOR_SEND = 0xFF0B5FFF;
    private static final int COLOR_SEND_DISABLED = 0xFFB6C2D6;
    private static final int COLOR_HINT_TEXT = 0xFF8A9099;

    private final JSONArray history = new JSONArray();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout container;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private float density;
    private int maxBubbleWidth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        maxBubbleWidth = (int) (dm.widthPixels * 0.80f);

        container = (LinearLayout) findViewById(R.id.container);
        scroll = (ScrollView) findViewById(R.id.scroll);
        input = (EditText) findViewById(R.id.input);
        send = (Button) findViewById(R.id.send);

        styleInputBar();

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSend();
            }
        });

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

        addSystemNote("Connected to " + SERVER_URL.replace("/api/chat", "")
                + "\n" + MODEL);
    }

    // ---------------------------------------------------------------- styling

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private void styleInputBar() {
        // Rounded, bordered text field.
        GradientDrawable field = new GradientDrawable();
        field.setColor(0xFFF2F4F8);
        field.setCornerRadius(dp(22));
        field.setStroke(dp(1), 0xFFD8DDE6);
        input.setBackground(field);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));

        styleSend(true);
    }

    private void styleSend(boolean enabled) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(enabled ? COLOR_SEND : COLOR_SEND_DISABLED);
        send.setBackground(bg);
    }

    // ---------------------------------------------------------------- bubbles

    private TextView addBubble(boolean user, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(user ? COLOR_USER_TEXT : COLOR_BOT_TEXT);
        tv.setLineSpacing(0f, 1.12f);
        tv.setMaxWidth(maxBubbleWidth);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setTextIsSelectable(true);
        tv.setMovementMethod(LinkMovementMethod.getInstance());

        float r = dp(18);
        float s = dp(5);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(user ? COLOR_USER : COLOR_BOT);
        if (!user) {
            bg.setStroke(dp(1), COLOR_BOT_STROKE);
        }
        // Flatten the corner nearest the sender for a subtle "tail".
        // order: TL, TR, BR, BL (x,y pairs)
        if (user) {
            bg.setCornerRadii(new float[]{r, r, r, r, s, s, r, r});
        } else {
            bg.setCornerRadii(new float[]{r, r, r, r, r, r, s, s});
        }
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = user ? Gravity.END : Gravity.START;
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);

        container.addView(tv);
        scrollToBottom();
        return tv;
    }

    private void addSystemNote(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(COLOR_HINT_TEXT);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(6), dp(8), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        container.addView(tv);
    }

    private void scrollToBottom() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ---------------------------------------------------------------- sending

    private void onSend() {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        input.setText("");
        setBusy(true);
        addBubble(true, text);

        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", text);
            history.put(msg);
        } catch (Exception ignored) {
        }

        // Placeholder "typing" bubble, replaced in place when the reply lands.
        final TextView pending = addBubble(false, "…");

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
                        pending.setText(finalReply);
                        if (finalOk) {
                            try {
                                JSONObject msg = new JSONObject();
                                msg.put("role", "assistant");
                                msg.put("content", finalReply);
                                history.put(msg);
                            } catch (Exception ignored) {
                            }
                        }
                        setBusy(false);
                        scrollToBottom();
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
        send.setText(busy ? "…" : "↑");
        styleSend(!busy);
        input.setEnabled(!busy);
    }
}
