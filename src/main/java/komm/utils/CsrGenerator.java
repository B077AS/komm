package komm.utils;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.StringWriter;
import java.security.*;
import java.util.UUID;

@Slf4j
public class CsrGenerator {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static String generate(UUID userId, String installationName) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC", "BC");
        gen.initialize(new ECNamedCurveGenParameterSpec("P-384"));
        KeyPair keyPair = gen.generateKeyPair();

        X500Name subject = new X500Name(
                "CN=" + installationName + ", OU=" + userId + ", O=KommInstallation"
        );

        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic())
                .build(new JcaContentSignerBuilder("SHA384withECDSA").setProvider("BC").build(keyPair.getPrivate()));

        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(csr);
        }

        return sw.toString();
    }
}