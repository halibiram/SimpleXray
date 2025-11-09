//go:build cgo
// +build cgo

package crypto

import (
	"testing"
)

// TestAES128GCMEncrypt tests AES-128-GCM encryption with BoringSSL
func TestAES128GCMEncrypt(t *testing.T) {
	key := make([]byte, 16)
	nonce := make([]byte, 12)
	plaintext := []byte("Hello, BoringSSL!")
	aad := []byte("additional data")
	
	ciphertext, tag, err := AES128GCMEncrypt(key, nonce, plaintext, aad)
	if err != nil {
		t.Fatalf("AES128GCMEncrypt failed: %v", err)
	}
	
	if len(ciphertext) == 0 {
		t.Error("ciphertext is empty")
	}
	
	if len(tag) != 16 {
		t.Errorf("tag length is %d, expected 16", len(tag))
	}
}

// TestAES256GCMEncrypt tests AES-256-GCM encryption with BoringSSL
func TestAES256GCMEncrypt(t *testing.T) {
	key := make([]byte, 32)
	nonce := make([]byte, 12)
	plaintext := []byte("Hello, BoringSSL!")
	aad := []byte("additional data")
	
	ciphertext, tag, err := AES256GCMEncrypt(key, nonce, plaintext, aad)
	if err != nil {
		t.Fatalf("AES256GCMEncrypt failed: %v", err)
	}
	
	if len(ciphertext) == 0 {
		t.Error("ciphertext is empty")
	}
	
	if len(tag) != 16 {
		t.Errorf("tag length is %d, expected 16", len(tag))
	}
}

// TestChaCha20Poly1305Encrypt tests ChaCha20-Poly1305 encryption with BoringSSL
func TestChaCha20Poly1305Encrypt(t *testing.T) {
	key := make([]byte, 32)
	nonce := make([]byte, 12)
	plaintext := []byte("Hello, BoringSSL!")
	aad := []byte("additional data")
	
	ciphertext, tag, err := ChaCha20Poly1305Encrypt(key, nonce, plaintext, aad)
	if err != nil {
		t.Fatalf("ChaCha20Poly1305Encrypt failed: %v", err)
	}
	
	if len(ciphertext) == 0 {
		t.Error("ciphertext is empty")
	}
	
	if len(tag) != 16 {
		t.Errorf("tag length is %d, expected 16", len(tag))
	}
}

// TestSHA256Hash tests SHA-256 hashing with BoringSSL
func TestSHA256Hash(t *testing.T) {
	data := []byte("Hello, BoringSSL!")
	hash := SHA256Hash(data)
	
	if len(hash) != 32 {
		t.Errorf("hash length is %d, expected 32", len(hash))
	}
	
	// Test that same input produces same hash
	hash2 := SHA256Hash(data)
	if string(hash) != string(hash2) {
		t.Error("hash is not deterministic")
	}
}

// TestSHA512Hash tests SHA-512 hashing with BoringSSL
func TestSHA512Hash(t *testing.T) {
	data := []byte("Hello, BoringSSL!")
	hash := SHA512Hash(data)
	
	if len(hash) != 64 {
		t.Errorf("hash length is %d, expected 64", len(hash))
	}
	
	// Test that same input produces same hash
	hash2 := SHA512Hash(data)
	if string(hash) != string(hash2) {
		t.Error("hash is not deterministic")
	}
}

// TestRandomBytes tests random byte generation with BoringSSL
func TestRandomBytes(t *testing.T) {
	buf1, err := RandomBytes(32)
	if err != nil {
		t.Fatalf("RandomBytes failed: %v", err)
	}
	
	if len(buf1) != 32 {
		t.Errorf("buffer length is %d, expected 32", len(buf1))
	}
	
	// Test that two calls produce different results (with high probability)
	buf2, err := RandomBytes(32)
	if err != nil {
		t.Fatalf("RandomBytes failed: %v", err)
	}
	
	if string(buf1) == string(buf2) {
		t.Error("random bytes are not random (unlikely but possible)")
	}
}

// TestBoringSSLGCM tests BoringSSLGCM AEAD implementation
func TestBoringSSLGCM(t *testing.T) {
	key := make([]byte, 16)
	aead, err := NewBoringSSLGCM(key)
	if err != nil {
		t.Fatalf("NewBoringSSLGCM failed: %v", err)
	}
	
	if aead.NonceSize() != 12 {
		t.Errorf("nonce size is %d, expected 12", aead.NonceSize())
	}
	
	if aead.Overhead() != 16 {
		t.Errorf("overhead is %d, expected 16", aead.Overhead())
	}
	
	nonce := make([]byte, 12)
	plaintext := []byte("Hello, BoringSSL!")
	aad := []byte("additional data")
	
	ciphertext := aead.Seal(nil, nonce, plaintext, aad)
	if len(ciphertext) == 0 {
		t.Error("ciphertext is empty")
	}
	
	if len(ciphertext) != len(plaintext)+aead.Overhead() {
		t.Errorf("ciphertext length is %d, expected %d", len(ciphertext), len(plaintext)+aead.Overhead())
	}
}

// BenchmarkAES128GCMEncrypt benchmarks AES-128-GCM encryption
func BenchmarkAES128GCMEncrypt(b *testing.B) {
	key := make([]byte, 16)
	nonce := make([]byte, 12)
	plaintext := make([]byte, 1024)
	aad := []byte("additional data")
	
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _, _ = AES128GCMEncrypt(key, nonce, plaintext, aad)
	}
}

// BenchmarkAES256GCMEncrypt benchmarks AES-256-GCM encryption
func BenchmarkAES256GCMEncrypt(b *testing.B) {
	key := make([]byte, 32)
	nonce := make([]byte, 12)
	plaintext := make([]byte, 1024)
	aad := []byte("additional data")
	
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _, _ = AES256GCMEncrypt(key, nonce, plaintext, aad)
	}
}

// BenchmarkChaCha20Poly1305Encrypt benchmarks ChaCha20-Poly1305 encryption
func BenchmarkChaCha20Poly1305Encrypt(b *testing.B) {
	key := make([]byte, 32)
	nonce := make([]byte, 12)
	plaintext := make([]byte, 1024)
	aad := []byte("additional data")
	
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _, _ = ChaCha20Poly1305Encrypt(key, nonce, plaintext, aad)
	}
}








