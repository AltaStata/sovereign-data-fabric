/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

/**
 * HPCS PKCS#11 Key Generation and Encryption Test
 * 
 * Standalone Java application to test IBM HPCS integration.
 * 
 * Usage:
 *   javac HPCSKeyTest.java
 *   java HPCSKeyTest <pin> [public-key-pem-file]
 * 
 * Prerequisites:
 *   - /opt/hpcs/pkcs11-grep11-s390x.so installed
 *   - /etc/ep11client/grep11client.yaml configured
 *   - Key created with pkcs11-tool: 
 *       pkcs11-tool --module /opt/hpcs/pkcs11-grep11-s390x.so \
 *         --login --pin <pin> --keypairgen --key-type RSA:2048 \
 *         --label <username> --usage-sign --usage-decrypt
 *   - Public key exported:
 *       pkcs11-tool --module ... --read-object --type pubkey --label <username> -o pubkey.der
 *       openssl rsa -pubin -inform DER -in pubkey.der -outform PEM -out pubkey.pem
 */

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import java.util.Enumeration;
import javax.crypto.Cipher;

public class HPCSKeyTest {
    
    private static final String KEY_LABEL = "testuser";  // use your HSM key label (username)
    
    private static final String SEPARATOR = "======================================================================";
    
    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("HPCS PKCS#11 Sign/Verify and Encrypt/Decrypt Test");
        System.out.println(SEPARATOR);
        System.out.println();
        
        if (args.length < 1) {
            System.out.println("Usage: java HPCSKeyTest <pin> [public-key-pem-file]");
            System.out.println("  pin: The API key for HPCS authentication");
            System.out.println("  public-key-pem-file: Optional path to public key PEM file");
            System.out.println("                       (default: /tmp/pubkey.pem)");
            System.exit(1);
        }
        
        String pin = args[0];
        String pubKeyFile = args.length > 1 ? args[1] : "/tmp/pubkey.pem";
        String libraryPath = System.getProperty("pkcs11.library", "/opt/hpcs/pkcs11-grep11-s390x.so");
        
        System.out.println("Configuration:");
        System.out.println("  Library: " + libraryPath);
        System.out.println("  Key Label: " + KEY_LABEL);
        System.out.println("  Public Key File: " + pubKeyFile);
        System.out.println();
        
        try {
            // Step 1: Check library exists
            System.out.print("1. Checking PKCS#11 library... ");
            File libFile = new File(libraryPath);
            if (!libFile.exists()) {
                System.out.println("FAILED");
                System.out.println("   Error: Library not found at " + libraryPath);
                System.exit(1);
            }
            System.out.println("OK");
            
            // Step 2: Load public key from PEM file
            System.out.print("2. Loading public key from " + pubKeyFile + "... ");
            PublicKey publicKey = loadPublicKeyFromPEM(pubKeyFile);
            System.out.println("OK");
            System.out.println("   Algorithm: " + publicKey.getAlgorithm());
            System.out.println("   Format: " + publicKey.getFormat());
            
            // Step 3: Initialize PKCS#11 provider
            System.out.print("3. Initializing PKCS#11 provider... ");
            Provider pkcs11Provider = initializePKCS11(libraryPath);
            Security.addProvider(pkcs11Provider);
            System.out.println("OK (" + pkcs11Provider.getName() + ")");
            
            // Step 4: Load keystore and login
            System.out.print("4. Loading PKCS#11 keystore... ");
            KeyStore keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider);
            keyStore.load(null, pin.toCharArray());
            System.out.println("OK");
            
            // Step 5: List keys (for debugging)
            System.out.println("5. Listing keys in keystore...");
            Enumeration<String> aliases = keyStore.aliases();
            int keyCount = 0;
            PrivateKey privateKey = null;
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                System.out.println("   Found: " + alias);
                keyCount++;
                
                // Try to get the private key
                if (alias.equals(KEY_LABEL)) {
                    try {
                        privateKey = (PrivateKey) keyStore.getKey(alias, pin.toCharArray());
                        System.out.println("   -> Got private key handle for " + alias);
                    } catch (Exception e) {
                        System.out.println("   -> Could not get key: " + e.getMessage());
                    }
                }
            }
            if (keyCount == 0) {
                System.out.println("   (no keys visible in keystore - this is normal without certificates)");
            }
            
            // Step 6: Try to get private key by label directly
            if (privateKey == null) {
                System.out.print("6. Trying to get private key by label '" + KEY_LABEL + "'... ");
                try {
                    privateKey = (PrivateKey) keyStore.getKey(KEY_LABEL, pin.toCharArray());
                    if (privateKey != null) {
                        System.out.println("OK");
                    } else {
                        System.out.println("NOT FOUND");
                        System.out.println();
                        System.out.println("   The key exists (pkcs11-tool can see it) but Java's KeyStore");
                        System.out.println("   cannot access it without an associated certificate.");
                        System.out.println();
                        System.out.println("   Workaround: Using direct PKCS#11 key finding...");
                        privateKey = findPrivateKeyByLabel(pkcs11Provider, pin, KEY_LABEL);
                    }
                } catch (Exception e) {
                    System.out.println("FAILED: " + e.getMessage());
                }
            } else {
                System.out.println("6. Private key already found in step 5");
            }
            
            if (privateKey == null) {
                System.out.println();
                System.out.println("ERROR: Could not obtain private key handle.");
                System.out.println("The key exists in HPCS (pkcs11-tool can use it), but Java's SunPKCS11");
                System.out.println("requires a certificate to be associated with the key for KeyStore access.");
                System.out.println();
                System.out.println("Solutions:");
                System.out.println("1. Use keytool to generate a key with self-signed cert (may crash)");
                System.out.println("2. Use pkcs11-tool for crypto operations (proven to work)");
                System.out.println("3. Create a certificate and import it to HPCS");
                System.exit(1);
            }
            
            System.out.println();
            
            // Step 7: Test sign/verify
            String testMessage = "Hello from AltaStata HPCS Test!";
            byte[] testData = testMessage.getBytes("UTF-8");
            System.out.println("7. Testing SIGN/VERIFY:");
            System.out.println("   Message: " + testMessage);
            
            // Sign with private key (happens in HPCS!)
            System.out.print("   Signing (in HPCS)... ");
            Signature signer = Signature.getInstance("SHA256withRSA", pkcs11Provider);
            signer.initSign(privateKey);
            signer.update(testData);
            byte[] signature = signer.sign();
            System.out.println("OK (" + signature.length + " bytes)");
            System.out.println("   Signature: " + Base64.getEncoder().encodeToString(signature).substring(0, 44) + "...");
            
            // Verify with public key (done locally with loaded public key)
            System.out.print("   Verifying (local)... ");
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(testData);
            boolean signValid = verifier.verify(signature);
            System.out.println(signValid ? "OK - SIGNATURE VALID" : "FAILED");
            
            System.out.println();
            
            // Step 8: Test encrypt/decrypt
            System.out.println("8. Testing ENCRYPT/DECRYPT:");
            System.out.println("   Original: " + testMessage);
            
            // Encrypt with public key (done locally)
            System.out.print("   Encrypting (local)... ");
            Cipher encryptCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            encryptCipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = encryptCipher.doFinal(testData);
            System.out.println("OK (" + encrypted.length + " bytes)");
            
            // Decrypt with private key (happens in HPCS!)
            System.out.print("   Decrypting (in HPCS)... ");
            Cipher decryptCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", pkcs11Provider);
            decryptCipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decrypted = decryptCipher.doFinal(encrypted);
            String decryptedMessage = new String(decrypted, "UTF-8");
            boolean decryptValid = testMessage.equals(decryptedMessage);
            System.out.println(decryptValid ? "OK" : "FAILED");
            System.out.println("   Decrypted: " + decryptedMessage);
            
            // Final result
            System.out.println();
            System.out.println(SEPARATOR);
            if (signValid && decryptValid) {
                System.out.println("SUCCESS! Both sign/verify AND encrypt/decrypt work!");
                System.out.println("Private key operations happened IN THE HSM (never left HPCS).");
            } else {
                System.out.println("PARTIAL: sign=" + signValid + ", decrypt=" + decryptValid);
            }
            System.out.println(SEPARATOR);
            
            if (!signValid || !decryptValid) {
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.out.println("FAILED");
            System.out.println();
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static PublicKey loadPublicKeyFromPEM(String pemFile) throws Exception {
        // Java 8 compatible file reading
        byte[] fileBytes = Files.readAllBytes(Paths.get(pemFile));
        String pemContent = new String(fileBytes, "UTF-8");
        
        // Remove PEM headers and whitespace
        String base64 = pemContent
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(keySpec);
    }
    
    private static Provider initializePKCS11(String libraryPath) throws Exception {
        String configContent = 
            "name = HPCS-Test\n" +
            "library = " + libraryPath + "\n" +
            "slotListIndex = 0\n";
        
        File configFile = File.createTempFile("pkcs11-", ".cfg");
        configFile.deleteOnExit();
        
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(configContent);
        }
        
        // Java 9+: Use Provider.configure() method via reflection (public API)
        Provider sunPKCS11 = Security.getProvider("SunPKCS11");
        if (sunPKCS11 != null) {
            try {
                // Provider.configure(String) is public API added in Java 9
                java.lang.reflect.Method configureMethod = Provider.class.getMethod("configure", String.class);
                return (Provider) configureMethod.invoke(sunPKCS11, configFile.getAbsolutePath());
            } catch (NoSuchMethodException e) {
                // Java 8 fallback - won't work but included for compilation
                throw new RuntimeException("Java 9+ required for PKCS11 support", e);
            }
        }
        
        throw new RuntimeException("SunPKCS11 provider not available");
    }
    
    /**
     * Attempt to find a private key by label using KeyStore iteration.
     * This is a fallback method when the key doesn't appear in aliases.
     */
    private static PrivateKey findPrivateKeyByLabel(Provider provider, String pin, String label) {
        // Unfortunately, Java's SunPKCS11 KeyStore doesn't expose keys without certificates.
        // This method is a placeholder - in practice, we need the certificate association.
        System.out.println("   Note: Direct key finding not supported without certificate");
        return null;
    }
}
