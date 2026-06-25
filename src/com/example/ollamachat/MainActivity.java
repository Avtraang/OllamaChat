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

import java.util.concurrent.TimeUnit;

import io.noties.markwon.Markwon;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * Minimal Ollama chat client with real-time streaming + Markdown rendering.
 *
 * Streaming uses OkHttp's standard Server-Sent Events API (okhttp-sse,
 * {@link EventSource}) against Ollama's OpenAI-compatible endpoint
 * {@code /v1/chat/completions}, which emits a genuine {@code text/event-stream}
 * (Ollama's native {@code /api/chat} is newline-delimited JSON, not SSE).
 * Each {@code data:} chunk carries an OpenAI-style delta whose
 * {@code choices[0].delta.content} is appended live; the stream ends with
 * {@code data: [DONE]}.
 *
 * Markdown (bold, lists, code blocks, …) is rendered with Markwon. Pure
 * framework UI otherwise — no Compose, no AndroidX widgets.
 */
public class MainActivity extends Activity {

    /** Ollama server base + model. Edit these two lines to point elsewhere. */
    private static final String SERVER = "http://192.168.1.186:11434";
    private static final String MODEL = "qwen3:30b-a3b-instruct-2507-q4_K_M";

    private static final String ENDPOINT = SERVER + "/v1/chat/completions";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

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

    private OkHttpClient client;
    private Markwon markwon;

    private LinearLayout container;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private float density;
    private int maxBubbleWidth;

    // Streaming state for the in-flight assistant turn.
    private final StringBuilder current = new StringBuilder();
    private TextView pendingBubble;
    private boolean renderScheduled;

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

        markwon = Markwon.create(this);
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout while streaming
                .build();

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

        addSystemNote("Streaming from " + SERVER + "\n" + MODEL);
    }

    // ---------------------------------------------------------------- styling

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private void styleInputBar() {
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

    private TextView addBubble(boolean user) {
        TextView tv = new TextView(this);
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
        // Flatten the corner nearest the sender for a subtle "tail". order: TL,TR,BR,BL
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
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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

        TextView userBubble = addBubble(true);
        userBubble.setText(text);
        appendHistory("user", text);

        // Fresh streaming bubble for the assistant reply.
        current.setLength(0);
        pendingBubble = addBubble(false);
        pendingBubble.setText("…");

        startStream();
    }

    private void startStream() {
        String payload;
        try {
            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            body.put("messages", history);
            body.put("stream", true);
            payload = body.toString();
        } catch (Exception e) {
            finishStream("[error] " + e.getMessage(), false);
            return;
        }

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(JSON, payload))
                .build();

        EventSources.createFactory(client).newEventSource(request, new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (data == null || "[DONE]".equals(data)) {
                    return;
                }
                String delta = extractDelta(data);
                if (delta.length() > 0) {
                    synchronized (current) {
                        current.append(delta);
                    }
                    scheduleRender();
                }
            }

            @Override
            public void onClosed(EventSource es) {
                String md;
                synchronized (current) {
                    md = current.toString();
                }
                finishStream(md.length() == 0 ? "[no response]" : md, true);
            }

            @Override
            public void onFailure(EventSource es, Throwable t, Response response) {
                String detail;
                if (t != null && t.getMessage() != null) {
                    detail = t.getMessage();
                } else if (response != null) {
                    detail = "HTTP " + response.code();
                } else {
                    detail = "connection failed";
                }
                finishStream("[error] " + detail, false);
            }
        });
    }

    /** Pull choices[0].delta.content out of one OpenAI-style SSE data chunk. */
    private static String extractDelta(String data) {
        try {
            JSONObject obj = new JSONObject(data);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return "";
            }
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) {
                return "";
            }
            return delta.optString("content", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Coalesce rapid token deltas into ~20fps Markdown re-renders on the UI thread. */
    private void scheduleRender() {
        if (renderScheduled) {
            return;
        }
        renderScheduled = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderScheduled = false;
                if (pendingBubble == null) {
                    return;
                }
                String md;
                synchronized (current) {
                    md = current.toString();
                }
                markwon.setMarkdown(pendingBubble, md);
                scrollToBottom();
            }
        }, 50);
    }

    private void finishStream(final String finalText, final boolean ok) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (pendingBubble != null) {
                    if (ok) {
                        markwon.setMarkdown(pendingBubble, finalText);
                        appendHistory("assistant", finalText);
                    } else {
                        pendingBubble.setText(finalText);
                    }
                }
                pendingBubble = null;
                setBusy(false);
                scrollToBottom();
            }
        });
    }

    private void appendHistory(String role, String content) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            history.put(msg);
        } catch (Exception ignored) {
        }
    }

    private void setBusy(boolean busy) {
        send.setEnabled(!busy);
        send.setText(busy ? "…" : "↑");
        styleSend(!busy);
        input.setEnabled(!busy);
    }
}
