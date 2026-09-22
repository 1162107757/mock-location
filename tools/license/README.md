# Offline license issuer

This directory contains a small JDK-only issuer for offline Mock Location
licenses. The private key is never packaged into the Android app.

```powershell
javac LicenseKeyTool.java
java LicenseKeyTool init license-keys
```

Copy the printed `PUBLIC_KEY_B64` value into `LicenseManager.java` once. Keep
`license-keys/private-key.pk8` secret and back it up securely. The app accepts
the older ML1 format for migration, but new cards should use ML2.

Issue a permanent device-bound card:

```powershell
java LicenseKeyTool issue license-keys/private-key.pk8 DEVICE_CODE 0 trajectory,route,favorite
```

The same command can generate a trial card directly by using `days:N`, for
example `days:7` or `days:30`.

For a stronger device-bound card, append the `MLK2-...` device key fingerprint
shown on Mock Location's authorization page:

```powershell
java LicenseKeyTool issue license-keys/private-key.pk8 DEVICE_CODE 0 trajectory,route,favorite license-id MLK2-DEVICE_KEY_ID
```

Issue a seven-day unbound test card (the expiry is Unix epoch seconds):

```powershell
java LicenseKeyTool issue license-keys/private-key.pk8 - 1790000000 trajectory,route,favorite
```

The generated token can be pasted into the app's “卡密与授权” page. A device
bound card should use both the full device code and the `MLK2` device key
fingerprint shown by the app. ML2 also records the target package name and
rejects tokens issued in the future or for a different package/signing key.

## Android issuer APK

The companion APK is built at `dist/MockLicenseIssuer-1.0.0.apk`. It is an
offline signing tool for the owner of the app:

1. Install it on the device used to issue cards.
2. On first launch, tap “导入私钥” and select the existing
   `license-keys/private-key.pk8` file. If the device file picker hides the
   `.pk8` extension, temporarily rename the file to `.txt` without changing
   its contents. The APK checks that the key matches the public key embedded in
   Mock Location, then encrypts it with Android Keystore.
3. Enter the target device code and the optional `MLK2` device key fingerprint
   copied from Mock Location. The issuer defaults to the current device code;
   replace it with the code from a virtual machine when issuing a VM-bound card.
   Leave the fingerprint empty only for a weaker device-code-only card. Use `-`
   for an unbound test card.
4. Enter the number of validity days. Enter `0` for a permanent card, or enter
   `1`, `7`, `30`, or any other positive number to generate a manually issued
   trial card. Choose the feature permissions, tap “生成卡密”, then copy the
   token into Mock Location → “卡密与授权”.

The private key is deliberately imported at runtime and is never included in
the issuer APK. Keep both `private-key.pk8` and the issuer APK under your
control; this offline design has no server-side revocation.

Mock Location does not grant an automatic trial. Every trial card is generated
manually by this issuer and carries its own signed expiry. The app only checks
the signed card locally; no server-side revocation is available.
