package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.Contracts;
import rhizome.core.box.Box;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxRegister;
import rhizome.core.common.Utils;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.token.TokenId;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenPayload;
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
 *
 * <p>Covers every consensus-relevant derivation {@code tx.js} exports: transaction wire
 * encoding, plain and post-quantum-committed address derivation, contract-address derivation,
 * and (further down) box id, token id, and the box/token payload encoders. Each is checked
 * against its Java counterpart — a decoder where one exists, a direct re-derivation otherwise —
 * so a JS/Java encoding drift fails here instead of shipping a dashboard transaction the node
 * silently rejects or misinterprets.
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
            TransactionDto.fromBuffer(Utils.hexStringToByteArray(wireHex)));
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

    // ---- box / token vectors ----
    //
    // Of tx.js's exported functions, only the transaction wire format, plain and PQ-committed
    // address derivation, and contract-address derivation were pinned above. deriveBoxId,
    // deriveTokenId, boxSerializedSize, encodeBoxCreate/encodeBoxUpdate (register payloads) and
    // encodeTokenMint/encodeTokenAmount had no cross-language vector at all, so a JS/Java
    // encoding drift in any of them would ship silently — the dashboard would build a box or
    // token transaction the node's BoxPayload/TokenPayload decoder rejects, or worse, one it
    // accepts with a different meaning. Vectors below were produced by evaluating the real
    // dashboard/crypto.js + tx.js under QuickJS (see the memory note on verifying dashboard JS)
    // with deterministic inputs; each is checked against the corresponding Java derivation or
    // decoder, the same way the transaction vectors above are.

    private static final String JS_BOX_CREATOR = "00111111111111111111111111111111111111111111111111";
    private static final String JS_TOKEN_MINTER = "00222222222222222222222222222222222222222222222222";
    private static final long JS_DERIVE_NONCE = 7;

    private static final String JS_BOX_ID =
        "1eda02a07761e7c345a23c4185beb9ff76935fb5bacd620ed5802d1f45dae8f9";
    private static final String JS_TOKEN_ID =
        "8e0f4973bc1e2dabdf4ea3f892a5c05f554ce086ad86ef0e0df191e032b0919d";

    // BOX_CREATE payload for 5 registers: I64(1000), BOOL(true), ADDRESS(0x33...), HASH32(0x44...),
    // STRING("rhizome").
    private static final String JS_BOX_CREATE_PAYLOAD =
        "0501000800000000000003e8020001010300190033333333333333333333333333333333333333333333"
        + "333304002044444444444444444444444444444444444444444444444444444444444444440500077268"
        + "697a6f6d65";
    private static final int JS_BOX_SERIALIZED_SIZE = 170;
    private static final String JS_BOX_UPDATE_TARGET_ID =
        "5555555555555555555555555555555555555555555555555555555555555555";
    // BOX_UPDATE payload, target id above followed by one I64 register holding 42.
    private static final String JS_BOX_UPDATE_PAYLOAD =
        "555555555555555555555555555555555555555555555555555555555555555501010008000000000000002a";

    // TOKEN_MINT payload for amount 1000000, 6 decimals, symbol "RZ", name "Rhizome".
    private static final String JS_TOKEN_MINT_PAYLOAD = "00000000000f42400602525a075268697a6f6d65";
    private static final String JS_TOKEN_ID_FOR_AMOUNT =
        "6666666666666666666666666666666666666666666666666666666666666666";
    // TOKEN_TRANSFER/BURN payload, token id above followed by an amount of 500.
    private static final String JS_TOKEN_TRANSFER_PAYLOAD =
        "666666666666666666666666666666666666666666666666666666666666666600000000000001f4";

    @Test
    void browserDerivedBoxIdMatchesJava() {
        byte[] id = Box.deriveId(PublicAddress.of(JS_BOX_CREATOR), JS_DERIVE_NONCE);
        assertEquals(JS_BOX_ID, Utils.bytesToHex(id).toLowerCase(),
            "JS deriveBoxId must match Box.deriveId");
    }

    @Test
    void browserDerivedTokenIdMatchesJava() {
        TokenId id = TokenMeta.deriveId(PublicAddress.of(JS_TOKEN_MINTER), JS_DERIVE_NONCE);
        assertEquals(JS_TOKEN_ID, id.toHexString().toLowerCase(),
            "JS deriveTokenId must match TokenMeta.deriveId");
    }

    @Test
    void browserEncodedBoxCreatePayloadDecodesToTheExpectedRegisters() {
        byte[] data = Utils.hexStringToByteArray(JS_BOX_CREATE_PAYLOAD);
        BoxPayload payload = BoxPayload.decode(TransactionKind.BOX_CREATE, data, 6);
        List<BoxRegister> regs = payload.registers();
        assertEquals(5, regs.size());
        assertEquals(1000L, Utils.bytesToLong(regs.get(0).payload()));
        assertEquals((byte) 1, regs.get(1).payload()[0]);
        assertEquals("00333333333333333333333333333333333333333333333333",
            Utils.bytesToHex(regs.get(2).payload()));
        assertEquals("4444444444444444444444444444444444444444444444444444444444444444",
            Utils.bytesToHex(regs.get(3).payload()));
        assertEquals("rhizome", new String(regs.get(4).payload(), java.nio.charset.StandardCharsets.UTF_8));

        // boxSerializedSize must agree with Box.serializedSize() for the same register set —
        // it is what the dashboard shows the user as the box's storage-rent base.
        Box box = new Box(new byte[32], PublicAddress.of(JS_BOX_CREATOR), 0, 0, 0, regs);
        assertEquals(JS_BOX_SERIALIZED_SIZE, box.serializedSize(),
            "JS boxSerializedSize must match Box.serializedSize");
    }

    @Test
    void browserEncodedBoxUpdatePayloadDecodesToTheExpectedTarget() {
        byte[] data = Utils.hexStringToByteArray(JS_BOX_UPDATE_PAYLOAD);
        BoxPayload payload = BoxPayload.decode(TransactionKind.BOX_UPDATE, data, 6);
        assertEquals(JS_BOX_UPDATE_TARGET_ID, Utils.bytesToHex(payload.boxId()));
        assertEquals(1, payload.registers().size());
        assertEquals(42L, Utils.bytesToLong(payload.registers().get(0).payload()));
    }

    @Test
    void browserEncodedTokenMintPayloadDecodesToTheExpectedFields() {
        byte[] data = Utils.hexStringToByteArray(JS_TOKEN_MINT_PAYLOAD);
        TokenPayload payload = TokenPayload.decode(TransactionKind.TOKEN_MINT, data, 16, 64, 18);
        assertEquals(1_000_000L, payload.amount());
        assertEquals(6, payload.decimals());
        assertEquals("RZ", payload.symbol());
        assertEquals("Rhizome", payload.name());
    }

    @Test
    void browserEncodedTokenTransferPayloadDecodesToTheExpectedAmount() {
        byte[] data = Utils.hexStringToByteArray(JS_TOKEN_TRANSFER_PAYLOAD);
        TokenPayload payload = TokenPayload.decode(TransactionKind.TOKEN_TRANSFER, data, 16, 64, 18);
        assertEquals(JS_TOKEN_ID_FOR_AMOUNT, payload.tokenId().toHexString());
        assertEquals(500L, payload.amount());
    }
}
