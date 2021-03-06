/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.azure.keyvault.spring;

import com.azure.core.exception.HttpRequestException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.Response;
import com.azure.core.util.Context;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Stream;

@Slf4j
public class KeyVaultOperation {

    /**
     * Stores the case sensitive flag.
     */
    private final boolean caseSensitive;

    /**
     * Stores the properties.
     */
    private HashMap<String, String> properties = new HashMap<>();

    /**
     * Stores the secret client.
     */
    private final SecretClient secretClient;

    /**
     * Stores the secret keys.
     */
    private List<String> secretKeys;

    /**
     * Constructor.
     *
     * @param secretClient the Key Vault secret client.
     * @param refreshInMillis the refresh in milliseconds (0 or less disables
     * refresh).
     * @param secretKeys the secret keys to look for.
     * @param caseSensitive the case sensitive flag.
     */
    public KeyVaultOperation(
            final SecretClient secretClient,
            final long refreshInMillis,
            List<String> secretKeys,
            boolean caseSensitive) {

        this.caseSensitive = caseSensitive;
        this.secretClient = secretClient;
        this.secretKeys = secretKeys;

        refreshProperties();
            
        if (refreshInMillis > 0) {
            final Timer timer = new Timer();
            final TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    refreshProperties();
                }
            };
            timer.scheduleAtFixedRate(task, refreshInMillis, refreshInMillis);
        }
    }

    /**
     * Get the property.
     *
     * @param property the property to get.
     * @return the property value.
     */
    public String getProperty(String property) {
        return properties.get(toKeyVaultSecretName(property));
    }

    /**
     * Get the property names.
     *
     * @return the property names.
     */
    public String[] getPropertyNames() {
        if (!caseSensitive) {
            return properties
                    .keySet()
                    .stream()
                    .flatMap(p -> Stream.of(p, p.replaceAll("-", ".")))
                    .distinct()
                    .toArray(String[]::new);
        } else {
            return properties
                    .keySet()
                    .toArray(new String[0]);
        }
    }

    /**
     * Refresh the properties by accessing key vault.
     */
    private void refreshProperties() {
        final HashMap<String, String> newProperties = new HashMap<>();
        if (secretKeys == null || secretKeys.isEmpty()) {
            final PagedIterable<SecretProperties> pagedIterable = secretClient.listPropertiesOfSecrets();
            if (pagedIterable != null) {
                pagedIterable.iterableByPage().forEach(r -> {
                    r.getElements().forEach(p -> {
                        final KeyVaultSecret secret = secretClient.getSecret(
                                p.getName(), p.getVersion());
                        newProperties.put(secret.getName(), secret.getValue());
                    });
                });
            }
        } else {
            for (final String secretKey : secretKeys) {
                final KeyVaultSecret secret = secretClient.getSecret(toKeyVaultSecretName(secretKey));
                if (secret != null) {
                    newProperties.put(secretKey, secret.getValue());
                }
            }
        }
        properties = newProperties;
    }

    /**
     * For convention we need to support all relaxed binding format from spring,
     * these may include:
     * <table>
     * <tr><td>Spring relaxed binding names</td></tr>
     * <tr><td>acme.my-project.person.first-name</td></tr>
     * <tr><td>acme.myProject.person.firstName</td></tr>
     * <tr><td>acme.my_project.person.first_name</td></tr>
     * <tr><td>ACME_MYPROJECT_PERSON_FIRSTNAME</td></tr>
     * </table>
     * But azure keyvault only allows ^[0-9a-zA-Z-]+$ and case insensitive, so
     * there must be some conversion between spring names and azure keyvault
     * names. For example, the 4 properties stated above should be convert to
     * acme-myproject-person-firstname in keyvault.
     *
     * @param property of secret instance.
     * @return the value of secret with given name or null.
     */
    private String toKeyVaultSecretName(@NonNull String property) {
        if (!caseSensitive) {
            if (property.matches("[a-z0-9A-Z-]+")) {
                return property.toLowerCase(Locale.US);
            } else if (property.matches("[A-Z0-9_]+")) {
                return property.toLowerCase(Locale.US).replaceAll("_", "-");
            } else {
                return property.toLowerCase(Locale.US)
                        .replaceAll("-", "") // my-project -> myproject
                        .replaceAll("_", "") // my_project -> myproject
                        .replaceAll("\\.", "-"); // acme.myproject -> acme-myproject
            }
        } else {
            return property;
        }
    }

    /**
     * Set the properties.
     *
     * @param properties the properties.
     */
    void setProperties(HashMap<String, String> properties) {
        this.properties = properties;
    }
    
    /**
     * Are we up?
     *
     * @return true if we are, false otherwise.
     */
    boolean isUp() {
        boolean up;
        try {
            final Response<KeyVaultSecret> response = secretClient
                    .getSecretWithResponse("should-not-be-empty", null, Context.NONE);
            up = response.getStatusCode() < 500;
        } catch (ResourceNotFoundException e) {
            up = true;
        } catch (HttpRequestException hre) {
            log.error("An HTTP error occurred while checking key vault connectivity", hre);
            up = true;
        } catch (RuntimeException re) {
            log.error("A runtime error occurred while checking key vault connectivity", re);
            up = false;
        }
        return up;
    }
}
