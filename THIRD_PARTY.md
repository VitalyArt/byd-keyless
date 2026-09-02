# Third-party components

BYD Keyless is an unofficial client and is not affiliated with or endorsed by BYD. No project-wide license has been selected. This inventory is not a grant of rights or a licensing review.

## Bundled native libraries

The following ARM64 binaries are committed under `app/src/main/jniLibs/arm64-v8a/`. The existing project documentation identifies a reference BYD Wear OS APK as their source. Its exact package version, original download location and original license/notice files are not recorded in this repository.

| File | Evidence in this repository |
| --- | --- |
| `libJniBtLib.so` | Explicitly loaded by `BtJniUtils`; supplies the BLE JNI entry points. |
| `libRSSILocation.so` | Explicitly loaded by `BtJniUtils` alongside the BLE library. |
| `libSafeKBCrypter.so` | Bundled with the reference native library set; exact role not verified. |
| `libencrypt.so` | Bundled with the reference native library set; exact role not verified. |
| `libffavc.so` | Bundled with the reference native library set; exact role not verified. |
| `libjniutil.so` | Bundled with the reference native library set; exact role not verified. |
| `libmmkv.so` | Bundled with the reference native library set; exact upstream/version not verified. |
| `libpag.so` | Bundled with the reference native library set; exact upstream/version not verified. |
| `libwbsk_crypto_tool.so` | Bundled with the reference native library set; exact role not verified. |

All nine binaries are retained unchanged. Filenames alone do not establish their authorship, upstream project, license or redistribution terms. Redistribution rights have not been independently verified. Before redistributing, obtain the applicable permissions and original notices from the relevant rights holders; the presence of these files here does not establish permission.

The Java classes under `com.byd.aeri.projectCore.bluetooth` and `com.sign.overseas` provide compatibility names/callbacks expected by the native code. These package names do not imply that this is an official BYD application.

## Build and managed dependencies

Gradle Wrapper files are included to make the build reproducible. Android, AndroidX, Kotlin, coroutines, Nordic BLE, OkHttp, ZXing and test dependencies are declared in the Gradle build files and resolved from Google Maven or Maven Central; their source distributions are not bundled here. They remain subject to their respective upstream terms. This inventory does not replace the notices or licenses supplied with those distributions.
