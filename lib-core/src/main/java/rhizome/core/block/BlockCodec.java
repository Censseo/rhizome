package rhizome.core.block;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.dto.BlockDto;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.dto.TransactionDto;

/**
 * Fixed-layout codec for a whole block (header + transactions), reusing the
 * per-object {@link BlockDto}/{@link TransactionDto} codecs:
 * {@code header(BlockDto.BUFFER_SIZE) || tx[0] || tx[1] || ...}, where the
 * transaction count is carried in the header. Used to store a full block as a
 * single value and to frame blocks on the wire.
 */
public final class BlockCodec {

    private BlockCodec() {}

    public static byte[] encode(Block block) {
        BlockDto header = block.serialize();
        List<Transaction> transactions = block.transactions();
        int size = BlockDto.BUFFER_SIZE + transactions.size() * TransactionDto.BUFFER_SIZE;

        ByteBuffer buffer = ByteBuffer.allocate(size);
        header.writeTo(buffer);
        for (Transaction t : transactions) {
            t.serialize().writeTo(buffer);
        }
        return buffer.array();
    }

    public static Block decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        BlockDto header = BlockDto.readFrom(buffer);

        List<Transaction> transactions = new ArrayList<>(header.numTransactions());
        for (int i = 0; i < header.numTransactions(); i++) {
            transactions.add(Transaction.of(TransactionDto.readFrom(buffer)));
        }
        return Block.of(header, transactions);
    }
}
