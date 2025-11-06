# Xray-core BoringSSL Patch Strategy

## Mevcut Durum

Şu anda Xray-core BoringSSL kütüphanelerini link ediyor ama kodda BoringSSL kullanmıyor. Gerçek bir BoringSSL entegrasyonu için:

## Seçenekler

### Seçenek 1: CGO Bridge ile BoringSSL (Önerilen)
Xray-core koduna CGO bridge ekleyerek BoringSSL'i kullanmak:

1. **Crypto Bridge**: Go crypto paketlerini BoringSSL'e yönlendir
2. **TLS Bridge**: Go crypto/tls'i BoringSSL'e yönlendir
3. **Hardware Acceleration**: AES-NI/NEON desteği

**Avantajlar:**
- Gerçek BoringSSL kullanımı
- Hardware acceleration
- Daha iyi performans

**Dezavantajlar:**
- Xray-core kodunda değişiklik gerekiyor
- Her Xray-core versiyonu için patch güncellenmeli

### Seçenek 2: Sadece Link Etme (Mevcut)
BoringSSL'i link et ama Go'nun kendi crypto'sunu kullan:

**Avantajlar:**
- Kod değişikliği yok
- Basit

**Dezavantajlar:**
- BoringSSL gerçekten kullanılmıyor
- Hardware acceleration yok

### Seçenek 3: Hybrid Approach
perf-net modülündeki BoringSSL'i kullan, Xray-core vanilla:

**Avantajlar:**
- Mevcut BoringSSL entegrasyonunu kullan
- Xray-core'da değişiklik yok

**Dezavantajlar:**
- Xray-core hala Go crypto kullanıyor

## Önerilen Yaklaşım

**Kısa vadede**: Seçenek 2 (sadece link etme) - workflow'da zaten yapıldı
**Uzun vadede**: Seçenek 1 (CGO bridge) - gerçek patch'ler oluştur

## Gerçek Patch Oluşturma Adımları

1. Xray-core'u clone et
2. Crypto paketlerine CGO bridge ekle
3. TLS implementasyonunu BoringSSL'e yönlendir
4. Patch'leri oluştur: `git diff > ../xray-patches/001-boringssl-bridge.patch`
5. Test et: `git apply --check 001-boringssl-bridge.patch`

## Notlar

- Patch'ler Xray-core versiyonuna bağlı
- Her yeni Xray-core versiyonunda patch'ler güncellenmeli
- CGO bridge karmaşık bir implementasyon gerektirir

