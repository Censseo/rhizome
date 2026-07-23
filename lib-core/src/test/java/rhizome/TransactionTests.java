package rhizome;

import org.junit.jupiter.api.Test;

import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.dto.TransactionDto;
import rhizome.core.user.User;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTests {

    @Test
    void checkTransactionJsonSerialization() {
        User miner = User.create();
        User receiver = User.create();

        Transaction t = miner.mine();
        Transaction t2 = miner.send(receiver, 30.0);

        assertTrue(t2.signatureValid());

        // Test the send transaction
        long ts = ((TransactionImpl) t2).timestamp();
        Transaction deserialized = Transaction.of(t2.toJson());

        assertTrue(deserialized.signatureValid());
        assertEquals(t2, deserialized);
        assertEquals(ts, ((TransactionImpl) deserialized).timestamp());

        // Test mining transaction
        deserialized = Transaction.of(t.toJson());
        ts = ((TransactionImpl) t).timestamp();

        assertEquals(t.hashContents(), deserialized.hashContents());
        assertEquals(t, deserialized);
        assertEquals(ts, ((TransactionImpl) deserialized).timestamp());
    }

    @Test
    void checkTransactionStructSerialization() {
        User miner = User.create();
        User receiver = User.create();

        Transaction t = miner.mine();
        Transaction t2 = miner.send(receiver, 30.0);

        assertTrue(t2.signatureValid());

        // Test the send transaction
        long ts = ((TransactionImpl) t2).timestamp();
        TransactionDto serialized = t2.serialize();
        Transaction deserialized = Transaction.of(serialized);

        assertTrue(deserialized.signatureValid());
        assertEquals(t2, deserialized);
        assertEquals(ts, ((TransactionImpl) deserialized).timestamp());

        // Test mining transaction
        serialized = t.serialize();
        deserialized = Transaction.of(serialized);
        ts = ((TransactionImpl) t).timestamp();

        assertEquals(t.hashContents(), deserialized.hashContents());
        assertEquals(t, deserialized);
        assertEquals(ts, ((TransactionImpl) deserialized).timestamp());
    }

    @Test
    void sizeBytesMatchesTheSerializedLengthForEveryKind() {
        // P7: the block-size pre-check now sums sizeBytes() instead of building a DTO per tx via
        // serialize().getSize(). The two must be exactly equal for every kind or the block-size cap
        // check would diverge from the real wire size.
        User u = User.create();
        Transaction transfer = u.send(User.create(), 12.5);
        Transaction coinbase = u.mine();
        assertEquals(transfer.serialize().getSize(), ((TransactionImpl) transfer).sizeBytes());
        assertEquals(coinbase.serialize().getSize(), ((TransactionImpl) coinbase).sizeBytes());

        // A payload-carrying kind (CALL) with varying data lengths exercises the contract suffix.
        for (byte[] data : new byte[][] { new byte[0], new byte[1], new byte[257] }) {
            var call = TransactionImpl.builder()
                .from(rhizome.core.ledger.PublicAddress.random())
                .to(rhizome.core.ledger.PublicAddress.random())
                .signingKey(rhizome.crypto.PublicKey.empty())
                .amount(new rhizome.core.transaction.TransactionAmount(1))
                .fee(new rhizome.core.transaction.TransactionAmount(1))
                .chainId(1).nonce(0).timestamp(1L)
                .kind(rhizome.core.transaction.TransactionKind.CALL)
                .data(data).gasLimit(5).gasPrice(2).build();
            assertEquals(call.serialize().getSize(), call.sizeBytes(),
                "sizeBytes must equal the serialized length for CALL with data.length=" + data.length);
        }
    }

    @Test
    void checkTransactionCopy() {
        User miner = User.create();
        User receiver = User.create();

        Transaction t = miner.mine();
        Transaction t2 = miner.send(receiver, 30.0);

        Transaction a = Transaction.of(t);
        Transaction b = Transaction.of(t2);

        assertEquals(a, t);
        assertEquals(b, t2);
    }
}