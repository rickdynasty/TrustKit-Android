package com.datatheorem.android.trustkit.reporting;


import android.support.annotation.NonNull;
import android.util.Base64;

import com.datatheorem.android.trustkit.pinning.PinningValidationResult;
import com.datatheorem.android.trustkit.config.DomainPinningPolicy;
import com.datatheorem.android.trustkit.utils.TrustKitLog;

import java.net.URL;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


/**
 * The BackgroundReporter save a report when a pinning validation fail and send the report
 * to the specific URI.
 */
public class BackgroundReporter {

    // Main application environment information
    private final String appPackageName;
    private final String appVersion;
    private final String appVendorId;

    // Configuration and Objects managing all the operation done by the BackgroundReporter
    private final boolean shouldRateLimitsReports;

    public BackgroundReporter(boolean shouldRateLimitsReports, @NonNull String appPackageName,
                              @NonNull String appVersion, @NonNull String appVendorId) {
        this.shouldRateLimitsReports = shouldRateLimitsReports;
        this.appPackageName = appPackageName;
        this.appVersion = appVersion;
        this.appVendorId = appVendorId;
    }

    private static String certificateToPem(X509Certificate certificate) {
        byte[] certificateData;
        try {
            certificateData = certificate.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Should never happen - certificate was previously " +
                    "parsed by the system");
        }

        // Create the PEM string
        String certificateAsPem = "-----BEGIN CERTIFICATE-----\n";
        certificateAsPem += Base64.encodeToString(certificateData, Base64.DEFAULT);
        certificateAsPem += "-----END CERTIFICATE-----\n";
        return certificateAsPem;
    }

    public void pinValidationFailed(@NonNull String serverHostname,
                                    @NonNull Integer serverPort,
                                    @NonNull List<X509Certificate> servedCertificateChain,
                                    @NonNull List<X509Certificate> validatedCertificateChain,
                                    @NonNull DomainPinningPolicy serverConfig,
                                    @NonNull PinningValidationResult validationResult) {

        TrustKitLog.i("Generating pin failure report for " + serverHostname);

        // Convert the certificates to PEM strings
        ArrayList<String> validatedCertificateChainAsPem = new ArrayList<>();
        for (X509Certificate certificate : validatedCertificateChain) {
            validatedCertificateChainAsPem.add(certificateToPem(certificate));
        }
        ArrayList<String> servedCertificateChainAsPem = new ArrayList<>();
        for (X509Certificate certificate : servedCertificateChain) {
            servedCertificateChainAsPem.add(certificateToPem(certificate));
        }

        // Generate the corresponding pin failure report
        PinningFailureReport report = new PinningFailureReport(appPackageName, appVersion,
                appVendorId, serverHostname, serverPort,
                serverConfig.getHostname(), serverConfig.shouldIncludeSubdomains(),
                serverConfig.shouldEnforcePinning(), servedCertificateChainAsPem,
                validatedCertificateChainAsPem, new Date(System.currentTimeMillis()),
                serverConfig.getPublicKeyHashes(), validationResult);

        // If a similar report hasn't been sent recently, send it now
        if (!(shouldRateLimitsReports && ReportRateLimiter.shouldRateLimit(report))) {
            sendReport(report, serverConfig.getReportUris());
        } else {
            TrustKitLog.i("Report for " + serverHostname + " was not sent due to rate-limiting");
        }
    }

    protected void sendReport(@NonNull PinningFailureReport report,
                              @NonNull Set<URL> reportUriSet) {
        // Prepare the AsyncTask's arguments
        ArrayList<Object> taskParameters = new ArrayList<>();
        taskParameters.add(report);
        for (URL reportUri : reportUriSet) {
            taskParameters.add(reportUri);
        }
        // Call the task
        new BackgroundReporterTask().execute(taskParameters.toArray());
    }
}
