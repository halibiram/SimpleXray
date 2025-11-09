# BoringSSL Tam Entegrasyon TODO Listesi

## 📋 Genel Bakış

Bu dokümantasyon, Xray-core'un BoringSSL'i gerçekten kullanması için gereken tüm adımları içerir. Şu anda BoringSSL kütüphaneleri link edilmiş durumda, ancak Xray-core hala Go'nun crypto/tls paketini kullanıyor.

## 🎯 Mevcut Durum

- ✅ BoringSSL kütüphaneleri build ediliyor (arm64-v8a, x86_64)
- ✅ BoringSSL static library'ler Xray-core binary'sine link ediliyor
- ✅ Binary boyutu 30MB (BoringSSL kodunun dahil olduğunu gösteriyor)
- ❌ Xray-core BoringSSL'i aktif olarak kullanmıyor
- ❌ Go linker kullanılmayan BoringSSL sembollerini atıyor
- ❌ Hardware acceleration (AES-NI/NEON) aktif değil

## 📝 TODO Listesi

### Faz 1: CGO Bridge Altyapısı

#### 1.1 Xray-core CGO Bridge Dosyası Oluştur

- [ ] `Xray-core/crypto/boringssl_bridge.go` dosyası oluştur
- [ ] BoringSSL header dosyalarını include et (`#include <openssl/ssl.h>`, `#include <openssl/crypto.h>`)
- [ ] CGO build tag'leri ekle (`//go:build cgo`)
- [ ] BoringSSL fonksiyon wrapper'ları oluştur
- [ ] Error handling ve memory management ekle

**Dosya Yapısı:**

```go
//go:build cgo
// +build cgo

package crypto

/*
#cgo CFLAGS: -I${SRCDIR}/../../boringssl/include
#cgo LDFLAGS: -L${SRCDIR}/../../boringssl/lib -lcrypto -lssl
#include <openssl/ssl.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
*/
import "C"
```

#### 1.2 Temel Crypto Fonksiyonları

- [ ] `AES128GCMEncrypt()` - BoringSSL EVP_aes_128_gcm kullanımı
- [ ] `AES256GCMEncrypt()` - BoringSSL EVP_aes_256_gcm kullanımı
- [ ] `ChaCha20Poly1305Encrypt()` - BoringSSL EVP_chacha20_poly1305 kullanımı
- [ ] `SHA256Hash()` - BoringSSL EVP_sha256 kullanımı
- [ ] `SHA512Hash()` - BoringSSL EVP_sha512 kullanımı
- [ ] `RandomBytes()` - BoringSSL RAND_bytes kullanımı

#### 1.3 TLS Handshake Bridge

- [ ] `TLS13Handshake()` - BoringSSL SSL_do_handshake wrapper
- [ ] `TLS13ClientHello()` - BoringSSL client hello oluşturma
- [ ] `TLS13ServerHello()` - BoringSSL server hello işleme
- [ ] `TLS13KeyExchange()` - BoringSSL key exchange işlemleri

### Faz 2: Xray-core Patch'leri

#### 2.1 Crypto Paketi Patch'i

- [ ] Xray-core'u clone et ve versiyon belirle
- [ ] `crypto/cipher` paketini incele
- [ ] Go'nun `crypto/cipher` kullanımlarını BoringSSL bridge'e yönlendir
- [ ] Patch dosyası oluştur: `002-crypto-boringssl.patch`

**Hedef Dosyalar:**

- `crypto/cipher/gcm.go` - AES-GCM implementasyonu
- `crypto/cipher/chacha20poly1305.go` - ChaCha20-Poly1305 implementasyonu
- `crypto/aes/aes.go` - AES cipher implementasyonu

#### 2.2 TLS Paketi Patch'i

- [ ] `crypto/tls` paketini incele
- [ ] `crypto/tls/handshake_client.go` - Client handshake'i BoringSSL'e yönlendir
- [ ] `crypto/tls/handshake_server.go` - Server handshake'i BoringSSL'e yönlendir
- [ ] `crypto/tls/conn.go` - TLS connection'ı BoringSSL'e yönlendir
- [ ] Patch dosyası oluştur: `003-tls-boringssl.patch`

#### 2.3 X.509 Certificate Patch'i

- [ ] `crypto/x509` paketini incele
- [ ] Certificate verification'ı BoringSSL'e yönlendir
- [ ] X509 certificate parsing'i BoringSSL'e yönlendir
- [ ] Patch dosyası oluştur: `004-x509-boringssl.patch`

### Faz 3: Hardware Acceleration

#### 3.1 AES-NI Desteği (x86_64)

- [ ] CPU feature detection ekle (`__builtin_cpu_supports("aes")`)
- [ ] AES-NI optimized fonksiyonları kullan
- [ ] Fallback mekanizması ekle (software implementation)

#### 3.2 NEON Desteği (ARM64)

- [ ] ARMv8 crypto extension detection ekle
- [ ] NEON optimized fonksiyonları kullan
- [ ] Fallback mekanizması ekle

#### 3.3 Performance Benchmarking

- [ ] BoringSSL vs Go crypto performans karşılaştırması
- [ ] Hardware acceleration etkisini ölç
- [ ] Memory usage karşılaştırması

### Faz 4: Build Workflow Güncellemeleri

#### 4.1 Patch Uygulama İyileştirmeleri

- [ ] Patch uygulama hata yönetimini iyileştir
- [ ] Patch versiyon uyumluluğu kontrolü ekle
- [ ] Patch başarısız olursa fallback mekanizması

#### 4.2 Build Verification

- [ ] BoringSSL fonksiyonlarının gerçekten kullanıldığını doğrula
- [ ] Binary'de BoringSSL sembollerinin varlığını kontrol et
- [ ] Runtime test'leri ekle (eğer mümkünse)

#### 4.3 CI/CD Entegrasyonu

- [ ] Patch test workflow'u oluştur
- [ ] Otomatik patch uygulama testi
- [ ] Build başarısızlık durumunda bildirim

### Faz 5: Testing ve Doğrulama

#### 5.1 Unit Testler

- [ ] BoringSSL bridge fonksiyonları için unit testler
- [ ] Crypto fonksiyon doğruluk testleri
- [ ] Error handling testleri

#### 5.2 Integration Testler

- [ ] TLS handshake testleri
- [ ] Certificate verification testleri
- [ ] Cipher suite testleri

#### 5.3 Performance Testleri

- [ ] Encryption/decryption throughput testleri
- [ ] TLS handshake latency testleri
- [ ] Memory usage profiling

### Faz 6: Dokümantasyon

#### 6.1 Kod Dokümantasyonu

- [ ] CGO bridge fonksiyonları için GoDoc yorumları
- [ ] Patch'ler için açıklayıcı yorumlar
- [ ] Build süreci dokümantasyonu

#### 6.2 Kullanım Dokümantasyonu

- [ ] BoringSSL entegrasyonu nasıl çalışır
- [ ] Patch uygulama adımları
- [ ] Troubleshooting rehberi

## 🔧 Teknik Detaylar

### CGO Bridge Örnek Kodu

```go
//go:build cgo
// +build cgo

package crypto

/*
#cgo CFLAGS: -I${SRCDIR}/../../boringssl/include
#cgo LDFLAGS: -L${SRCDIR}/../../boringssl/lib -lcrypto -lssl
#include <openssl/evp.h>
#include <openssl/aes.h>
#include <openssl/err.h>
*/
import "C"
import (
    "unsafe"
)

// AES128GCMEncrypt encrypts data using AES-128-GCM with BoringSSL
func AES128GCMEncrypt(key, nonce, plaintext, aad []byte) ([]byte, []byte, error) {
    // BoringSSL EVP_* API kullanımı
    // ...
}
```

### Patch Oluşturma Adımları

1. Xray-core'u clone et:

```bash
git clone https://github.com/XTLS/Xray-core.git
cd Xray-core
git checkout v25.10.15  # veya güncel versiyon
```

2. Değişiklikleri yap:

```bash
# CGO bridge dosyası ekle
# Crypto paketlerini değiştir
# TLS paketlerini değiştir
```

3. Patch oluştur:

```bash
git add .
git commit -m "WIP: Add BoringSSL CGO bridge"
git format-patch -1 HEAD
cp 0001-*.patch ../SimpleXray/xray-patches/001-boringssl-bridge.patch
```

4. Patch'i test et:

```bash
cd Xray-core
git checkout v25.10.15
git apply --check ../SimpleXray/xray-patches/001-boringssl-bridge.patch
```

## ⚠️ Önemli Notlar

1. **Versiyon Bağımlılığı**: Patch'ler Xray-core versiyonuna bağlıdır. Her yeni versiyonda patch'ler güncellenmelidir.

2. **CGO Karmaşıklığı**: CGO bridge implementasyonu karmaşıktır ve dikkatli memory management gerektirir.

3. **Test Gereksinimleri**: Her patch değişikliği kapsamlı testlerle doğrulanmalıdır.

4. **Fallback Mekanizması**: Patch başarısız olursa, vanilla Xray-core build'i devam etmelidir.

5. **Performance Trade-offs**: BoringSSL kullanımı performans artışı sağlar ancak binary boyutunu artırır.

## 📊 Öncelik Sırası

1. **Yüksek Öncelik**: CGO bridge altyapısı (Faz 1)
2. **Orta Öncelik**: Crypto paketi patch'leri (Faz 2.1)
3. **Orta Öncelik**: TLS paketi patch'leri (Faz 2.2)
4. **Düşük Öncelik**: Hardware acceleration (Faz 3)
5. **Düşük Öncelik**: X.509 patch'leri (Faz 2.3)

## 🚀 Hızlı Başlangıç

En hızlı sonuç için:

1. CGO bridge dosyası oluştur (Faz 1.1)
2. Temel crypto fonksiyonlarını implement et (Faz 1.2)
3. Xray-core crypto paketini patch'le (Faz 2.1)
4. Test et ve doğrula (Faz 5)

## 📚 Referanslar

- [BoringSSL API Documentation](https://commondatastorage.googleapis.com/chromium-boringssl-docs/headers.html)
- [Go CGO Documentation](https://pkg.go.dev/cmd/cgo)
- [Xray-core Source Code](https://github.com/XTLS/Xray-core)







