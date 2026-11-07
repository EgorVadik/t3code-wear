# T3 Code for Wear OS

A read-only companion for Wear OS watches (Galaxy Watch 4 and newer). It shows the threads of every
environment the phone app is connected to, each thread's conversation, and provider usage limits.
It does not send messages or answer approvals.

## How it connects

The watch never connects to an environment. It shows what the phone app sees.

1. `apps/mobile/src/features/wear/wearSync.ts` publishes the phone's environments, and whether it
   is connected to each, over the Wearable Data Layer (`apps/mobile/modules/t3-wear-bridge`).
2. For threads and usage the watch sends the phone a request. The phone makes the same read over
   its own connection, whichever route that uses (LAN, Tailscale or T3 Connect), and sends back
   the environment's response, gzipped; large ones stream over a channel.
3. While a watch is paired, the phone runs a foreground service with a "Syncing to your watch"
   notification and a headless JS task, so it keeps answering with the app in the background.
   Samsung phones may still stop it unless the app's battery use is set to Unrestricted.

Removing or disabling an environment on the phone removes it from the watch.

## Build and install

The Data Layer only connects apps with the same package name and signing certificate, so the watch
app must match the phone build it pairs with:

| Phone variant | Watch flavor  | Package                      |
| ------------- | ------------- | ---------------------------- |
| development   | `development` | `com.t3tools.t3code.dev`     |
| preview       | `preview`     | `com.t3tools.t3code.preview` |
| production    | `production`  | `com.t3tools.t3code`         |

Debug builds sign with `apps/mobile/android/app/debug.keystore` when it exists, which is what
`expo prebuild` debug phone builds use. For phone builds signed elsewhere, such as EAS, pass the
same keystore:

```sh
./gradlew :assembleDevelopmentRelease \
  -Pt3.wear.storeFile=/path/to/keystore.jks -Pt3.wear.storePassword=… \
  -Pt3.wear.keyAlias=… -Pt3.wear.keyPassword=…
```

The same values can come from `T3_WEAR_STOREFILE`, `T3_WEAR_STOREPASSWORD`, `T3_WEAR_KEYALIAS`
and `T3_WEAR_KEYPASSWORD`.

Install on a watch with wireless debugging enabled (Settings → Developer options). Release
builds run noticeably smoother on a watch than debug builds:

```sh
adb connect <watch-ip>:<port>
adb -s <watch-ip>:<port> install build/outputs/apk/development/release/t3code-wear-development-release.apk
```

## Tests

```sh
./gradlew :testDevelopmentDebugUnitTest
```
