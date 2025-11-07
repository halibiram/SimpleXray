# GitHub Actions workflow run'larını takip etmek için PowerShell script

Write-Host "=== GitHub Actions Workflow Monitor ===" -ForegroundColor Cyan
Write-Host ""

# GitHub CLI kontrolü
try {
    $ghVersion = gh --version 2>$null
    if (-not $ghVersion) {
        Write-Host "⚠️  GitHub CLI bulunamadı" -ForegroundColor Yellow
        Write-Host "Yükleyin: https://cli.github.com/" -ForegroundColor Yellow
        exit 1
    }
} catch {
    Write-Host "⚠️  GitHub CLI bulunamadı" -ForegroundColor Yellow
    exit 1
}

# Authentication kontrolü
try {
    $authStatus = gh auth status 2>&1
    if ($authStatus -match "not logged") {
        Write-Host "⚠️  GitHub CLI authentication gerekli" -ForegroundColor Yellow
        Write-Host "Çalıştırın: gh auth login" -ForegroundColor Yellow
        exit 1
    }
} catch {
    Write-Host "⚠️  GitHub CLI authentication gerekli" -ForegroundColor Yellow
    Write-Host "Çalıştırın: gh auth login" -ForegroundColor Yellow
    exit 1
}

# Son workflow run'ları listele
Write-Host "📋 Son Workflow Run'ları:" -ForegroundColor Blue
Write-Host ""

try {
    gh run list --limit 10 --json databaseId,status,conclusion,name,headBranch,createdAt | ConvertFrom-Json | ForEach-Object {
        $status = $_.status
        $conclusion = if ($_.conclusion) { $_.conclusion } else { "in_progress" }
        $color = switch ($conclusion) {
            "success" { "Green" }
            "failure" { "Red" }
            "cancelled" { "Yellow" }
            default { "White" }
        }
        Write-Host "$($_.databaseId) | $status | $conclusion | $($_.name) | $($_.headBranch)" -ForegroundColor $color
    }
} catch {
    Write-Host "❌ Workflow run'ları alınamadı" -ForegroundColor Red
    Write-Host "Hata: $_" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "🔍 Hangi workflow'u detaylı takip etmek istersiniz?" -ForegroundColor Blue
Write-Host "1. Build Xray-core with BoringSSL"
Write-Host "2. Auto Release"
Write-Host "3. Tüm workflow'lar"
Write-Host "4. Son çalışan workflow'u takip et"
$choice = Read-Host "Seçiminiz (1-4)"

switch ($choice) {
    "1" {
        $workflow = "Build Xray-core with BoringSSL"
    }
    "2" {
        $workflow = "Auto Release"
    }
    "3" {
        $workflow = ""
    }
    "4" {
        Write-Host ""
        Write-Host "🔄 Son workflow run'u takip ediliyor..." -ForegroundColor Green
        gh run watch
        exit 0
    }
    default {
        Write-Host "Geçersiz seçim" -ForegroundColor Red
        exit 1
    }
}

if ($workflow) {
    Write-Host ""
    Write-Host "📊 Workflow: $workflow" -ForegroundColor Blue
    Write-Host ""
    gh run list --workflow="$workflow" --limit 5
    
    Write-Host ""
    $runId = Read-Host "Son run'un detaylarını görmek için ID'yi girin (veya Enter'a basın)"
    
    if ($runId) {
        Write-Host ""
        Write-Host "📝 Run Detayları:" -ForegroundColor Green
        Write-Host ""
        gh run view $runId --log
    }
} else {
    Write-Host ""
    Write-Host "📊 Tüm Workflow'lar:" -ForegroundColor Blue
    Write-Host ""
    gh workflow list
}

Write-Host ""
Write-Host "✅ Tamamlandı" -ForegroundColor Green






