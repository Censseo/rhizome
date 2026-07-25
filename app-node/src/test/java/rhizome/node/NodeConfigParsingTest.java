package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Environment config parsing guards (audit: unvalidated config): numeric env vars must fail
 * fast with a clear message, and the miner vote must be bounded to the protocol's vote domain,
 * instead of dying with a raw NumberFormatException or minting INVALID_VOTE blocks.
 */
class NodeConfigParsingTest {

    @Test
    void voteParsesInsideTheProtocolDomain() {
        assertEquals(0, RhizomeNode.parseVote("0"));
        assertEquals(1, RhizomeNode.parseVote(" 1 "));
        assertEquals(-2, RhizomeNode.parseVote("-2"));
        assertEquals(2, RhizomeNode.parseVote("2"));
    }

    @Test
    void voteRejectsJunkAndOutOfDomainValues() {
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parseVote("yes"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parseVote("3"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parseVote("-3"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parseVote("99999999999"));
    }

    @Test
    void portParsesAndIsRangeChecked() {
        assertEquals(3000, RhizomeNode.parsePort("3000"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parsePort("abc"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parsePort("0"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parsePort("70000"));
    }

    @Test
    void blockIntervalParsesAndMustBePositive() {
        assertEquals(1000L, RhizomeNode.parseBlockIntervalMs("1000"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parseBlockIntervalMs("fast"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parseBlockIntervalMs("0"));
        assertThrows(IllegalArgumentException.class, () -> RhizomeNode.parseBlockIntervalMs("-100"));
    }

    @Test
    void stateRetentionMustCoverTheReorgDepth() {
        // retainDepth < maxReorgDepth: a reorg past the retained roots could not rebuild the
        // state tree — fail fast at the wiring site (audit: retainDepth below maxReorgDepth).
        RhizomeNode.checkStateRetention(64, 64);
        RhizomeNode.checkStateRetention(128, 64);
        assertThrows(IllegalStateException.class, () -> RhizomeNode.checkStateRetention(63, 64));
    }
}
