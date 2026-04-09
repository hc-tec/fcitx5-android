# Function Kits (Workspace Setup)

This repo includes a **Function Kit** host/runtime integration. The Android build can bundle:

- `function-kit-runtime-sdk/dist/` (browser runtime bundle)
- curated `function-kits/*` (kit manifests + UI assets)

If the workspace is not available, the build will still succeed and bundle a **placeholder kit** (so CI and first-time contributors can build the APK).

## Recommended workspace layout

Put these repos next to each other:

```
<workspace>/
  fcitx5-android/              # this repo
  function-kits/               # kit catalog (manifests + UI)
  function-kit-runtime-sdk/    # browser runtime bundle (dist/)
  kit-studio/                  # optional: desktop devtool
```

## Configure workspace root

You can either rely on the layout above, or explicitly point the Android build to the workspace root.

### Windows (PowerShell)

Temporary (current shell):

```powershell
$env:FUNCTION_KIT_WORKSPACE_ROOT = (Resolve-Path ..).Path
```

Persistent:

```powershell
setx FUNCTION_KIT_WORKSPACE_ROOT (Resolve-Path ..).Path
```

### macOS / Linux

```bash
export FUNCTION_KIT_WORKSPACE_ROOT="$(cd .. && pwd)"
```

## Build

```bash
./gradlew :app:assembleDebug
```

If the real Function Kits are detected, debug builds bundle the available curated kits automatically.

For release builds, the APK intentionally keeps only the built-in store shell:

- `kit-store`

All other kits, including `chat-auto-reply`, `quick-phrases`, `bridge-debugger`, `ime-hooks`, `runtime-lab`, `file-upload-lab`, `tone-rewrite`, and `wx-reply`, should stay out of release and be installed through Download Center when needed.
