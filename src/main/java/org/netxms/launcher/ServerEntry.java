package org.netxms.launcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerEntry(String address, int port, String lastLogin, String lastSeenVersion, String lastUsed) {
    public static final int DEFAULT_PORT = 4701;

    public ServerEntry {
        address = Objects.requireNonNull(address, "address").trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1).trim();
        }
        if (address.isEmpty()) {
            throw new IllegalArgumentException("server address must not be empty");
        }
        if (port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (port <= 0) {
            port = DEFAULT_PORT;
        }
    }

    public static ServerEntry of(String address, int port) {
        return new ServerEntry(address, port, null, null, null);
    }

    public static String compactAuthority(String host, int port) {
        return (port == DEFAULT_PORT) ? bracketedHost(host) : authority(host, port);
    }

    private static String authority(String host, int port) {
        return bracketedHost(host) + ":" + port;
    }

    private static String bracketedHost(String host) {
        return isIpv6Literal(host) ? ("[" + host + "]") : host;
    }

    private static boolean isIpv6Literal(String host) {
        int colon = host.indexOf(':');
        return (colon >= 0) && (host.indexOf(':', colon + 1) >= 0);
    }

    public ServerEntry withLastLogin(String login) {
        return new ServerEntry(address, port, login, lastSeenVersion, lastUsed);
    }

    public ServerEntry withLastSeenVersion(String version) {
        return new ServerEntry(address, port, lastLogin, version, lastUsed);
    }

    public ServerEntry withLastUsed(Instant when) {
        return new ServerEntry(address, port, lastLogin, lastSeenVersion, (when != null) ? when.toString() : null);
    }

    public boolean matches(String address, int port) {
        return this.address.equalsIgnoreCase((address != null) ? address.trim() : null) && (this.port == port);
    }

    public String displayAddress() {
        return authority(address, port);
    }
}
