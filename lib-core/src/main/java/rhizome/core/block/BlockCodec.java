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
        List<rhizome.core.crypto.SHA256Hash> uncles = block.uncles();
        // Transactions are variable length (contract payloads), so sum their sizes.
        int size = BlockDto.BUFFER_SIZE;
        TransactionDto[] dtos = new TransactionDto[transactions.size()];
        for (int i = 0; i < dtos.length; i++) {
            dtos[i] = transactions.get(i).serialize();
            size += dtos[i].getSize();
        }
        size += Integer.BYTES + uncles.size() * rhizome.core.crypto.SHA256Hash.SIZE;

        ByteBuffer buffer = ByteBuffer.allocate(size);
        header.writeTo(buffer);
        for (TransactionDto dto : dtos) {
            dto.writeTo(buffer);
        }
        buffer.putInt(uncles.size());
        for (rhizome.core.crypto.SHA256Hash uncle : uncles) {
            buffer.put(uncle.hash().getArray());
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
        List<rhizome.core.crypto.SHA256Hash> uncles = new ArrayList<>();
        int numUncles = buffer.getInt();
        for (int i = 0; i < numUncles; i++) {
            byte[] h = new byte[rhizome.core.crypto.SHA256Hash.SIZE];
            buffer.get(h);
            uncles.add(rhizome.core.crypto.SHA256Hash.of(h));
        }
        return Block.of(header, transactions, uncles);
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
