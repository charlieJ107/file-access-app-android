import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/** Bind public recovery metadata to the actual verified leaf and its direct predecessor. */
public final class VerifyRotationBinding {
    private static String fingerprint(X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[1].matches("[0-9a-f]{64}") || !args[2].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Usage: VerifyRotationBinding candidate.apk previous-sha256 current-sha256");
        }
        var result = new ApkVerifier.Builder(new File(args[0])).setMinCheckedPlatformVersion(35).build().verify();
        if (!result.isVerified() || result.getSignerCertificates().size() != 1
                || result.getSigningCertificateLineage() == null) {
            throw new IllegalArgumentException("Recovery requires a verified single-signer APK with rotation history");
        }
        var certificates = result.getSigningCertificateLineage().getCertificatesInLineage();
        if (certificates.size() < 2
                || !fingerprint(result.getSignerCertificates().get(0)).equals(args[2])
                || !fingerprint(certificates.get(certificates.size() - 1)).equals(args[2])
                || !fingerprint(certificates.get(certificates.size() - 2)).equals(args[1])) {
            throw new IllegalArgumentException("Recovery request must match the verified current signer and its direct predecessor");
        }
        System.out.println("Verified prepared rotation certificate bindings");
    }
}
