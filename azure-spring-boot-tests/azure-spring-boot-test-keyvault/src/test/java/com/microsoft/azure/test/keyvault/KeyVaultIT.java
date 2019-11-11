/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.azure.test.keyvault;

import com.microsoft.azure.management.appservice.WebApp;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.keyvault.Vault;
import com.microsoft.azure.management.resources.fluentcore.utils.SdkContext;
import com.microsoft.azure.mgmt.*;
import com.microsoft.azure.test.AppRunner;
import com.microsoft.azure.utils.SSHShell;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static org.junit.Assert.assertEquals;

@Slf4j
public class KeyVaultIT {
  
    private static ClientSecretAccess access;
    private static Vault vault;
    private static String resourceGroupName;
    private static RestTemplate restTemplate;
    private static final String prefix = "test-keyvault";
    private static final String VM_USER_NAME = "deploy";
    private static final String VM_USER_PASSWORD = "12NewPAwX0rd!";
    private static final String KEY_VAULT_VALUE = "value";
    private static final String TEST_KEY_VAULT_JAR_FILE_NAME = "app.jar";
    private static String TEST_KEYVAULT_APP_JAR_PATH;

    @BeforeClass
    public static void createKeyVault() throws IOException {
        access = ClientSecretAccess.load();
        resourceGroupName = SdkContext.randomResourceName(ConstantsHelper.TEST_RESOURCE_GROUP_NAME_PREFIX, 30);
        final KeyVaultTool tool = new KeyVaultTool(access);
        vault = tool.createVaultInNewGroup(resourceGroupName, prefix);
        vault.secrets().define("key").withValue(KEY_VAULT_VALUE).create();
        vault.secrets().define("azure-cosmosdb-key").withValue(KEY_VAULT_VALUE).create();
        restTemplate = new RestTemplate();

        TEST_KEYVAULT_APP_JAR_PATH = new File(System.getProperty("keyvault.app.jar.path")).getCanonicalPath();
        log.info("--------------------->resources provision over");
    }
    
    @AfterClass
    public static void deleteResourceGroup() {
        final ResourceGroupTool tool = new ResourceGroupTool(access);
        tool.deleteGroup(resourceGroupName);
        log.info("--------------------->resources clean over");
    }

    @Test
    public void keyVaultAsPropertySource() {
        try (AppRunner app = new AppRunner(DumbApp.class)) {
            app.property("azure.keyvault.enabled", "true");
            app.property("azure.keyvault.uri", vault.vaultUri());
            app.property("azure.keyvault.client-id", access.clientId());
            app.property("azure.keyvault.client-key", access.clientSecret());
            
            app.start();
            assertEquals(KEY_VAULT_VALUE, app.getProperty("key"));
            app.close();
            log.info("--------------------->test over");
        }
    }

    @Test
    public void keyVaultAsPropertySourceWithSpecificKeys() {
        try (AppRunner app = new AppRunner(DumbApp.class)) {
            app.property("azure.keyvault.enabled", "true");
            app.property("azure.keyvault.uri", vault.vaultUri());
            app.property("azure.keyvault.client-id", access.clientId());
            app.property("azure.keyvault.client-key", access.clientSecret());
            app.property("azure.keyvault.secret.keys", "key");

            app.start();
            assertEquals(KEY_VAULT_VALUE, app.getProperty("key"));
            app.close();
            log.info("--------------------->test over");
        }
    }

    @Test
    public void keyVaultWithAppServiceMSI() throws Exception {
        final AppServiceTool appServiceTool = new AppServiceTool(access);

        final Map<String, String> appSettings = new HashMap<>();
        appSettings.put("AZURE_KEYVAULT_URI", vault.vaultUri());

        final WebApp appService = appServiceTool.createAppService(resourceGroupName, prefix, appSettings);

        // Grant System Assigned MSI access to key vault
        KeyVaultTool.grantSystemAssignedMSIAccessToKeyVault(vault,
                appService.systemAssignedManagedServiceIdentityPrincipalId());

        // Deploy to app through FTP
        appServiceTool.deployJARToAppService(appService, TEST_KEYVAULT_APP_JAR_PATH);

        // Restart App Service
        appService.restart();

        final String resourceUrl = "https://" + appService.name() + ".azurewebsites.net" + "/get";
        // warm up
        final ResponseEntity<String> response = curlWithRetry(resourceUrl, 3, 120_000, String.class);

        assertEquals(response.getStatusCode(), HttpStatus.OK);
        assertEquals(response.getBody(), KEY_VAULT_VALUE);
        log.info("--------------------->test over");
    }

    @Test
    public void keyVaultWithVirtualMachineMSI() throws Exception {
        final VirtualMachineTool vmTool = new VirtualMachineTool(access);

        // create virtual machine
        final VirtualMachine vm = vmTool.createVM(resourceGroupName, prefix, VM_USER_NAME, VM_USER_PASSWORD);
        final String host = vm.getPrimaryPublicIPAddress().ipAddress();

        // Grant System Assigned MSI access to key vault
        KeyVaultTool.grantSystemAssignedMSIAccessToKeyVault(vault,
                vm.systemAssignedManagedServiceIdentityPrincipalId());

        // Upload app.jar to virtual machine
        try (SSHShell sshShell = SSHShell.open(host, 22, VM_USER_NAME, VM_USER_PASSWORD);
             FileInputStream fis = new FileInputStream(new File(TEST_KEYVAULT_APP_JAR_PATH))) {

            log.info(String.format("Uploading jar file %s", TEST_KEYVAULT_APP_JAR_PATH));
            sshShell.upload(fis, TEST_KEY_VAULT_JAR_FILE_NAME, "", true, "4095");
        }

        // run java application
        final List<String> commands = new ArrayList<>();
        commands.add(String.format("cd /home/%s", VM_USER_NAME));
        commands.add(String.format("nohup java -jar -Dazure.keyvault.uri=%s %s &", vault.vaultUri(),
                TEST_KEY_VAULT_JAR_FILE_NAME));
        vmTool.runCommandOnVM(vm, commands);

        final ResponseEntity<String> response = curlWithRetry(
                String.format("http://%s:8080/get", host),
                3,
                60_000,
                String.class);

        assertEquals(response.getStatusCode(), HttpStatus.OK);
        assertEquals(response.getBody(), KEY_VAULT_VALUE);
        log.info("--------------------->test over");
    }

    private static <T> ResponseEntity<T> curlWithRetry(String resourceUrl,
                                                    int retryTimes,
                                                    int sleepMills,
                                                    Class<T> clazz) {
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        ResponseEntity<T> response = ResponseEntity.of(Optional.empty());

        while (retryTimes-- > 0 && httpStatus != HttpStatus.OK) {
            SdkContext.sleep(sleepMills);

            log.info("CURLing " + resourceUrl);

            response = restTemplate.getForEntity(resourceUrl, clazz);
            httpStatus = response.getStatusCode();
        }
        return response;
    }

    @SpringBootApplication
    public static class DumbApp {}
}