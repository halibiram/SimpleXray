# SELinux Uyumluluk Çözümleri

## Genel Bakış

Android 16+ (API 36+) ile birlikte SELinux politikaları sıkılaştırıldı. Bu dokümantasyon, SELinux denial'larını önlemek için geliştirilen yasal ve güvenli alternatif çözümleri açıklar.

**ÖNEMLİ:** Bu çözümler SELinux'u "atlatmak" için değil, Android SDK API'lerini kullanarak SELinux uyumlu alternatifler sağlamak içindir.

---

## 🔧 Geliştirilen Çözümler

### 1. Process Kontrolü - `/proc/PID` Erişimi Yerine

**Sorun:** Android 16+ SELinux `/proc/PID` dizin erişimini kısıtlıyor.

**Çözüm:** `Process.sendSignal(pid, 0)` kullanarak process kontrolü yapılıyor.

```kotlin
// ❌ Eski yöntem (SELinux denial)
File("/proc/$pid").exists()

// ✅ Yeni yöntem (SELinux uyumlu)
Process.sendSignal(pid, 0) // Signal 0 sadece kontrol eder, sinyal göndermez
```

**Dosya:** `SelinuxComplianceHelper.kt` - `isProcessAlive()`

---

### 2. Process Bilgisi - `/proc/PID/cmdline` Yerine

**Sorun:** `/proc/PID/cmdline` erişimi Android 16+'da kısıtlı.

**Çözüm:** `Process.getUidForPid()` ve `ActivityManager.getRunningAppProcesses()` kullanılıyor.

```kotlin
// ❌ Eski yöntem
File("/proc/$pid/cmdline").readText()

// ✅ Yeni yöntem
val uid = Process.getUidForPid(pid)
val processes = activityManager.runningAppProcesses
```

**Dosya:** `SelinuxComplianceHelper.kt` - `getProcessInfo()`, `findProcessByBinaryName()`

---

### 3. System Property Erişimi - `SystemProperties` Yerine

**Sorun:** `SystemProperties.get()` Android 16+ SELinux tarafından engelleniyor.

**Çözüm:** `Build` sınıfı ve try-catch ile güvenli erişim.

```kotlin
// ❌ Eski yöntem
SystemProperties.get("ro.debuggable")

// ✅ Yeni yöntem
Build.TYPE == "eng" || Build.TYPE == "userdebug" // ro.debuggable için
// Veya try-catch ile güvenli erişim
```

**Dosya:** `SelinuxComplianceHelper.kt` - `getSystemProperty()`

---

### 4. Network İstatistikleri - `/proc/net` Yerine

**Sorun:** `/proc/net` erişimi Android 16+ SELinux tarafından engelleniyor.

**Çözüm:** `TrafficStats` ve `NetworkStatsManager` API'leri kullanılıyor.

```kotlin
// ❌ Eski yöntem
File("/proc/net/sockstat").readText()

// ✅ Yeni yöntem
TrafficStats.getUidRxBytes(uid)
NetworkStatsManager.querySummary(...)
```

**Dosya:** `SelinuxComplianceHelper.kt` - `getNetworkStats()`

---

### 5. CPU Bilgisi - `/proc/cpuinfo` Yerine

**Sorun:** `/proc/cpuinfo` erişimi Android 16+ SELinux tarafından kısıtlı.

**Çözüm:** `Runtime` ve `Build` sınıfları kullanılıyor.

```kotlin
// ❌ Eski yöntem
File("/proc/cpuinfo").readText()

// ✅ Yeni yöntem
Runtime.getRuntime().availableProcessors()
Build.SUPPORTED_ABIS
```

**Dosya:** `SelinuxComplianceHelper.kt` - `getCpuInfo()`

---

### 6. File Descriptor Sayısı - `/proc/self/fd` Yerine

**Sorun:** `/proc/self/fd` erişimi Android 16+ SELinux tarafından engelleniyor.

**Çözüm:** `Os.getrlimit()` kullanılıyor.

```kotlin
// ❌ Eski yöntem
File("/proc/self/fd").listFiles()?.size

// ✅ Yeni yöntem
Os.getrlimit(OsConstants.RLIMIT_NOFILE)
```

**Dosya:** `SelinuxComplianceHelper.kt` - `getFileDescriptorCount()`

---

### 7. Native Library Execution - `setExecutable()` Yerine

**Sorun:** Android 16+ SELinux `setExecutable()` çağrılarını engelliyor.

**Çözüm:** Native library dizinini doğrudan kullanma (zaten executable context'te).

```kotlin
// ❌ Eski yöntem
file.setExecutable(true, false)

// ✅ Yeni yöntem
// Android 14+ için native library dizinini doğrudan kullan
// app_file_exec context zaten execution izni veriyor
```

**Dosya:** `XrayCoreLauncher.kt` - `copyExecutable()`

---

## 📋 Kullanım Örnekleri

### Process Kontrolü

```kotlin
import com.simplexray.an.common.SelinuxComplianceHelper

// Process'in yaşayıp yaşamadığını kontrol et
val isAlive = SelinuxComplianceHelper.isProcessAlive(pid)

// Process bilgilerini al
val processInfo = SelinuxComplianceHelper.getProcessInfo(pid)
if (processInfo != null) {
    println("PID: ${processInfo.pid}, UID: ${processInfo.uid}, Alive: ${processInfo.isAlive}")
}

// Binary adına göre process bul
val pid = SelinuxComplianceHelper.findProcessByBinaryName(context, "libxray.so")
```

### System Property Erişimi

```kotlin
// Debug property kontrolü
val isDebuggable = SelinuxComplianceHelper.getSystemProperty("ro.debuggable") == "1"

// MIUI version kontrolü
val miuiVersion = SelinuxComplianceHelper.getSystemProperty("ro.miui.ui.version.name")
```

### Network İstatistikleri

```kotlin
val stats = SelinuxComplianceHelper.getNetworkStats(context)
if (stats != null) {
    println("RX: ${stats.rxBytes}, TX: ${stats.txBytes}")
}
```

### CPU Bilgisi

```kotlin
val cpuInfo = SelinuxComplianceHelper.getCpuInfo()
println("Processors: ${cpuInfo.availableProcessors}")
println("Architecture: ${cpuInfo.architecture}")
```

---

## 🔒 Güvenlik Notları

1. **Yasal Kullanım:** Tüm çözümler Android SDK API'lerini kullanır ve SELinux politikalarına uygundur.

2. **Fallback Mekanizmaları:** Eğer Android API erişimi başarısız olursa, güvenli fallback mekanizmaları kullanılır.

3. **Error Handling:** Tüm erişimler try-catch ile korunur ve SELinux denial'ları sessizce handle edilir.

4. **Logging:** SELinux denial'ları debug seviyesinde loglanır ancak uygulama çalışmaya devam eder.

---

## 📊 Android Versiyon Uyumluluğu

| Android Versiyonu | API Level | SELinux Sıkılığı | Çözüm Durumu |
|-------------------|-----------|------------------|--------------|
| Android 13- | API 33- | Orta | Mevcut çözümler çalışıyor |
| Android 14-15 | API 34-35 | Yüksek | Native library direkt kullanımı |
| Android 16+ | API 36+ | Çok Yüksek | Tüm alternatifler aktif |

---

## 🎯 Uygulanan Değişiklikler

### Güncellenen Dosyalar

1. **`SelinuxComplianceHelper.kt`** (YENİ)
   - SELinux uyumlu helper sınıfı
   - Android API tabanlı alternatifler
   - Process, network, system property erişimleri

2. **`TProxyService.kt`**
   - `isProcessAlive()` → `SelinuxComplianceHelper.isProcessAlive()` kullanıyor
   - Process kontrolü SELinux uyumlu hale getirildi

3. **`XrayCoreLauncher.kt`**
   - `isProcessAlive()` → `SelinuxComplianceHelper.isProcessAlive()` kullanıyor
   - Process kontrolü SELinux uyumlu hale getirildi

---

## 🚀 Sonuç

Tüm kritik SELinux denial'ları Android SDK API'leri kullanılarak çözüldü. Uygulama Android 16+ ile tam uyumlu ve SELinux denial'ları minimize edildi.

**Kalan denial'lar:** Third-party kütüphanelerden (BoringSSL, sistem kütüphaneleri) gelen denial'lar kontrol edilemez ancak uygulama işlevselliğini etkilemez.

---

## 📝 Referanslar

- [Android SELinux Documentation](https://source.android.com/docs/security/selinux)
- [Android Process API](https://developer.android.com/reference/android/os/Process)
- [Android NetworkStatsManager](https://developer.android.com/reference/android/app/usage/NetworkStatsManager)
- [Android TrafficStats](https://developer.android.com/reference/android/net/TrafficStats)

