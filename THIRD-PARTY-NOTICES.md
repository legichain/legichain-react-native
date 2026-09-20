# Third-party components

Legichain SDK source is MIT licensed. Dependencies retain their own licenses;
they are resolved by the platform package manager and are not relicensed.

| Component | Version | License / corresponding source |
| --- | --- | --- |
| JMRTD | 0.7.42 | LGPL-2.1-or-later, https://repo.maven.apache.org/maven2/org/jmrtd/jmrtd/0.7.42/jmrtd-0.7.42-sources.jar |
| Scuba SC Android | 0.0.23 | LGPL-2.1-or-later, https://repo.maven.apache.org/maven2/net/sf/scuba/scuba-sc-android/0.0.23/scuba-sc-android-0.0.23-sources.jar |
| OpenCV Android | 4.13.0 | Apache-2.0, https://github.com/opencv/opencv/tree/4.13.0 |
| NFCPassportReader | 2.3.1 | MIT, https://github.com/AndyQ/NFCPassportReader/tree/2.3.1 |
| CameraX / AndroidX | see Gradle | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ |
| ML Kit text / face | 16.0.0 / 16.1.7 | https://developers.google.com/ml-kit/terms |
| OkHttp | 4.12.0 | Apache-2.0, https://github.com/square/okhttp/tree/parent-4.12.0 |
| Kotlin coroutines | 1.8.1 | Apache-2.0, https://github.com/Kotlin/kotlinx.coroutines/tree/1.8.1 |

Host app distributors must preserve applicable dependency notices and source /
relinking obligations, including the LGPL components. No JMRTD or Scuba source
modifications were made. Keep these as replaceable dependencies rather than
embedding an undocumented modified copy. See dependency packages for transitive
cryptographic component notices (Bouncy Castle / OpenSSL).
