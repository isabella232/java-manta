/*
 * Copyright (c) 2015-2019, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.manta.client;

import com.joyent.manta.client.helper.IntegrationTestHelper;
import com.joyent.manta.config.IntegrationTestConfigContext;
import com.joyent.manta.config.ChainedConfigContext;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.config.MapConfigContext;
import com.joyent.test.util.MantaAssert;
import com.joyent.test.util.MantaFunction;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

import static com.joyent.manta.exception.MantaErrorCode.ACCOUNT_DOES_NOT_EXIST_ERROR;
import static com.joyent.manta.exception.MantaErrorCode.NO_CODE_ERROR;
import static com.joyent.manta.exception.MantaErrorCode.RESOURCE_NOT_FOUND_ERROR;

/**
 * Tests for verifying the correct behavior of error handling from Manta API
 * failures.
 */
@Test(groups = {"error"})
public class MantaClientErrorIT {
    private MantaClient mantaClient;

    private ConfigContext config;

    private String testPathPrefix;

    @BeforeClass
    @Parameters({"testType"})
    public void beforeClass(final @Optional String testType) throws IOException {
        // Let TestNG configuration take precedence over environment variables
        config = new IntegrationTestConfigContext();
        final String testName = this.getClass().getSimpleName();
        mantaClient = new MantaClient(config);

        if (testType.equals("buckets")) {
            testPathPrefix = IntegrationTestHelper.setupTestPath(config, mantaClient,
                    testName, testType);
        } else {
            testPathPrefix = IntegrationTestConfigContext.generateBasePathWithoutSeparator(config, testName);
        }

        IntegrationTestHelper.createTestBucketOrDirectory(mantaClient, testPathPrefix, testType);
    }

    @AfterClass
    public void afterClass() throws IOException {
        IntegrationTestHelper.cleanupTestBucketOrDirectory(mantaClient, testPathPrefix);
    }

    @Test
    public void incorrectLogin() {
        Properties properties = new Properties();
        properties.setProperty(MapConfigContext.MANTA_USER_KEY, "baduser");

        ConfigContext badConfig = new ChainedConfigContext(
                config,
                new MapConfigContext(properties)
        );

        Assert.assertEquals(badConfig.getMantaUser(), "baduser",
                "Unable to set incorrect account");

        final String path = String.format("%s/stor", config.getMantaHomeDirectory());
        final MantaClient badClient = new MantaClient(badConfig);

        MantaAssert.assertResponseFailureStatusCode(403, ACCOUNT_DOES_NOT_EXIST_ERROR,
                (MantaFunction<Object>) () -> badClient.get(path));
    }

    @Test
    public void badHomeDirectory() {
        String path = "/badpath";

        MantaAssert.assertResponseFailureStatusCode(403, ACCOUNT_DOES_NOT_EXIST_ERROR,
                (MantaFunction<Object>) () -> mantaClient.get(path));
    }

    @Test
    public void fileNotFoundWithNoContent() {
        String path = String.format("%s/%s", testPathPrefix, UUID.randomUUID());

        MantaAssert.assertResponseFailureStatusCode(404, NO_CODE_ERROR,
                (MantaFunction<Object>) () -> mantaClient.head(path));
    }

    @Test
    public void fileNotFoundWithContent() {
        String path = String.format("%s/%s", testPathPrefix, UUID.randomUUID());

        MantaAssert.assertResponseFailureStatusCode(404, RESOURCE_NOT_FOUND_ERROR,
                (MantaFunction<Object>) () -> mantaClient.get(path));
    }
}
