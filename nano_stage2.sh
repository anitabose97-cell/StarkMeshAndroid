#!/usr/bin/env bash
set -e

ROOT="$HOME/StarkMeshAndroid"
JAVA_DIR="$ROOT/app/src/main/java/com/starkmesh/app"
XML_DIR="$ROOT/app/src/main/res/xml"
WF_DIR="$ROOT/.github/workflows"

mkdir -p "$JAVA_DIR" "$XML_DIR" "$WF_DIR" "$ROOT/app/libs" "$ROOT/app/src/main/assets/kws"

FILES=(
  "$ROOT/settings.gradle.kts"
  "$ROOT/build.gradle.kts"
  "$ROOT/gradle.properties"
  "$ROOT/app/build.gradle.kts"
  "$ROOT/app/src/main/AndroidManifest.xml"
  "$XML_DIR/accessibility_service_config.xml"
  "$JAVA_DIR/VaultManager.kt"
  "$JAVA_DIR/NeuralVoiceEngine.kt"
  "$JAVA_DIR/WakeWordDetector.kt"
  "$JAVA_DIR/StarkAccessibilityService.kt"
  "$JAVA_DIR/CameraWellnessHelper.kt"
  "$JAVA_DIR/StarkAiClient.kt"
  "$JAVA_DIR/StarkCallScreeningService.kt"
  "$JAVA_DIR/StarkRelayService.kt"
  "$JAVA_DIR/BootReceiver.kt"
  "$JAVA_DIR/MainActivity.kt"
  "$WF_DIR/build_apk.yml"
)

echo "Ready to edit ${#FILES[@]} Stage 2 files in nano."
echo "In nano: paste/type content -> Ctrl+O -> Enter -> Ctrl+X"
echo ""

for f in "${FILES[@]}"; do
  read -r -p "Open $f in nano? [Y/n/q] " ans
  ans="${ans:-Y}"
  case "$ans" in
    q|Q) break ;;
    n|N) continue ;;
    *) nano "$f" ;;
  esac
done

echo "Done. Listing created files:"
find "$ROOT" -maxdepth 5 -type f | sort
