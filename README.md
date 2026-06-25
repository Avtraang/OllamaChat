# Ollama Chat (Android)

A deliberately minimal Android chat client for a **local Ollama** server.
No Jetpack Compose, no AndroidX/support libraries, no third-party
dependencies — just the raw Android framework.

| | |
|---|---|
| Server | `http://192.168.1.186:11434/api/chat` |
| Model | `qwen3:30b-a3b-instruct-2507-q4_K_M` |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| Package | `com.example.ollamachat` |

The UI is one screen: a scrolling conversation view, a text box, and a
**Send** button. The running conversation is kept in memory and sent on each
turn (via Ollama's `/api/chat`), so the model has context across messages.

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
private static final String SERVER_URL = "http://192.168.1.186:11434/api/chat";
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

1. `aapt` — compile resources + manifest, generate `R.java`, create base APK
2. `javac --release 8` — compile sources to Java-8 bytecode
3. `dalvik-exchange` (`dx`) — convert `.class` → `classes.dex`
4. `aapt add` — insert `classes.dex` into the APK
5. `zipalign` — 4-byte align
6. `apksigner` — sign (a local debug keystore is generated on first run)

`android.jar` (compile-time framework stubs) is fetched automatically into
`.buildtools/` on first build.

## Project layout

```
AndroidManifest.xml                         app manifest (perms, cleartext, launcher)
res/layout/activity_main.xml                the single-screen UI
res/values/strings.xml                      strings
src/com/example/ollamachat/MainActivity.java  all app logic
build.sh                                    SDK-less build pipeline
OllamaChat.apk                              prebuilt signed APK
```
