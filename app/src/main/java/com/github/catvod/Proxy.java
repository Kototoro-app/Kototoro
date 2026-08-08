package com.github.catvod;

import com.github.catvod.utils.Util;

import java.net.URI;

public class Proxy {

    private static int port = -1;
    private static final InheritableThreadLocal<Endpoint> endpoint = new InheritableThreadLocal<>();

    public static void set(int port) {
        Proxy.port = port;
    }

    public static int getPort() {
        Endpoint current = endpoint.get();
        if (current != null) {
            try {
                int endpointPort = URI.create(current.localAddress).getPort();
                if (endpointPort > 0) return endpointPort;
            } catch (Throwable ignored) {
            }
        }
        return port;
    }

    public static String getUrl(boolean local) {
        Endpoint current = endpoint.get();
        if (current != null) return appendPath(current.getAddress(local), "proxy");
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + getPort() + "/proxy";
    }

    public static String getAddress(boolean local) {
        Endpoint current = endpoint.get();
        if (current != null) return ensureTrailingSlash(current.getAddress(local));
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + getPort() + "/";
    }

    public static void setEndpoint(String localAddress, String remoteAddress) {
        if (localAddress == null || localAddress.trim().isEmpty()) {
            endpoint.remove();
        } else {
            endpoint.set(new Endpoint(localAddress, remoteAddress));
        }
    }

    public static String[] getEndpoint() {
        Endpoint current = endpoint.get();
        return current == null ? null : new String[]{current.localAddress, current.remoteAddress};
    }

    public static void clearEndpoint() {
        endpoint.remove();
    }

    private static String appendPath(String base, String path) {
        return ensureTrailingSlash(base) + path;
    }

    private static String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private static final class Endpoint {
        private final String localAddress;
        private final String remoteAddress;

        private Endpoint(String localAddress, String remoteAddress) {
            this.localAddress = trimTrailingSlash(localAddress.trim());
            this.remoteAddress = remoteAddress == null || remoteAddress.trim().isEmpty()
                ? this.localAddress
                : trimTrailingSlash(remoteAddress.trim());
        }

        private String getAddress(boolean local) {
            return local ? localAddress : remoteAddress;
        }

        private static String trimTrailingSlash(String value) {
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return value;
        }
    }
}
