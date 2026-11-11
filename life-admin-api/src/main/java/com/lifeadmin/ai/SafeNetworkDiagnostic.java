package com.lifeadmin.ai;

import java.util.*;

/** Emits class names and fixed categories only; never returns exception messages or stack traces. */
record SafeNetworkDiagnostic(String exceptionClass, String rootCauseClass, String category) {
    static SafeNetworkDiagnostic from(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var chain = new ArrayList<Throwable>();
        Throwable current = failure;
        while (current != null && chain.size() < 64 && seen.add(current)) {
            chain.add(current);
            current = current.getCause();
        }
        String category = "NETWORK_IO_ERROR";
        // Prefer specific underlying causes over transport wrappers (e.g. SSL handshake + certificate).
        for (Throwable cause : chain) {
            if (cause instanceof java.security.cert.CertificateException
                    || cause instanceof java.security.cert.CertPathValidatorException
                    || cause instanceof java.security.cert.CertPathBuilderException) {
                category = "TLS_CERTIFICATE_ERROR"; break;
            }
            if (cause instanceof javax.net.ssl.SSLException) category = "TLS_ERROR";
        }
        if (category.equals("NETWORK_IO_ERROR")) {
            for (int i = chain.size() - 1; i >= 0; i--) {
                Throwable cause = chain.get(i);
                String candidate = classify(cause);
                if (candidate != null) { category = candidate; break; }
            }
        }
        return new SafeNetworkDiagnostic(className(failure), className(chain.getLast()), category);
    }

    private static String classify(Throwable cause) {
        if (cause instanceof java.net.UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException) return "DNS_ERROR";
        if (cause instanceof java.net.http.HttpConnectTimeoutException) return "CONNECT_TIMEOUT";
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) return "TIMEOUT";
        if (cause instanceof java.net.NoRouteToHostException) return "NO_ROUTE_TO_HOST";
        // Inspect messages only to select a constant; no substring from a message reaches logs.
        String message = cause.getMessage();
        String normalized = message == null ? "" : message.substring(0, Math.min(1024, message.length())).toLowerCase(Locale.ROOT);
        if (cause instanceof java.net.SocketException || cause instanceof java.io.IOException) {
            if (normalized.contains("connection refused")) return "CONNECTION_REFUSED";
            if (normalized.contains("connection reset")) return "CONNECTION_RESET";
            if (normalized.contains("broken pipe")) return "BROKEN_PIPE";
        }
        if (cause instanceof java.net.ConnectException) return "CONNECTION_FAILED";
        if (cause instanceof java.io.EOFException) return "CONNECTION_CLOSED";
        if (cause instanceof java.io.InterruptedIOException || cause instanceof InterruptedException) return "INTERRUPTED";
        return null;
    }

    private static String className(Throwable error) {
        String name = error.getClass().getName();
        return name.length() <= 200 && name.matches("[a-zA-Z0-9_.$]+") ? name : "OTHER_EXCEPTION";
    }
}
