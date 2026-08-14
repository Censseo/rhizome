package rhizome;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Helpers.PDN;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.transaction.Transaction;
import rhizome.core.user.User;

class UserTests {
        @Test
    void checkSignature() {
        BlockImpl b = Block.empty();
        
        User miner = User.create();
        User receiver = User.create();

        Transaction t = miner.mine();
        b.addTransaction(t);
        Transaction t2 = miner.send(receiver, PDN(30.0));
        b.addTransaction(t2);

        assertTrue(t2.signatureValid());
    }

    @Test
    void checkUserSerialization() {
        BlockImpl b = Block.empty();
        
        User miner = User.create();
        User receiver = User.create();

        Transaction t = miner.mine();
        b.addTransaction(t);
        Transaction t2 = miner.send(receiver, PDN(30.0));
        b.addTransaction(t2);

        assertTrue(t2.signatureValid());

        // Recreate miner from JSON, including the private key (the default toJson is
        // public-only; the private-key form is built explicitly here because the
        // private-key serializer no longer ships in the production jar).
        JSONObject serialized = new JSONObject()
            .put("publicKey", miner.publicKey().toHexString())
            .put("privateKey", miner.privateKey().toHexString());
        JSONObject parsed = new JSONObject(serialized.toString());

        User minerCopy = User.of(parsed);
        // Test the signature still works
        minerCopy.signTransaction(t2);
        assertTrue(t2.signatureValid());
    }
}
