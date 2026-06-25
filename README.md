# Ollama Chat (Android)

A deliberately minimal Android chat client for a **local Ollama** server.
No Jetpack Compose, no AndroidX/support libraries, no third-party
dependencies — just the raw Android framework.

| | |
|---|---|
| Server | `http://192.168.1.186:11434` |
| Endpoint | `/v1/chat/completions` (OpenAI-compatible, **SSE streaming**) |
| Model | `qwen3:30b-a3b-instruct-2507-q4_K_M` |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| Package | `com.example.ollamachat` |

The UI is one screen of chat bubbles, a text box, and a **Send** button. The
running conversation is kept in memory so the model has context across turns.

**Real-time streaming.** Replies stream in token-by-token over
Server-Sent Events using OkHttp's standard `EventSource` API
(`okhttp-sse`). Note this targets Ollama's **OpenAI-compatible**
`/v1/chat/completions` endpoint rather than the native `/api/chat`: the
native endpoint streams newline-delimited JSON (`application/x-ndjson`),
which is *not* SSE, whereas `/v1/chat/completions` emits a genuine
`text/event-stream` (`data: …` chunks terminated by `data: [DONE]`) that
`EventSource` consumes directly.

**Markdown.** Assistant messages are rendered with
[Markwon](https://github.com/noties/Markwon), so **bold**, lists, and
fenced code blocks format correctly as the text streams in.

### Dependencies (all from Maven Central, no Google Maven required)

| Library | Version | Purpose |
|---|---|---|
| `com.squareup.okhttp3:okhttp` + `okhttp-sse` | 3.12.13 | HTTP + SSE `EventSource` |
| `com.squareup.okio:okio` | 1.17.6 | OkHttp I/O |
| `io.noties.markwon:core` | 4.6.2 | Markdown rendering |
| `com.atlassian.commonmark:commonmark` | 0.13.0 | Markdown parser (Markwon dep) |

OkHttp 3.12.x and its deps are pure-Java JARs. Markwon ships as an AAR; the
build uses its `classes.jar` and drops the optional AndroidX-dependent
`Precomputed*TextSetterCompat` classes (never invoked). Tiny compile-time
stubs stand in for `androidx.annotation` (CLASS-retention annotations, absent
at runtime).

## Install (sideload)

The prebuilt, signed APK is committed at **`OllamaChat.apk`** (~17 KB).

1. Copy `OllamaChat.apk` to the phone (USB, Drive, etc.).
2. Open it with the Files app and allow "install from unknown sources".
3. Make sure the phone is on the **same Wi-Fi/LAN** as the machine running
   Ollama, and that Ollama is listening on the network, not just localhost:
   ```sh
   OLLAMA_HOST=0.0.0.0:11434 ollama serve
   ```
   (Cleartext HTTP is enabled in the app's manifest so it can reach the
   plain-HTTP Ollama endpoint.)

### Changing the server or model

Edit the two constants at the top of
`src/com/example/ollamachat/MainActivity.java` and rebuild:

```java
private static final String SERVER = "http://192.168.1.186:11434";
private static final String MODEL = "qwen3:30b-a3b-instruct-2507-q4_K_M";
```

## Building from source

This project builds **without the full Android SDK or Gradle**, using only
the standalone command-line build tools. On Debian/Ubuntu:

```sh
sudo apt-get install -y aapt dalvik-exchange zipalign apksigner default-jdk
./build.sh
```

`build.sh` runs the full pipeline and emits `OllamaChat.apk`:

0. fetch `android.jar` + the Maven Central libraries into `.buildtools/`
1. `aapt` — compile resources + manifest, generate `R.java`, create base APK
2. `javac --release 8` — compile app + stubs to Java-8 bytecode
3. `dalvik-exchange` (`dx`) — dex app **+ all dependency JARs** → `classes.dex`
4. `aapt add` — insert `classes.dex` into the APK
5. `zipalign` — 4-byte align
6. `apksigner` — sign (a local debug keystore is generated on first run)

All downloads land in `.buildtools/` (git-ignored) on first build.

## Project layout

```
AndroidManifest.xml                           app manifest (perms, cleartext, launcher)
res/layout/activity_main.xml                  the single-screen UI
res/values/strings.xml                        strings
res/values/styles.xml                         color theme
src/com/example/ollamachat/MainActivity.java  all app logic (SSE stream + Markdown)
stubs/androidx/annotation/*.java              compile-time annotation stubs
stubs/io/noties/markwon/R.java                stub for Markwon's unused resource R
build.sh                                      SDK-less build pipeline
OllamaChat.apk                                prebuilt signed APK
```
