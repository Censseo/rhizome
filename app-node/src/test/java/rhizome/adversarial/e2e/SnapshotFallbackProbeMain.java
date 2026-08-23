package rhizome.adversarial.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;

/**
 * Forked-process probe for {@link E2EGenesisSnapshotFallbackTest} (Build I6): exercises
 * {@code SnapshotLoader.fromResource}'s {@code getContentLengthLong() == -1} fallback branch --
 * the one T025 (the feature's post-implementation review) flagged as untested, since
 * {@code fromResource(String)} takes no {@link URLConnection} injection seam and a real classpath
 * resource ({@code file:}/{@code jar:}) always reports a real length.
 *
 * <p>Closes that gap WITHOUT a JVM-global {@link java.net.URL#setURLStreamHandlerFactory}: a
 * {@link URLStreamHandler} can be attached directly to one {@link URL} instance (the five-arg
 * {@code URL} constructor), which is enough to fabricate a "declared length unknown" resource --
 * but {@code SnapshotLoader.fromResource} does not take a {@code URL}, it calls
 * {@code SnapshotLoader.class.getClassLoader().getResource(resourcePath)} internally. So this
 * class loads a SECOND, throwaway copy of {@code SnapshotLoader} through a purpose-built
 * {@link ClassLoader} whose {@code getResource} recognises one magic resource name and returns
 * our fabricated {@link URL} -- every other class {@code SnapshotLoader} touches
 * ({@code LedgerSnapshot}, {@code org.json.JSONObject}, ...) still resolves through the normal
 * parent classloader, so there is exactly one shadowed class and no risk of two incompatible
 * copies of anything this probe actually inspects (it never touches the returned
 * {@code LedgerSnapshot}'s fields -- success is "no exception").
 *
 * <p>Run as a separate process (not inline in the test) specifically to control {@code -Xmx}:
 * the whole point is observing this fallback branch's memory behaviour under a small, constrained
 * heap, which must be a property of the JVM this code runs in, not of the test runner's.
 *
 * <p>Args: {@code <paddingBytes>}. Prints exactly one {@code RESULT:...} line to stdout.
 */
public final class SnapshotFallbackProbeMain {

    private static final String RESOURCE_NAME = "snapshot-fallback-probe.json";
    private static final byte[] PREFIX = ("{\"version\":1,\"source\":\"probe\",\"sourceHeight\":0,"
        + "\"chainId\":1,\"balances\":{},\"pad\":\"").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SUFFIX = "\"}".getBytes(StandardCharsets.US_ASCII);

    private SnapshotFallbackProbeMain() {
    }

    public static void main(String[] args) {
        long padding = Long.parseLong(args[0]);

        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) {
                return new URLConnection(u) {
                    @Override public void connect() {
                    }

                    @Override public long getContentLengthLong() {
                        return -1; // the exact fallback condition SnapshotLoader.fromResource guards
                    }

                    @Override public InputStream getInputStream() {
                        return new SyntheticInputStream(padding);
                    }
                };
            }
        };
        URL probeUrl;
        try {
            probeUrl = new URL("probe", "synthetic", -1, "/" + RESOURCE_NAME, handler);
        } catch (java.net.MalformedURLException e) {
            throw new AssertionError(e);
        }

        ClassLoader parent = SnapshotFallbackProbeMain.class.getClassLoader();
        ClassLoader shadow = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("rhizome.core.ledger.SnapshotLoader")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            c = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String internal = name.replace('.', '/') + ".class";
                try (InputStream in = getParent().getResourceAsStream(internal)) {
                    if (in == null) {
                        throw new ClassNotFoundException(name);
                    }
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }

            @Override
            public URL getResource(String name) {
                if (name.equals(RESOURCE_NAME)) {
                    return probeUrl;
                }
                return super.getResource(name);
            }
        };

        try {
            Class<?> snapshotLoaderClass = Class.forName(
                "rhizome.core.ledger.SnapshotLoader", true, shadow);
            Method fromResource = snapshotLoaderClass.getDeclaredMethod("fromResource", String.class);
            fromResource.setAccessible(true);
            fromResource.invoke(null, RESOURCE_NAME);
            System.out.println("RESULT:SUCCESS");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                System.out.println("RESULT:IOEXCEPTION:" + cause.getMessage());
            } else if (cause instanceof OutOfMemoryError) {
                System.out.println("RESULT:OOM:" + cause.getMessage());
            } else {
                System.out.println("RESULT:UNEXPECTED:" + cause);
            }
        } catch (OutOfMemoryError e) {
            System.out.println("RESULT:OOM:" + e.getMessage());
        } catch (Throwable t) {
            System.out.println("RESULT:UNEXPECTED:" + t);
        }
    }

    /** Streams {@code PREFIX + <padding> 'a' bytes + SUFFIX} lazily, so generating the probe
     *  resource never itself needs to hold the whole body in memory -- only the consumer
     *  ({@code SnapshotLoader.fromResource}'s fallback branch) does, which is the point. */
    private static final class SyntheticInputStream extends InputStream {
        private final long padding;
        private long emitted = 0;
        private int prefixPos = 0;
        private int suffixPos = 0;
        private boolean prefixDone = false;
        private boolean paddingDone = false;

        SyntheticInputStream(long padding) {
            this.padding = padding;
        }

        @Override
        public int read() {
            if (!prefixDone) {
                if (prefixPos < PREFIX.length) {
                    return PREFIX[prefixPos++] & 0xFF;
                }
                prefixDone = true;
            }
            if (!paddingDone) {
                if (emitted < padding) {
                    emitted++;
                    return 'a';
                }
                paddingDone = true;
            }
            if (suffixPos < SUFFIX.length) {
                return SUFFIX[suffixPos++] & 0xFF;
            }
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int count = 0;
            while (count < len) {
                int c = read();
                if (c == -1) {
                    return count == 0 ? -1 : count;
                }
                b[off + count] = (byte) c;
                count++;
            }
            return count;
        }
    }
}
