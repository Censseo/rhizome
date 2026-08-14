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
        // PrivateKey.equals is value-based AND constant time (the secret-key comparison must not
        // leak, through timing, how many leading bytes of a candidate matched). Delegating also
        // repairs this class's equals/hashCode contract: the old code compared key BYTES here
        // while hashCode() hashed the key OBJECT, whose identity equality BouncyCastle never
        // overrode — so two equal users hashed differently and a HashSet kept both.
        return Arrays.equals(publicKey().toBytes(), user.publicKey().toBytes())
            && Objects.equals(privateKey(), user.privateKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicKey, privateKey);
    }
}
