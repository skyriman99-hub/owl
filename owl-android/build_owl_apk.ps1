$ErrorActionPreference = "Stop"

$JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$env:PATH"

$SDK = "C:\Users\asmar\AppData\Local\Android\Sdk"
$BUILD_TOOLS = "$SDK\build-tools\36.0.0"
$ANDROID_JAR = "$SDK\platforms\android-35\android.jar"
$KOTLINC = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
$KOTLIN_STDLIB = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\lib\kotlin-stdlib.jar"

$PROJECT_DIR = "c:\Users\asmar\OneDrive\Desktop\seal\owl-android\app\src\main"
$BUILD_DIR = "c:\Users\asmar\OneDrive\Desktop\seal\owl-android\build_direct"
$FINAL_APK = "c:\Users\asmar\OneDrive\Desktop\seal\owl.apk"

if (Test-Path $BUILD_DIR) { Remove-Item -Recurse -Force $BUILD_DIR }
New-Item -ItemType Directory -Force -Path "$BUILD_DIR\compiled_res", "$BUILD_DIR\gen", "$BUILD_DIR\classes", "$BUILD_DIR\dex" | Out-Null

Write-Host "[1/6] Compiling Android resources with aapt2..."
& "$BUILD_TOOLS\aapt2.exe" compile --dir "$PROJECT_DIR\res" -o "$BUILD_DIR\compiled_res\res.zip"

Write-Host "[2/6] Linking resources & AndroidManifest.xml with aapt2..."
& "$BUILD_TOOLS\aapt2.exe" link `
    -I $ANDROID_JAR `
    --manifest "$PROJECT_DIR\AndroidManifest.xml" `
    -A "$PROJECT_DIR\assets" `
    -o "$BUILD_DIR\owl-unaligned.apk" `
    --java "$BUILD_DIR\gen" `
    "$BUILD_DIR\compiled_res\res.zip" `
    --auto-add-overlay

Write-Host "[3/6] Compiling R.java and Kotlin sources (MainActivity.kt + OwlDatabase.kt)..."
$javaFiles = Get-ChildItem -Path "$BUILD_DIR\gen" -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
$ktFiles = @(
    "$PROJECT_DIR\java\com\owl\downloader\database\OwlDatabase.kt",
    "$PROJECT_DIR\java\com\owl\downloader\MainActivity.kt"
)

& $KOTLINC $ktFiles $javaFiles -cp $ANDROID_JAR -d "$BUILD_DIR\classes" -jvm-target 11
& "$JAVA_HOME\bin\javac.exe" -source 11 -target 11 -cp "$ANDROID_JAR;$BUILD_DIR\classes" -d "$BUILD_DIR\classes" $javaFiles

Write-Host "[4/6] Dexing bytecode + kotlin-stdlib with d8..."
$classFiles = Get-ChildItem -Path "$BUILD_DIR\classes" -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName
& "$BUILD_TOOLS\d8.bat" --lib $ANDROID_JAR --min-api 21 --output "$BUILD_DIR\dex" $classFiles $KOTLIN_STDLIB

Write-Host "[5/6] Packaging classes.dex into APK and running zipalign..."
python -c "import zipfile; z = zipfile.ZipFile(r'$BUILD_DIR\owl-unaligned.apk', 'a'); z.write(r'$BUILD_DIR\dex\classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED); z.close()"

& "$BUILD_TOOLS\zipalign.exe" -f -p 4 "$BUILD_DIR\owl-unaligned.apk" "$BUILD_DIR\owl-aligned.apk"

Write-Host "[6/6] Generating keystore and signing owl.apk with v1/v2/v3 signatures..."
$KEYSTORE = "c:\Users\asmar\OneDrive\Desktop\seal\owl-android\owl-release.jks"
if (-not (Test-Path $KEYSTORE)) {
    & "$JAVA_HOME\bin\keytool.exe" -genkeypair `
        -v -keystore $KEYSTORE `
        -alias owlkey `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass owl123456 -keypass owl123456 `
        -dname "CN=Owl Downloader, OU=Engineering, O=Owl, L=Riyadh, S=Riyadh, C=SA"
}

& "$BUILD_TOOLS\apksigner.bat" sign `
    --min-sdk-version 21 `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --ks $KEYSTORE `
    --ks-key-alias owlkey `
    --ks-pass pass:owl123456 `
    --key-pass pass:owl123456 `
    --out $FINAL_APK `
    "$BUILD_DIR\owl-aligned.apk"

& "$BUILD_TOOLS\apksigner.bat" verify --verbose $FINAL_APK
& "$BUILD_TOOLS\aapt2.exe" dump badging $FINAL_APK
Get-Item $FINAL_APK | Select-Object FullName, Length, LastWriteTime
