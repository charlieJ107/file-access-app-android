import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import java.io.File;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;

/** Verify signed APKs, then require forward-only continuity with the published APK. */
public final class VerifySigningLineage {
    private static ApkVerifier.Result verified(String path) throws Exception {
        var result = new ApkVerifier.Builder(new File(path))
                .setMinCheckedPlatformVersion(35).build().verify();
        if (!result.isVerified() || result.getSignerCertificates().size() != 1) {
            throw new IllegalArgumentException("APK must verify with exactly one current signer");
        }
        return result;
    }

    private static List<X509Certificate> history(ApkVerifier.Result result) {
        var lineage = result.getSigningCertificateLineage();
        var current = result.getSignerCertificates().get(0);
        var certificates = lineage == null ? List.of(current) : lineage.getCertificatesInLineage();
        if (certificates.isEmpty() || !certificates.get(certificates.size() - 1).equals(current)
                || new HashSet<>(certificates).size() != certificates.size()) {
            throw new IllegalArgumentException("Signing history must end at the current signer without duplicates");
        }
        return certificates;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException("Usage: VerifySigningLineage candidate.apk [previous.apk|-] [lineage]");
        }
        var candidate = verified(args[0]);
        if (!candidate.isVerifiedUsingV3Scheme() && !candidate.isVerifiedUsingV31Scheme()) {
            throw new IllegalArgumentException("Release APK requires verified v3 signing");
        }
        var candidateHistory = history(candidate);
        var lineage = candidate.getSigningCertificateLineage();
        // Keep upgrades from every historical key possible, and never authorize a reverse rotation.
        for (int index = 0; index < candidateHistory.size() - 1; index++) {
            var capabilities = lineage.getSignerCapabilities(candidateHistory.get(index));
            if (!capabilities.hasInstalledData() || capabilities.hasRollback()) {
                throw new IllegalArgumentException("Every historical signer must allow installed data and forbid rollback");
            }
        }
        if (args.length == 3) {
            var supplied = SigningCertificateLineage.readFromFile(new File(args[2]));
            if (!supplied.getCertificatesInLineage().equals(candidateHistory)) {
                throw new IllegalArgumentException("Current key must be the newest signer in the complete supplied lineage");
            }
        }
        if (args.length >= 2 && !args[1].equals("-")) {
            var previousHistory = history(verified(args[1]));
            if (candidateHistory.size() < previousHistory.size()
                    || !candidateHistory.subList(0, previousHistory.size()).equals(previousHistory)) {
                throw new IllegalArgumentException("Signing key changed without an authorized forward lineage, or history was removed");
            }
        }
        System.out.println("Verified APK signing identity and forward lineage continuity");
    }
}
