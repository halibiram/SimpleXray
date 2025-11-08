/*
 * Hardware-Accelerated Crypto (Rust Implementation)
 * Uses ring for hardware-accelerated crypto operations
 */

use ring::aead;
use log::info;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CryptoAlgorithm {
    Aes128Gcm,
    Aes256Gcm,
    ChaCha20Poly1305,
}

#[derive(Clone, Debug)]
pub struct CryptoCapabilities {
    pub has_aes_hardware: bool,
    pub has_pmull_hardware: bool,
    pub has_neon: bool,
    pub has_sha_hardware: bool,
    pub cpu_model: String,
}

pub struct QuicheCrypto {
    algorithm: CryptoAlgorithm,
    sealing_key: Option<aead::LessSafeKey>,
    opening_key: Option<aead::LessSafeKey>,
}

impl QuicheCrypto {
    pub fn create(algorithm: CryptoAlgorithm) -> Result<Self, Box<dyn std::error::Error>> {
        info!("Created crypto handler (algorithm={:?})", algorithm);
        Ok(Self {
            algorithm,
            sealing_key: None,
            opening_key: None,
        })
    }

    pub fn initialize(&mut self, key: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
        let aead_alg = match self.algorithm {
            CryptoAlgorithm::Aes128Gcm => &aead::AES_128_GCM,
            CryptoAlgorithm::Aes256Gcm => &aead::AES_256_GCM,
            CryptoAlgorithm::ChaCha20Poly1305 => &aead::CHACHA20_POLY1305,
        };

        let unbound_key = aead::UnboundKey::new(aead_alg, key)?;
        let less_safe_key = aead::LessSafeKey::new(unbound_key);
        
        // LessSafeKey is not Clone, so we need to create two keys from the same UnboundKey
        // Since UnboundKey is not Clone either, we need to create it twice
        let unbound_key2 = aead::UnboundKey::new(aead_alg, key)?;
        self.sealing_key = Some(less_safe_key);
        self.opening_key = Some(aead::LessSafeKey::new(unbound_key2));

        info!("Crypto initialized (key_len={})", key.len());
        Ok(())
    }

    pub fn encrypt(
        &self,
        plaintext: &[u8],
        ciphertext: &mut [u8],
        nonce: &[u8],
    ) -> Result<usize, Box<dyn std::error::Error>> {
        let sealing_key = self.sealing_key.as_ref()
            .ok_or("Crypto not initialized")?;

        if ciphertext.len() < plaintext.len() + sealing_key.algorithm().tag_len() {
            return Err("Ciphertext buffer too small".into());
        }

        // Copy plaintext to ciphertext buffer
        ciphertext[..plaintext.len()].copy_from_slice(plaintext);

        let nonce = aead::Nonce::try_assume_unique_for_key(nonce)
            .map_err(|_| "Invalid nonce length")?;

        // seal_in_place_append_tag takes a slice containing the plaintext
        // and appends the tag. The buffer must be large enough for plaintext + tag.
        // We pass a slice that includes space for the tag.
        let tag_len = sealing_key.seal_in_place_append_tag(
            nonce,
            aead::Aad::empty(),
            &mut ciphertext[..plaintext.len()],
        )?;

        Ok(plaintext.len() + tag_len)
    }

    pub fn decrypt(
        &self,
        ciphertext: &mut [u8],
        plaintext_len: usize,
        nonce: &[u8],
    ) -> Result<usize, Box<dyn std::error::Error>> {
        let opening_key = self.opening_key.as_ref()
            .ok_or("Crypto not initialized")?;

        let nonce = aead::Nonce::try_assume_unique_for_key(nonce)
            .map_err(|_| "Invalid nonce length")?;

        let plaintext = opening_key.open_in_place(
            nonce,
            aead::Aad::empty(),
            &mut ciphertext[..plaintext_len],
        )?;

        Ok(plaintext.len())
    }

    pub fn get_capabilities() -> CryptoCapabilities {
        // ring automatically uses hardware acceleration if available
        CryptoCapabilities {
            has_aes_hardware: true,  // ring uses hardware if available
            has_pmull_hardware: true,
            has_neon: cfg!(target_arch = "aarch64") || cfg!(target_arch = "arm"),
            has_sha_hardware: true,
            cpu_model: String::new(), // Would need CPUID equivalent
        }
    }

    pub fn print_capabilities() {
        let caps = Self::get_capabilities();
        info!("Crypto Capabilities:");
        info!("  AES Hardware: {}", caps.has_aes_hardware);
        info!("  PMULL Hardware: {}", caps.has_pmull_hardware);
        info!("  NEON: {}", caps.has_neon);
        info!("  SHA Hardware: {}", caps.has_sha_hardware);
    }
}




