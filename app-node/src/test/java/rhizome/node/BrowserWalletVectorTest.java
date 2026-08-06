package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.Contracts;
import rhizome.core.common.Utils;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.core.transaction.dto.TransactionDto;
import rhizome.crypto.SignatureScheme;

/**
 * Cross-language vectors for the dashboard's browser wallet: the wire bytes
 * below were produced by the embedded JS stack (dashboard/crypto.js + tx.js,
 * deterministic seed 0x07×32) and must parse, verify and derive identically in
 * Java. If a signing-format change breaks these, the browser wallet in
 * app-node/src/main/resources/dashboard must be updated in the same commit.
 */
class BrowserWalletVectorTest {

    private static final String JS_ADDRESS = "00057a79bbe10e1f772ec7a783a7060d30edfd53aebce17f26";
    /** Same Ed25519 key, but the address also commits to PQ_COMMITMENT — note the 0x01 version. */
    private static final String JS_PQ_ADDRESS = "015746e91d6782ac7e799408c81c5ed22df049ef613d73f28e";
    private static final String PQ_COMMITMENT =
        "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a";

    private static final String TRANSFER_WIRE =
        "0047ba68edd20bea0fba156c23d0be8cbbd40eebdd5a2223ebc8727a31862d56b0f9375cb324f9c341ee"
        + "6efd7109765c7c5af19f34807cba93b3e26b09d2cf520cea4a6c63e29c520abef5507b132ec5f9954776"
        + "aebebe7b92421eea691446d22c000001977420dc0000000000000000000000000000000000000000000"
        + "00000000100000000075bcd1500000000000000050000000002000000000000000900";

    private static final String CALL_WIRE =
        "005dd9d7ea24d182bb55586ccb63148b075bec2f95f6061235d76f43a29c544ebb29aa4580aca2704da1"
        + "4f4f39516893350d70493da28b8255654dcd595249f902ea4a6c63e29c520abef5507b132ec5f9954776"
        + "aebebe7b92421eea691446d22c000001977420dc010111111111111111111111111111111111111111"
        + "1111111111000000000000000000000000000000000000000002000000000000000a02000000000001"
        + "86a000000000000000030000001b02abababababababababababababababababababababababababcd";

    private static final String PQ_TRANSFER_WIRE =
        "01384d210d2288dc3c5d0334f3fbb47b073bf68e572d1df09b791980b9c7b8ca54ed03caf289262d2231"
        + "cc4ba19c17052ee1ed7f735bf81f0e5f4bc60105884c01ea4a6c63e29c520abef5507b132ec5f995477"
        + "6aebebe7b92421eea691446d22c5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"
        + "5a5a5a5a000001977420dc020000000000000000000000000000000000000000000000000100000000"
        + "0000002a00000000000000050000000002000000000000000b00";

    private static Transaction decode(String wireHex) {
        return Transaction.of(
            BinarySerializable.fromBuffer(Utils.hexStringToByteArray(wireHex), TransactionDto.class));
    }

    @Test
    void browserSignedTransferParsesAndVerifies() {
        Transaction t = decode(TRANSFER_WIRE);
        assertEquals(JS_ADDRESS, t.from().toHexString().toLowerCase());
        assertTrue(t.signatureValid(), "JS Ed25519 signature must verify in Java");
        assertTrue(t.senderBindingValid(), "JS-derived address must match Java's derivation");
        assertEquals("c035698f50cba6ba5b39c44972858c08dd8cf4c858e41bef57999d271a9e64ea",
            t.hashContents().toHexString().toLowerCase(), "JS txid must match Java hashContents");
    }

    @Test
    void browserSignedContractCallParsesAndVerifies() {
        Transaction t = decode(CALL_WIRE);
        assertEquals(JS_ADDRESS, t.from().toHexString().toLowerCase());
        assertEquals(TransactionKind.CALL, ((TransactionImpl) t).kind());
        assertTrue(t.signatureValid());
        assertTrue(t.senderBindingValid());
        assertEquals("228f955668d3881ae6ff937e3f62513746d8100760219e755005edc4d173a15f",
            t.hashContents().toHexString().toLowerCase());
    }

    /**
     * The post-quantum-committed scheme end to end: the browser derives a 0x01 address whose body
     * absorbs the commitment, ships the commitment on the wire, and Java recomputes the identical
     * address from (public key, scheme, commitment). This is the migration hinge — if it breaks,
     * an address created today cannot prove which post-quantum key it pre-registered.
     */
    @Test
    void browserSignedPostQuantumCommittedTransferParsesAndVerifies() {
        Transaction t = decode(PQ_TRANSFER_WIRE);
        TransactionImpl tx = (TransactionImpl) t;
        assertEquals(SignatureScheme.ED25519_PQC, tx.scheme());
        assertEquals(PQ_COMMITMENT, Utils.bytesToHex(tx.pqCommitment()).toLowerCase());
        assertEquals(JS_PQ_ADDRESS, t.from().toHexString().toLowerCase());
        assertTrue(t.signatureValid(), "JS Ed25519 signature must verify in Java");
        assertTrue(t.senderBindingValid(), "commitment must be folded into the address identically");
        assertEquals("ab1aad896c226bc9fc620e4eef7d469ff1b34501d276e90010981184c798b22a",
            t.hashContents().toHexString().toLowerCase());
    }

    /** The same key under two schemes must never derive the same address. */
    @Test
    void postQuantumCommitmentChangesTheAddress() {
        assertEquals(SignatureScheme.ED25519.code(), PublicAddress.of(JS_ADDRESS).version());
        assertEquals(SignatureScheme.ED25519_PQC.code(), PublicAddress.of(JS_PQ_ADDRESS).version());
        assertTrue(PublicAddress.of(JS_ADDRESS).isValidChecksum());
        assertTrue(PublicAddress.of(JS_PQ_ADDRESS).isValidChecksum());
    }

    @Test
    void contractAddressDerivationMatchesJs() {
        PublicAddress deployer = PublicAddress.of(JS_ADDRESS);
        assertEquals("2dd2eab2d6c07efcd13fb7070302584605749d7d8fef1765d3",
            Contracts.deriveAddress(deployer, 9).toHexString().toLowerCase(),
            "JS deriveContractAddress must match Contracts.deriveAddress");
    }
}
