# 🚀 Hyper Monitor System

## Hızlı Başlangıç

### Hızlı Status Kontrolü

```bash
./scripts/quick-status.sh
```

### Hyper Monitor (Sürekli İzleme)

```bash
./scripts/hyper-monitor.sh [interval]
```

Örnek:

```bash
# 15 saniyede bir kontrol et (varsayılan)
./scripts/hyper-monitor.sh

# 30 saniyede bir kontrol et
./scripts/hyper-monitor.sh 30
```

## Özellikler

### ⚡ Hyper Hızlı

- Paralel veri toplama
- Optimize edilmiş API çağrıları
- Milisaniye seviyesinde analiz

### 🤖 Otomatik Düzeltme

- Failure tespiti
- Hata tipi analizi
- Otomatik düzeltme uygulama
- Commit ve push

### 📊 İstatistikler

- Toplam kontrol sayısı
- Başarı/başarısızlık oranları
- Düzeltme sayısı
- Çalışma süresi

### 🎯 Akıllı Analiz

- En yaygın hata tipini tespit eder
- Hata loglarını otomatik analiz eder
- Bağlamsal düzeltmeler uygular

## Kullanım Senaryoları

### Senaryo 1: Hızlı Kontrol

```bash
./scripts/quick-status.sh
```

### Senaryo 2: Sürekli İzleme

```bash
./scripts/hyper-monitor.sh 20
```

### Senaryo 3: Arka Planda Çalıştırma

```bash
nohup ./scripts/hyper-monitor.sh > monitor.log 2>&1 &
```

## Çıktı Formatı

```
╔════════════════════════════════════════════════════════════════╗
║           🚀 HYPER MONITOR SYSTEM 🚀                          ║
║         GitHub Actions Real-Time Monitor                      ║
╚════════════════════════════════════════════════════════════════╝

[14:30:15] Kontrol #1
🔄 Workflow devam ediyor...
  → Build BoringSSL (arm64-v8a)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 İSTATİSTİKLER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Toplam Kontrol: 1
Başarılı: 0
Başarısız: 0
Düzeltme Uygulandı: 0
Başarı Oranı: 0%
Çalışma Süresi: 15s
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

## Hata Tipleri ve Düzeltmeler

### Build BoringSSL

- Build verification eklenir
- Library search iyileştirilir
- Error recovery güçlendirilir

### Verify BoringSSL Artifacts

- Path düzeltmeleri uygulanır
- Alternative location search eklenir

### Clone BoringSSL

- Fallback mekanizması aktif edilir
- GitHub mirror kullanılır

## Notlar

- Sistem otomatik olarak başarılı olana kadar devam eder
- Maksimum 3 başarısız denemeden sonra durur
- Tüm değişiklikler otomatik commit edilir
- İstatistikler gerçek zamanlı güncellenir
