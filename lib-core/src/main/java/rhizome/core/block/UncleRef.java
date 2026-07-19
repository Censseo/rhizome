package rhizome.core.block;

import rhizome.core.crypto.SHA256Hash;

/**
 * A reference to an uncle block (GHOST): its hash plus its difficulty. The
 * difficulty is committed in the referencing block so the uncle's work
 * (2^difficulty) can be recomputed from the chain alone — a node that restarts
 * with an empty orphan pool still reconstructs the exact cumulative weight. At
 * add time the committed difficulty is checked against the real orphan, so it
 * cannot be inflated.
 */
public record UncleRef(SHA256Hash hash, int difficulty) {}
