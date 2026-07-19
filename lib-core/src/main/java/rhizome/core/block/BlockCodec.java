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
        // Transactions are variable length (contract payloads), so sum their sizes.
        int size = BlockDto.BUFFER_SIZE;
        TransactionDto[] dtos = new TransactionDto[transactions.size()];
        for (int i = 0; i < dtos.length; i++) {
            dtos[i] = transactions.get(i).serialize();
            size += dtos[i].getSize();
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        header.writeTo(buffer);
        for (TransactionDto dto : dtos) {
            dto.writeTo(buffer);
        }
        return buffer.array();
    }

    public static Block decode(byte[] bytes) {
        return decode(ByteBuffer.wrap(bytes));
    }

    private static Block decode(ByteBuffer buffer) {
        BlockDto header = BlockDto.readFrom(buffer);

        List<Transaction> transactions = new ArrayList<>(header.numTransactions());
        for (int i = 0; i < header.numTransactions(); i++) {
            transactions.add(Transaction.of(TransactionDto.readFrom(buffer)));
        }
        return Block.of(header, transactions);
    }

    /**
     * Decodes a concatenation of blocks (each self-delimiting via its header's
     * transaction count), as served by the {@code /sync} endpoint.
     */
    public static List<Block> decodeAll(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<Block> blocks = new ArrayList<>();
        while (buffer.hasRemaining()) {
            blocks.add(decode(buffer));
        }
        return blocks;
    }
}
