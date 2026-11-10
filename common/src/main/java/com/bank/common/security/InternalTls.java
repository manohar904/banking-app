package com.bank.common.security;

import java.net.http.HttpClient;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

/**
 * How calls between the parts of the bank check who they are talking to. With an SSL bundle named
 * {@value #BUNDLE} (set up in compose.yaml), they trust only the stack's own certificate authority.
 * Without one, as in tests, they use Java's usual settings and plain http:// addresses work as before.
 *
 * <p>Only internal calls use this. Email and anything else on the internet keep the usual public
 * certificate authorities.
 */
@Component
public class InternalTls {

    public static final String BUNDLE = "internal";

    private final SSLContext sslContext;

    @Autowired
    public InternalTls(ObjectProvider<SslBundles> bundles) {
        this(contextFrom(bundles.getIfAvailable()));
    }

    /** null means Java's usual settings. */
    InternalTls(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    /** For code built without Spring, such as tests. */
    public static InternalTls javaDefaults() {
        return new InternalTls((SSLContext) null);
    }

    public HttpClient.Builder configure(HttpClient.Builder builder) {
        return sslContext == null ? builder : builder.sslContext(sslContext);
    }

    /** null means Java's usual settings. */
    public SSLSocketFactory socketFactory() {
        return sslContext == null ? null : sslContext.getSocketFactory();
    }

    private static SSLContext contextFrom(SslBundles bundles) {
        if (bundles == null) {
            return null;
        }
        try {
            return bundles.getBundle(BUNDLE).createSslContext();
        } catch (NoSuchSslBundleException e) {
            return null;
        }
    }
}
