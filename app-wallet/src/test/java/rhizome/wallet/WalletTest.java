package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

class WalletTest {

    @TempDir
    Path tempDir;

    @Test
    void keyfileRoundTripPreservesIdentity() throws Exception {
        Wallet original = Wallet.create();
        Path keyfile = tempDir.resolve("wallet.json");
        original.save(keyfile, null, true); // explicit plaintext opt-in (audit S-3)

        Wallet loaded = Wallet.load(keyfile);
        assertEquals(original.address(), loaded.address());
        assertEquals(original.publicKey().toHexString(), loaded.publicKey().toHexString());
    }

    @Test
    void encryptedRoundTripWithExplicitPassphrase() throws Exception {
        // Exercises the --passphrase-file plumbing (audit F3): an explicit passphrase must seal
        // the file and unlock it again, with no env var or console involved.
        Wallet original = Wallet.create();
        Path keyfile = tempDir.resolve("wallet-encrypted.json");
        original.save(keyfile, "s3cret".toCharArray());

        Wallet loaded = Wallet.load(keyfile, "s3cret".toCharArray());
        assertEquals(original.address(), loaded.address());
        assertTrue(java.nio.file.Files.readString(keyfile).contains("rhizome-keystore"),
            "explicit passphrase must produce an encrypted envelope");
    }

    @Test
    void addressDerivesFromPublicKey() {
        Wallet wallet = Wallet.create();
        assertEquals(PublicAddress.of(wallet.publicKey()), wallet.address());
    }

    @Test
    void signedSendIsValidAndCarriesChainIdAndNonce() {
        Wallet wallet = Wallet.create();
        PublicAddress to = PublicAddress.random();

        Transaction tx = wallet.signedSend(to, new TransactionAmount(500), new TransactionAmount(1),
            7, 3, 123_456L);

        assertTrue(tx.signatureValid());
        assertEquals(wallet.address(), ((TransactionImpl) tx).from());
        assertEquals(7, ((TransactionImpl) tx).chainId());
        assertEquals(3, ((TransactionImpl) tx).nonce());
        assertEquals(500, ((TransactionImpl) tx).amount().amount());
    }

    @Test
    void plaintextSaveIsRefusedNonInteractivelyWithoutOptIn() {
        // No console and no passphrase in tests: an unguarded save would drop a spendable seed
        // on disk with only a warning nobody reads (audit S-3). It must fail closed instead.
        assumeTrue(System.console() == null, "test requires a non-interactive environment");
        assumeTrue(System.getenv("RHIZOME_WALLET_PLAINTEXT") == null,
            "test requires RHIZOME_WALLET_PLAINTEXT to be unset");
        assumeTrue(System.getenv("RHIZOME_WALLET_PASSPHRASE") == null,
            "test requires RHIZOME_WALLET_PASSPHRASE to be unset");
        Wallet wallet = Wallet.create();
        Path keyfile = tempDir.resolve("refused.json");
        assertThrows(IOException.class, () -> wallet.save(keyfile));
        assertTrue(Files.notExists(keyfile), "a refused save must not leave a key file behind");
    }

    @Test
    void keyFileIsOwnerOnlyOnPosix() throws Exception {
        // The key file must be 0600 from the moment the seed hits the disk (audit H5), on both
        // the encrypted and (explicitly opted-in) plaintext paths.
        for (String name : new String[] {"encrypted.json", "plaintext.json"}) {
            Path keyfile = tempDir.resolve(name);
            if ("encrypted.json".equals(name)) {
                Wallet.create().save(keyfile, "s3cret".toCharArray());
            } else {
                Wallet.create().save(keyfile, null, true);
            }
            final java.util.Set<java.nio.file.attribute.PosixFilePermission> perms;
            try {
                perms = Files.getPosixFilePermissions(keyfile);
            } catch (UnsupportedOperationException nonPosix) {
                assumeTrue(false, "POSIX permissions unsupported on this filesystem");
                return;
            }
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                perms, name + " must be owner-only (0600)");
        }
    }

    @Test
    void chainIdPinRoundTripsThroughPlaintextKeyFile() throws Exception {
        assumeTrue(System.console() == null, "test requires a non-interactive environment");
        Wallet wallet = Wallet.create();
        Path keyfile = tempDir.resolve("tofu.json");
        wallet.save(keyfile, null, true);

        // Backward compatible: a key file written before pinning has no pin yet.
        assertNull(Wallet.readChainIdPin(keyfile));

        // Plaintext files keep the pin in clear (the seed itself is in clear there), and no
        // passphrase is involved.
        wallet.saveChainIdPin(keyfile, 42, "http://localhost:7373", null);
        Wallet.TofuPin pin = Wallet.readChainIdPin(keyfile);
        assertEquals(42, pin.chainId());
        assertEquals("http://localhost:7373", pin.nodeUrl());

        // Pinning must not damage the key material, and load surfaces the pin.
        Wallet loaded = Wallet.load(keyfile);
        assertEquals(wallet.address(), loaded.address());
        assertEquals(42, loaded.chainIdPin().chainId());
        assertEquals("http://localhost:7373", loaded.chainIdPin().nodeUrl());
    }

    @Test
    void sealedPinIsUnreadableWithoutThePassphrase() throws Exception {
        // On an encrypted key file the pin lives INSIDE the GCM payload: the static cleartext
        // reader sees nothing, and the on-disk bytes do not expose the pin at all.
        Wallet wallet = Wallet.create();
        Path keyfile = tempDir.resolve("tofu-encrypted.json");
        wallet.save(keyfile, "s3cret".toCharArray());
        assertNull(Wallet.readChainIdPin(keyfile));

        wallet.saveChainIdPin(keyfile, 7, "http://localhost:7373", "s3cret".toCharArray());
        assertNull(Wallet.readChainIdPin(keyfile), "a sealed pin must be invisible to the cleartext reader");

        String onDisk = Files.readString(keyfile);
        assertTrue(onDisk.contains("rhizome-keystore"), "file must stay an encrypted envelope");
        assertTrue(!onDisk.contains("chainId"), "the pin must not appear in cleartext on disk");

        Wallet loaded = Wallet.load(keyfile, "s3cret".toCharArray());
        assertEquals(wallet.address(), loaded.address());
        assertEquals(7, loaded.chainIdPin().chainId());
        assertEquals("http://localhost:7373", loaded.chainIdPin().nodeUrl());
    }

    @Test
    void sealedPinUpdateWithoutPassphraseFails() throws Exception {
        assumeTrue(System.console() == null, "test requires a non-interactive environment");
        assumeTrue(System.getenv("RHIZOME_WALLET_PASSPHRASE") == null,
            "test requires RHIZOME_WALLET_PASSPHRASE to be unset");
        Wallet wallet = Wallet.create();
        Path keyfile = tempDir.resolve("tofu-nopass.json");
        wallet.save(keyfile, "s3cret".toCharArray());

        assertThrows(IOException.class,
            () -> wallet.saveChainIdPin(keyfile, 7, "http://localhost:7373", null));
        // And a WRONG passphrase must fail too (GCM authentication), leaving the file intact.
        assertThrows(RuntimeException.class,
            () -> wallet.saveChainIdPin(keyfile, 7, "http://localhost:7373", "wr0ng".toCharArray()));
        assertEquals(wallet.address(), Wallet.load(keyfile, "s3cret".toCharArray()).address());
    }

    @Test
    void tamperedEncryptedKeyFileIsDetectedOnLoadAndOnPinWrite() throws Exception {
        // Flipping one ciphertext byte must break GCM authentication: neither load nor a pin
        // update may proceed on a forged file (audit: pin forgery by file write access).
        Wallet wallet = Wallet.create();
        Path keyfile = tempDir.resolve("tofu-tampered.json");
        wallet.save(keyfile, "s3cret".toCharArray());
        wallet.saveChainIdPin(keyfile, 7, "http://localhost:7373", "s3cret".toCharArray());

        byte[] bytes = Files.readAllBytes(keyfile);
        org.json.JSONObject envelope = new org.json.JSONObject(new String(bytes));
        java.util.Arrays.fill(bytes, (byte) 0);
        String ct = envelope.getString("ct");
        // Flip one ciphertext char inside the sealed payload itself.
        envelope.put("ct", (ct.charAt(0) == 'A' ? 'B' : 'A') + ct.substring(1));
        Files.writeString(keyfile, envelope.toString(2));

        assertThrows(RuntimeException.class, () -> Wallet.load(keyfile, "s3cret".toCharArray()));
        assertThrows(RuntimeException.class,
            () -> wallet.saveChainIdPin(keyfile, 9, "http://evil:7373", "s3cret".toCharArray()));
    }

    @Test
    void legacyCleartextPinOnEncryptedFileIsReadAndMigratedIntoTheEnvelope() throws Exception {
        // Files written by the previous version carry the pin as cleartext metadata NEXT TO the
        // envelope. They must still load (verification keeps working), and the next pin write
        // must migrate the pin into the sealed payload.
        Wallet wallet = Wallet.create();
        Path keyfile = tempDir.resolve("tofu-legacy.json");
        wallet.save(keyfile, "s3cret".toCharArray());
        org.json.JSONObject legacy = new org.json.JSONObject(Files.readString(keyfile));
        legacy.put("chainId", 5);
        legacy.put("nodeUrl", "http://legacy:7373");
        Files.writeString(keyfile, legacy.toString(2));

        // Legacy read path: cleartext pin still visible, and load surfaces it too.
        Wallet.TofuPin legacyPin = Wallet.readChainIdPin(keyfile);
        assertEquals(5, legacyPin.chainId());
        Wallet loaded = Wallet.load(keyfile, "s3cret".toCharArray());
        assertEquals(5, loaded.chainIdPin().chainId());
        assertEquals("http://legacy:7373", loaded.chainIdPin().nodeUrl());

        // Migration: re-pinning seals the pin; nothing cleartext remains.
        loaded.saveChainIdPin(keyfile, 5, "http://localhost:7373", "s3cret".toCharArray());
        assertNull(Wallet.readChainIdPin(keyfile), "pin must have migrated into the envelope");
        assertTrue(!Files.readString(keyfile).contains("chainId"));
        Wallet.TofuPin sealed = Wallet.load(keyfile, "s3cret".toCharArray()).chainIdPin();
        assertEquals(5, sealed.chainId());
        assertEquals("http://localhost:7373", sealed.nodeUrl());
    }
}
