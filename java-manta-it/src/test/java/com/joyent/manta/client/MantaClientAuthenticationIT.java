/*
 * Copyright (c) 2019, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.manta.client;

import com.joyent.manta.client.helper.IntegrationTestHelper;
import com.joyent.manta.config.*;
import com.joyent.manta.exception.ConfigurationException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.UUID;

/**
 * Tests client authentication, sub-user functionality and other issues
 * associated with it for the {@link MantaClient} class.
 *
 * @author <a href="https://github.com/nairashwin952013">Ashwin A Nair</a>-
 */
public class MantaClientAuthenticationIT {
    private static final String TEST_DATA = "Arise,Awake And Do Not Stop Until Your Goal Is Reached.";

    private MantaClient mantaClient;

    private String testPathPrefix;

    @BeforeClass
    @Parameters({"usingEncryption", "testType"})
    public void beforeClass(final @Optional Boolean usingEncryption,
                            final @Optional String testType) throws IOException {

        // Let TestNG configuration take precedence over environment variables
        ConfigContext config = new IntegrationTestConfigContext(usingEncryption);
        final String testName = this.getClass().getSimpleName();

        mantaClient = new MantaClient(config);
        testPathPrefix = IntegrationTestHelper.setupTestPath(config, mantaClient,
                testName, testType);
        IntegrationTestHelper.createTestBucketOrDirectory(mantaClient, testPathPrefix, testType);
    }

    @AfterClass
    public void afterClass() throws IOException {
        IntegrationTestHelper.cleanupTestBucketOrDirectory(mantaClient, testPathPrefix);
    }

    @Test(expectedExceptions = { ConfigurationException.class })
    public final void testInvalidMantaUser() throws IOException {
        System.setProperty("manta.dumpConfig", "true");
        final ConfigContext config = new ChainedConfigContext(
                new StandardConfigContext()
                        .setMantaKeyId("some placeholder string")
                        .setMantaUser("MY USERNAME"),
                new DefaultsConfigContext()

        );

        try (final MantaClient client = new MantaClient(config)) {
            Assert.assertNotNull(client);

            final String name = UUID.randomUUID().toString();
            final String path = config.getMantaUser() + "/stor/" + name;
            client.put(path, TEST_DATA);
        }
    }
}

