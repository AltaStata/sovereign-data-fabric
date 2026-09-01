/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

/*
 * Load GREP11 client settings from grep11client.yaml (same file used by the PKCS#11 .so).
 * Use from Mac or any host: no .so required, just IAM + gRPC.
 */

package com.altastata.cloud.ibm;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * GREP11 connection config loaded from grep11client.yaml.
 * Maps YAML keys: tokens.0.grep11connection.address/port, iamcredentialtemplate.instance,
 * and the first user with iamauth.apikey (e.g. users.2.iamauth.apikey).
 */
public final class Grep11ConfigFromYaml {

    public final String endpoint;
    public final int port;
    public final String instanceId;
    public final String apiKey;

    /**
     * Constructs a new Grep11ConfigFromYaml config object.
     *
     * @param endpoint the GREP11 connection address endpoint
     * @param port the GREP11 connection port
     * @param instanceId the IBM Cloud HPCS instance ID
     * @param apiKey the IBM Cloud API key for IAM authentication
     */
    public Grep11ConfigFromYaml(String endpoint, int port, String instanceId, String apiKey) {
        this.endpoint = endpoint;
        this.port = port;
        this.instanceId = instanceId;
        this.apiKey = apiKey;
    }

    /**
     * Load config from a grep11client.yaml file.
     *
     * @param yamlPath path to the YAML file (e.g. /etc/ep11client/grep11client.yaml or ./grep11client.yaml)
     * @return config with endpoint, port, instanceId, apiKey
     * @throws IllegalArgumentException if file missing or required keys not found
     */
    @SuppressWarnings("unchecked")
    public static Grep11ConfigFromYaml load(Path yamlPath) throws Exception {
        if (!Files.exists(yamlPath) || !Files.isRegularFile(yamlPath)) {
            throw new IllegalArgumentException("YAML file not found: " + yamlPath);
        }
        Map<String, Object> root;
        try (InputStream in = new FileInputStream(yamlPath.toFile())) {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            root = yaml.load(in);
        }
        if (root == null) {
            throw new IllegalArgumentException("Empty YAML: " + yamlPath);
        }

        String instanceId = null;
        Object iam = root.get("iamcredentialtemplate");
        if (iam instanceof Map) {
            Object inst = ((Map<?, ?>) iam).get("instance");
            if (inst != null) instanceId = inst.toString().trim();
        }
        if (instanceId == null || instanceId.isEmpty() || instanceId.startsWith("<")) {
            throw new IllegalArgumentException("YAML: iamcredentialtemplate.instance must be set (HPCS instance ID)");
        }

        Object tokens = root.get("tokens");
        if (!(tokens instanceof Map)) {
            throw new IllegalArgumentException("YAML: tokens must be a map");
        }
        Map<?, ?> tokensMap = (Map<?, ?>) tokens;
        Object token0 = tokensMap.get(0);
        if (token0 == null) token0 = tokensMap.get("0");
        if (!(token0 instanceof Map)) {
            throw new IllegalArgumentException("YAML: tokens.0 not found");
        }
        Map<?, ?> t0 = (Map<?, ?>) token0;

        Object conn = t0.get("grep11connection");
        if (!(conn instanceof Map)) {
            throw new IllegalArgumentException("YAML: tokens.0.grep11connection not found");
        }
        Map<?, ?> grep11 = (Map<?, ?>) conn;
        Object addr = grep11.get("address");
        Object portObj = grep11.get("port");
        String endpoint = addr != null ? addr.toString().trim() : null;
        int port = 13412;
        if (portObj != null) {
            if (portObj instanceof Number) port = ((Number) portObj).intValue();
            else try { port = Integer.parseInt(portObj.toString()); } catch (NumberFormatException ignored) { }
        }
        if (endpoint == null || endpoint.isEmpty() || endpoint.startsWith("<")) {
            throw new IllegalArgumentException("YAML: tokens.0.grep11connection.address must be set (e.g. <id>.ep11.<region>.hs-crypto.appdomain.cloud)");
        }

        String apiKey = null;
        Object users = t0.get("users");
        if (users instanceof Map) {
            Map<?, ?> usersMap = (Map<?, ?>) users;
            for (Object k : new Object[] { 2, "2", 1, "1", 0, "0" }) {
                Object u = usersMap.get(k);
                if (u instanceof Map) {
                    Object iamauth = ((Map<?, ?>) u).get("iamauth");
                    if (iamauth instanceof Map) {
                        Object key = ((Map<?, ?>) iamauth).get("apikey");
                        if (key != null) {
                            String s = key.toString().trim();
                            if (!s.isEmpty() && !s.startsWith("<")) {
                                apiKey = s;
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("YAML: no users.<n>.iamauth.apikey found (set in tokens.0.users.2.iamauth.apikey or similar)");
        }

        return new Grep11ConfigFromYaml(endpoint, port, instanceId, apiKey);
    }
}
