package org.healthtg.seed;

import com.mongodb.ConnectionString;

import java.util.List;
import java.util.Locale;

public final class DemoEnvironmentGuard {
    public static final String DATABASE = "health_tg_demo";

    private DemoEnvironmentGuard() {
    }

    public static void requireDemoEnvironment(String demoFlag, String mongoUri) {
        requireDemoEnvironment(demoFlag, mongoUri, System.getenv("HEALTH_TG_DEMO_COMPOSE"));
    }

    static void requireDemoEnvironment(String demoFlag, String mongoUri, String composeFlag) {
        if (!"true".equals(demoFlag == null ? null : demoFlag.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Demo data commands require HEALTH_TG_DEMO=true");
        }
        if (mongoUri == null || !mongoUri.startsWith("mongodb://")) {
            throw new IllegalStateException("Demo data commands require the fixed local demo MongoDB");
        }
        try {
            ConnectionString connection = new ConnectionString(mongoUri);
            List<String> hosts = connection.getHosts();
            boolean composeMode = "true".equals(composeFlag == null ? null : composeFlag.toLowerCase(Locale.ROOT));
            boolean allowedHosts = !hosts.isEmpty() && (hosts.stream().allMatch(DemoEnvironmentGuard::isLoopback)
                    || (composeMode && hosts.stream().allMatch(DemoEnvironmentGuard::isComposeMongo)));
            if (!allowedHosts || !DATABASE.equals(connection.getDatabase())) {
                throw new IllegalStateException("Demo data commands require the fixed local demo MongoDB");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Demo data commands require the fixed local demo MongoDB", exception);
        }
    }

    private static boolean isComposeMongo(String host) {
        return host.equalsIgnoreCase("mongo") || host.equalsIgnoreCase("mongo:27017");
    }

    private static boolean isLoopback(String host) {
        String hostname = host;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0 || !validPortSuffix(host.substring(end + 1))) return false;
            hostname = host.substring(1, end);
        } else {
            int colon = host.lastIndexOf(':');
            if (colon >= 0) {
                if (host.indexOf(':') != colon || !validPortSuffix(host.substring(colon))) return false;
                hostname = host.substring(0, colon);
            }
        }
        return hostname.equalsIgnoreCase("localhost") || hostname.equals("127.0.0.1")
                || hostname.equals("::1");
    }

    private static boolean validPortSuffix(String suffix) {
        return suffix.isEmpty() || suffix.matches(":[0-9]{1,5}");
    }
}
