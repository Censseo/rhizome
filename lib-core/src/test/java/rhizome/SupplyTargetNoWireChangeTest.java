package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.HeaderCodec;
import rhizome.core.block.HeaderWire;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.SHA256Hash;
/**
 * 008-decaying-supply-target T019 (FR-019): the no-wire-change regression — the decaying target
 * lives entirely in the arithmetic, so the header wire format must be untouched:
 * {@link HeaderWire#PREFIX_BYTES} is unchanged, the header codec round-trips identically for both
 * a committing and an absent header, and (structurally) the feature adds no persisted surface —
 * proven here by the supply identity continuing to be the ONLY thing a header commits beyond the
 * pre-002 fields. The persisted-store half (no new column family) is asserted in app-node's
 * {@code DecaySurfaceGuardTest}, which can see {@code lib-persistence}.
 */
class SupplyTargetNoWireChangeTest {

    /** The 002-era prefix width: 4 header ints/longs through the committed supply. Pinned so any
     *  wire growth — even one this feature will never make — fails here first. */
    private static final int PREFIX_BYTES_002 = 160;

    @Test
    void theHeaderPrefixIsByteForByteThePreFeatureWidth() {
        assertEquals(PREFIX_BYTES_002, HeaderWire.PREFIX_BYTES,
            "the wire prefix must stay exactly the 002-era width: the decay adds arithmetic, "
                + "never bytes (FR-019)");
    }

    @Test
    void theHeaderCodecRoundTripsIdenticallyForACommittingAndAnAbsentHeader() {
        SHA256Hash hash = SHA256Hash.of(new byte[32]);

        // A committing header — supply present, vote non-zero, stateRoot set: every optional
        // field folded in, the maximal preimage — must round-trip field-identically.
        BlockHeader committing = new BlockHeader(123, 456L, 16, 1, hash, hash, hash, hash,
            2, 123456789012345L, java.util.List.of());
        BlockHeader decoded = HeaderCodec.decode(HeaderCodec.encode(committing));
        assertEquals(committing, decoded, "a committing header must round-trip identically");

        // And an absent one: SUPPLY_ABSENT survives as absent, never silently zeroed.
        BlockHeader absent = new BlockHeader(124, 457L, 16, 1, hash, hash, hash, hash,
            0, -1L, java.util.List.of());
        BlockHeader decodedAbsent = HeaderCodec.decode(HeaderCodec.encode(absent));
        assertEquals(-1L, decodedAbsent.supply(), "absent must round-trip as absent (-1)");
        assertEquals(absent, decodedAbsent);

        // The prefix writer itself emits exactly PREFIX_BYTES for either shape.
        ByteBuffer buffer = ByteBuffer.allocate(HeaderWire.PREFIX_BYTES);
        HeaderWire.writePrefix(buffer, new HeaderWire.Prefix(123, 456L, 16, 1, hash, hash, hash,
            hash, 2, 123456789012345L));
        assertEquals(0, buffer.remaining(), "the prefix fill must consume exactly PREFIX_BYTES");
    }

    @Test
    void theShippedProfilesDispatchExactlyWhatTheirOwnScheduleStates() {
        // The cheap behavioural face of FR-019's "stayed inside the arithmetic": each shipped
        // profile's dispatched reward equals an independently built peak-curve evaluation at its
        // own live target — bit-for-bit the pre-008 value for mainnet and devnet below their
        // decay start (their decay is scheduled at 126 144 000, far above every reachable test
        // height, so the short-circuit governs there), and the geometric value on testnet.
        for (NetworkParameters params : new NetworkParameters[] {
                NetworkParameters.cleanMainnet(), NetworkParameters.testnet(),
                NetworkParameters.devnet()}) {
            var peakCurve = rhizome.core.blockchain.EmissionCurve.build(params.supplyTarget(),
                params.emissionCoefficient(), params.emissionTableSteps());
            long[] supplies = {0, 1, params.genesisSupply() > 0 ? params.genesisSupply() : 5_000_000L};
            for (long height : new long[] {1, 2, 1_000}) {
                for (long supply : supplies) {
                    long dispatched = params.miningReward(height, supply);
                    if (params.emissionCurveActiveAt(height)) {
                        assertEquals(Math.max(params.minerRevenueFloor(),
                                peakCurve.raw(supply, params.supplyTargetAt(height))),
                            dispatched,
                            params.networkName() + " at height " + height + ", supply " + supply
                                + ": the pre-decay dispatch must equal the pre-008 peak evaluation");
                    } else {
                        assertEquals(params.miningReward(height), dispatched,
                            params.networkName() + " at height " + height + ": below curve "
                                + "activation the geometric value governs unchanged");
                    }
                }
            }
        }
        // The decay-active fixture diverges past its own start height, and mainnet schedules the
        // decay while testnet and devnet are explicitly at the sentinel (T045, WI-9).
        assertEquals(126_144_000L, NetworkParameters.cleanMainnet().decayStartHeight());
        assertEquals(0L, NetworkParameters.testnet().decayStartHeight(),
            "testnet explicitly never schedules the decay");
        assertEquals(0L, NetworkParameters.devnet().decayStartHeight(),
            "devnet explicitly never schedules the decay");
        NetworkParameters decay = CurveActiveNetwork.decayActiveTestnet();
        assertEquals(1L, decay.emissionCurveHeight());
        assertTrue(decay.supplyTargetSchedule().isScheduled());
    }
}
