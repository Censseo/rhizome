package rhizome.vm;

import rhizome.core.box.Box;

/**
 * Resolves a data box by id for a contract's {@code box_read} host call. Backed by
 * the box processor's session-aware read, so a contract sees boxes written earlier
 * in the same block. A {@code null} reader (or a null result) means no box.
 */
@FunctionalInterface
public interface BoxReader {
    Box read(byte[] id);
}
