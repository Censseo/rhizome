package rhizome.core.user;

import java.util.Arrays;
import java.util.Objects;

import org.json.JSONObject;

import lombok.Builder;
import lombok.Data;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;

@Data
@Builder
public class UserImpl implements User {
    private PublicKey publicKey;
    private PrivateKey privateKey;

    @Override
    public JSONObject toJson() {
        return toJson(this);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof User)) return false;
        User user = (User) o;
        // Compare the secret key in constant time: Arrays.equals short-circuits on the first differing
        // byte, which leaks (via timing) how many leading bytes of a candidate private key match — the
        // one place raw secret-key bytes are compared (audit hygiene). MessageDigest.isEqual is the JDK's
        // constant-time byte-array comparison.
        return Arrays.equals(publicKey().toBytes(), user.publicKey().toBytes()) &&
            java.security.MessageDigest.isEqual(
                privateKey().key().getEncoded(), user.privateKey().key().getEncoded());
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicKey, privateKey);
    }
}
