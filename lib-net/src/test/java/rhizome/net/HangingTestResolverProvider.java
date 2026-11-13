package rhizome.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Test-JVM resolver: names ending in {@code .hang.invalid} block until interrupted, as a resolver
 * that never answers would; every other lookup goes to the JDK's built-in resolver unchanged.
 * Registered through {@code META-INF/services} on the lib-net test classpath only.
 */
public final class HangingTestResolverProvider extends InetAddressResolverProvider {

    static final String HANG_SUFFIX = ".hang.invalid";

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                if (host.endsWith(HANG_SUFFIX)) {
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new UnknownHostException(host);
                }
                return builtin.lookupByName(host, policy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "rhizome-hanging-test-resolver";
    }
}
