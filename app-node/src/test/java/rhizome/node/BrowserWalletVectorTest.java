package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.Contracts;
import rhizome.core.common.Utils;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionKind;
import rhizome.core.transaction.dto.TransactionDto;

/**
 * Cross-language vectors for the dashboard's browser wallet: the wire bytes
 * below were produced by the embedded JS stack (dashboard/crypto.js + tx.js,
 * deterministic seed 0x07×32) and must parse, verify and derive identically in
 * Java. If a signing-format change breaks these, the browser wallet in
 * app-node/src/main/resources/dashboard must be updated in the same commit.
 */
class BrowserWalletVectorTest {

    private static final String JS_ADDRESS = "00057a79bbe10e1f772ec7a783a7060d30edfd53aeb3655f43";

    private static final String TRANSFER_WIRE =
        "482db45a47d701b550a176d9ee837d0a7d0ed5e5fcde6e9dc52a6462f9be152104be90e8196512b463c4"
        + "39253b44869804bf51327af8305bd5e078d1649f0f0eea4a6c63e29c520abef5507b132ec5f9954776ae"
        + "bebe7b92421eea691446d22c00000198264654000000000000000000000000000000000000000000000"
        + "000000100000000075bcd1500000000000000050000000002000000000000000900";

    private static final String CALL_WIRE =
        "ad38d805a44a2b129bec79d30c6aaf75daa8c792e71f43dccca3f3d491127d97b8e730c5d9806c1f30bc"
        + "15d296f5d3c6c81279c4d17d9b3df029a03fcd07c505ea4a6c63e29c520abef5507b132ec5f9954776ae"
        + "bebe7b92421eea691446d22c000001982646540111111111111111111111111111111111111111111111"
        + "111111000000000000000000000000000000000000000002000000000000000a0200000000000186a000"
        + "000000000000030000001a02ababababababababababababababababababababababababcd";

    @Test
    void browserSignedTransferParsesAndVerifies() {
        Transaction t = Transaction.of(
            BinarySerializable.fromBuffer(Utils.hexStringToByteArray(TRANSFER_WIRE), TransactionDto.class));
        assertEquals(JS_ADDRESS, t.from().toHexString().toLowerCase());
        assertTrue(t.signatureValid(), "JS Ed25519 signature must verify in Java");
        assertEquals("c5dcbc1e4ab328857fcb2f44f4d5fe9e15fdeebb74b5923a54f6f7aab84b2c50",
            t.hashContents().toHexString().toLowerCase(), "JS txid must match Java hashContents");
    }

    @Test
    void browserSignedContractCallParsesAndVerifies() {
        Transaction t = Transaction.of(
            BinarySerializable.fromBuffer(Utils.hexStringToByteArray(CALL_WIRE), TransactionDto.class));
        assertEquals(JS_ADDRESS, t.from().toHexString().toLowerCase());
        assertEquals(TransactionKind.CALL, ((rhizome.core.transaction.TransactionImpl) t).kind());
        assertTrue(t.signatureValid());
        assertEquals("f8b1d8de00f95a3db2208b0bb3053936967b96fa4499038322353851cd09b5d5",
            t.hashContents().toHexString().toLowerCase());
    }

    @Test
    void contractAddressDerivationMatchesJs() {
        PublicAddress deployer = PublicAddress.of(JS_ADDRESS);
        assertEquals("f84e1bf3e719eab1fa48e132587a7a6ddc79581c574e56618c",
            Contracts.deriveAddress(deployer, 9).toHexString().toLowerCase(),
            "JS deriveContractAddress must match Contracts.deriveAddress");
    }
}
