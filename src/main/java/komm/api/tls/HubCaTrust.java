package komm.api.tls;

import komm.utils.AppConfig;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.UUID;

/**
 * Trust for connections to installation servers, anchored on the hub CA.
 * <p>
 * Installations serve TLS with a certificate the hub signed at activation
 * (CN = installationId). They live on bare IPs, so standard hostname
 * verification is meaningless — instead the identity check is: the certificate
 * chains to the hub CA <b>and</b> its CN equals the installation we intend to
 * reach. That is stronger than DNS identity: a hijacked IP cannot present a
 * valid certificate for someone else's installation.
 * <p>
 * The CA certificate is fetched once per app run from the hub over its normal
 * (publicly trusted) HTTPS, which makes the download tamper-proof.
 */
@Slf4j
public final class HubCaTrust {

    private static volatile X509Certificate caCertificate;

    private HubCaTrust() {
    }

    /** SSLContext for talking to one specific installation over https/wss. */
    public static SSLContext contextFor(UUID installationId) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null,
                new TrustManager[]{new HubCaTrustManager(caCertificate(), installationId)},
                new SecureRandom());
        return ctx;
    }

    private static X509Certificate caCertificate() throws Exception {
        X509Certificate cached = caCertificate;
        if (cached != null) return cached;
        synchronized (HubCaTrust.class) {
            if (caCertificate == null) {
                caCertificate = fetchCaFromHub();
            }
            return caCertificate;
        }
    }

    private static X509Certificate fetchCaFromHub() throws Exception {
        String url = AppConfig.getInstance().getApiUrl() + "/api/auth/ca";
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Hub CA fetch failed: HTTP " + response.statusCode());
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate ca = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.US_ASCII)));
        log.info("Hub CA certificate loaded: {}", ca.getSubjectX500Principal().getName());
        return ca;
    }

    private static final class HubCaTrustManager extends X509ExtendedTrustManager {

        private final X509Certificate ca;
        private final UUID expectedInstallationId;

        private HubCaTrustManager(X509Certificate ca, UUID expectedInstallationId) {
            this.ca = ca;
            this.expectedInstallationId = expectedInstallationId;
        }

        private void verifyServer(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Empty certificate chain");
            }
            X509Certificate leaf = chain[0];
            try {
                leaf.verify(ca.getPublicKey());
            } catch (Exception e) {
                throw new CertificateException("Certificate is not signed by the hub CA", e);
            }
            leaf.checkValidity();

            String cn = extractCn(leaf);
            if (cn == null || !cn.equalsIgnoreCase(expectedInstallationId.toString())) {
                throw new CertificateException(
                        "Certificate identity mismatch: CN=" + cn
                                + " but expected installation " + expectedInstallationId);
            }
        }

        private static String extractCn(X509Certificate cert) {
            String dn = cert.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                part = part.trim();
                if (part.startsWith("CN=")) {
                    return part.substring(3).trim();
                }
            }
            return null;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            verifyServer(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            verifyServer(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            verifyServer(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not supported");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("Client certificates are not supported");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("Client certificates are not supported");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[]{ca};
        }
    }
}
