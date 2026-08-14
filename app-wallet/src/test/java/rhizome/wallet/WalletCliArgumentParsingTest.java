package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L21's real net: one argument-parsing case per command that takes a typed value.
 *
 * <p>{@link WalletCliDispatchTest} pins the shape of the dispatch table — that every command is
 * bound to its own arity gate. That is not the same property as this one. Arity says "you gave me
 * four words"; this says "the third word is not an address", and the two fail in different places
 * for different reasons. Only 4 test files covered 17 commands when the dispatch table was
 * introduced, so the value parsers — the ones that stop a mistyped amount, an over-long symbol or
 * a truncating decimals count from being SIGNED and only bounced by the node — had no per-command
 * coverage at all.
 *
 * <p>Every case here is offline. The node URL points at a closed port, and each assertion is that
 * the parser throws BEFORE anything is sent: if a parse ever silently widened, the command would
 * reach the client and fail with a {@code WalletException} instead, which is a failure of this
 * test rather than a pass. The commands that load the wallet before parsing get a real key file,
 * so the failure under test is the parse and never a missing file.
 */
class WalletCliArgumentParsingTest {

    /** A closed port: reached only if a parser wrongly accepted, and then it refuses immediately. */
    private static final String NODE = "http://127.0.0.1:1";

    @TempDir
    Path dir;

    private String keyfile;
    private String address;
    private String wasmfile;

    @BeforeEach
    void createWallet() throws Exception {
        keyfile = dir.resolve("w.key").toString();
        assertEquals(0, WalletCli.dispatch(new String[] {"keygen", keyfile, "--plaintext"}));
        address = Wallet.load(Path.of(keyfile), null).address().toHexString();
        wasmfile = dir.resolve("c.wasm").toString();
        Files.write(Path.of(wasmfile), new byte[] {0x00, 0x61, 0x73, 0x6d});
    }

    /** Runs a command that must fail while parsing, and returns the message it failed with. */
    private String parseFailure(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> WalletCli.dispatch(args),
            "expected " + args[0] + " to refuse its argument before any network call").getMessage();
    }

    private void assertRefused(String expectedFragment, String... args) {
        String message = parseFailure(args);
        assertTrue(message.contains(expectedFragment),
            args[0] + ": expected a message containing \"" + expectedFragment + "\", got: " + message);
    }

    // ---- send: recipient checksum and PDN amounts ----

    @Test
    void sendRefusesARecipientWhoseChecksumDoesNotMatch() {
        // One flipped character in an address is the difference between paying someone and
        // burning the amount: the checksum is what catches it, before the transaction is signed.
        String typo = "00" + "F".repeat(46) + "AB";
        assertRefused("checksum", "send", NODE, keyfile, typo, "1.0");
    }

    @Test
    void sendRefusesANonNumericAmount() {
        assertRefused("invalid PDN amount", "send", NODE, keyfile, address, "ten");
    }

    @Test
    void sendRefusesAnAmountFinerThanOneBaseUnit() {
        // Truncating this silently would send a different amount than the operator typed.
        assertRefused("precision", "send", NODE, keyfile, address, "1.000000001");
    }

    @Test
    void sendRefusesANegativeFee() {
        assertRefused("invalid PDN amount", "send", NODE, keyfile, address, "1.0", "-1.0");
    }

    // ---- deploy / call: the gas pair ----

    @Test
    void deployRefusesANonPositiveGasLimit() {
        // A negative gas value used to be signed here and only rejected by the node.
        assertRefused("gasLimit", "deploy", NODE, keyfile, wasmfile, "-1", "1");
    }

    @Test
    void deployRefusesAGasLimitAboveTheProtocolCeiling() {
        assertRefused("gasLimit", "deploy", NODE, keyfile, wasmfile, "99999999999", "1");
    }

    @Test
    void callRefusesAGasPriceThatWouldOverflowTheFeeProduct() {
        assertRefused("gasPrice", "call", NODE, keyfile, address, "", "10000", "999999999999999");
    }

    // ---- data boxes ----

    @Test
    void boxCreateRefusesANonNumericValue() {
        assertRefused("invalid PDN amount", "box-create", NODE, keyfile, "lots");
    }

    @Test
    void boxCreateRefusesAMalformedRegisterSpec() {
        assertRefused("register must be", "box-create", NODE, keyfile, "1.0", "--reg", "novalue");
    }

    @Test
    void boxCreateRefusesAnUnknownRegisterType() {
        assertRefused("unknown register type", "box-create", NODE, keyfile, "1.0", "--reg", "colour:7");
    }

    @Test
    void boxShowRefusesAnIdOfTheWrongLength() {
        assertRefused("64 hex characters", "box-show", NODE, "abcd");
    }

    @Test
    void boxShowRefusesANonHexId() {
        // An unvalidated id used to flow straight into the node's query string, where a '&'
        // injected extra parameters (audit BAS-2).
        assertRefused("not hex", "box-show", NODE, "z".repeat(64));
    }

    // ---- native tokens ----

    @Test
    void tokenMintRefusesAnEmptySymbol() {
        assertRefused("token symbol", "token-mint", NODE, keyfile, "", "Name", "100", "2");
    }

    @Test
    void tokenMintRefusesASymbolOverTheByteLimit() {
        // Bounded in UTF-8 BYTES, not characters: the payload's single length byte is what wraps.
        assertRefused("token symbol", "token-mint", NODE, keyfile, "É".repeat(9), "Name", "100", "2");
    }

    @Test
    void tokenMintRefusesANameOverTheByteLimit() {
        assertRefused("token name", "token-mint", NODE, keyfile, "SYM", "N".repeat(65), "100", "2");
    }

    @Test
    void tokenMintRefusesAZeroAmount() {
        assertRefused("token amount", "token-mint", NODE, keyfile, "SYM", "Name", "0", "2");
    }

    @Test
    void tokenMintRefusesDecimalsOutsideTheProtocolRange() {
        // encodeMint narrows decimals with a (byte) cast: out of range used to truncate into a
        // different token than the one asked for.
        assertRefused("decimals", "token-mint", NODE, keyfile, "SYM", "Name", "100", "19");
    }

    @Test
    void tokenTransferRefusesAMalformedTokenId() {
        assertRefused("tokenId", "token-transfer", NODE, keyfile, "beef", address, "5");
    }

    @Test
    void tokenTransferRefusesARecipientWhoseChecksumDoesNotMatch() {
        String typo = "00" + "F".repeat(46) + "AB";
        assertRefused("checksum", "token-transfer", NODE, keyfile, "a".repeat(64), typo, "5");
    }

    @Test
    void tokenBurnRefusesANegativeAmount() {
        assertRefused("token amount", "token-burn", NODE, keyfile, "a".repeat(64), "-5");
    }

    @Test
    void tokenShowRefusesAMalformedTokenId() {
        assertRefused("tokenId", "token-show", NODE, "not-a-token-id");
    }

    @Test
    void tokenBalanceRefusesAMalformedTokenId() {
        assertRefused("tokenId", "token-balance", NODE, "beef", address);
    }
}
