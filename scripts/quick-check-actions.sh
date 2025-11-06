#!/bin/bash
echo "��� GitHub Actions Quick Check"
echo ""
echo "Son workflow run'ları:"
gh run list --limit 5 2>/dev/null || echo "⚠️  'gh auth login' çalıştırın"
echo ""
echo "Başarısız run'lar:"
gh run list --status failure --limit 3 2>/dev/null || echo "⚠️  Authentication gerekli"
