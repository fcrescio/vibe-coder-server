# Android Vibe Demo

Minimal Android project used to smoke-test `vibe-coder-server` with the
`mistral-vibe-acp` runtime.

Build from a container or host that has JDK 17 and Android SDK 35:

```bash
gradle :app:assembleDebug
```

In the server UI, register this directory as an existing project and send a
small prompt through the console, for example:

```text
Change the demo title color and build a debug APK.
```
