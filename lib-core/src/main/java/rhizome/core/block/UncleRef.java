package rhizome.core.block;

import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;

/**
 * A reference to an uncle block (GHOST): its hash, its difficulty, and the address
 * that mined it. The difficulty is committed in the referencing block so the uncle's
 * work ({@code 2^difficulty}) can be recomputed from the chain alone — a node that
 * restarts with an empty orphan pool still reconstructs the exact cumulative weight.
 * The miner address lets the block pay the uncle its reward. Both are checked against
 * the real orphan at admission, so neither can be forged: difficulty cannot be inflated
 * and the reward cannot be redirected.
 */
public record UncleRef(SHA256Hash hash, int difficulty, PublicAddress miner) {}
