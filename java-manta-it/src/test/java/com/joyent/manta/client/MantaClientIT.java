/*
 * Copyright (c) 2013-2020, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.manta.client;

import com.joyent.manta.client.helper.IntegrationTestHelper;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.config.IntegrationTestConfigContext;
import com.joyent.manta.http.MantaHttpHeaders;
import com.joyent.test.util.MantaAssert;
import com.joyent.test.util.MantaFunction;
import com.joyent.test.util.RandomInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tests the basic functionality of the {@link MantaClient} class.
 */
@Test(groups = {"buckets"})
public class MantaClientIT {

    private static final String TEST_DATA = "EPISODEII_IS_BEST_EPISODE";

    private MantaClient mantaClient;

    private String testPathPrefix;

    @BeforeClass
    @Parameters({"encryptionCipher", "testType"})
    public void beforeClass(final @Optional String encryptionCipher, final @Optional String testType) throws IOException {

        // Let TestNG configuration take precedence over environment variables
        ConfigContext config = new IntegrationTestConfigContext(encryptionCipher);
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

    @Test
    public final void testCRUDObject() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        try (final MantaObjectInputStream gotObject = mantaClient.getAsInputStream(path)) {
            Assert.assertNotNull(gotObject);
            Assert.assertNotNull(gotObject.getContentType());
            Assert.assertNotNull(gotObject.getContentLength());
            Assert.assertNotNull(gotObject.getEtag());
            Assert.assertNotNull(gotObject.getMtime());
            Assert.assertNotNull(gotObject.getPath());

            final String data = IOUtils.toString(gotObject, Charset.defaultCharset());
            Assert.assertEquals(data, TEST_DATA);
        }

        mantaClient.delete(path);

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(testPathPrefix + name));
    }

    @Test
    public final void canReadStreamAndThenCloseWithoutErrors() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        mantaClient.put(path, TEST_DATA);

        try (final MantaObjectInputStream gotObject = mantaClient.getAsInputStream(path)) {
            gotObject.read();
            gotObject.read();
        }

        mantaClient.delete(path);
        Assert.assertFalse(mantaClient.existsAndIsAccessible(path));
    }

    @Test
    public final void canCopyStreamToFileAndCloseWithoutErrors() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;

        try (InputStream in = new RandomInputStream(8000)) {
            mantaClient.put(path, in);
        }

        File temp = File.createTempFile("object-" + name, ".data");
        FileUtils.forceDeleteOnExit(temp);

        try (InputStream in = mantaClient.getAsInputStream(path);
             FileOutputStream out = new FileOutputStream(temp)) {
            IOUtils.copyLarge(in, out);
        }

        mantaClient.delete(path);
        Assert.assertFalse(mantaClient.existsAndIsAccessible(path));
    }

    @Test
    public final void canCreateStreamInOneThreadAndCloseInAnother()
            throws Exception {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;

        try (InputStream in = new RandomInputStream(8000)) {
            mantaClient.put(path, in);
        }

        File temp = File.createTempFile("object-" + name, ".data");
        FileUtils.forceDeleteOnExit(temp);

        FileOutputStream out = new FileOutputStream(temp);

        Callable<InputStream> callable = () -> mantaClient.getAsInputStream(path);

        ExecutorService service = Executors.newFixedThreadPool(1);
        InputStream in = service.submit(callable).get();

        try {
            IOUtils.copyLarge(in, out);
        } finally {
            in.close();
            out.close();
        }
        mantaClient.delete(path);

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(path));
    }

    @Test
    public final void createMantaObjectStreamCloseInOneThreadAndAbortInAnother()
            throws Exception {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;

        try (InputStream in = new RandomInputStream(10000)) {
            mantaClient.put(path, in);
        }

        File temp = File.createTempFile("object-" + name, ".data");
        FileUtils.forceDeleteOnExit(temp);
        FileOutputStream out = new FileOutputStream(temp);
        FileOutputStream out2 = new FileOutputStream(temp);

        Callable<InputStream> callable = () -> mantaClient.getAsInputStream(path);

        ExecutorService service = Executors.newFixedThreadPool(2);
        MantaObjectInputStream in = (MantaObjectInputStream) service.submit(callable).get();
        MantaObjectInputStream in2 = (MantaObjectInputStream) service.submit(callable).get();

        try {
            IOUtils.copyLarge(in, out);
            IOUtils.copyLarge(in2, out2);
        } finally {
            in.abortConnection();
            out.close();
            in2.close();
            out2.close();
        }
    }

    @Test
    public final void testManyOperationsWithDirectories() throws IOException {
        String dir = testPathPrefix + "multiple";
        final boolean bucketsEnabled = testPathPrefix.contains(mantaClient.getContext().getMantaBucketsDirectory());

        if (bucketsEnabled) {
            throw new SkipException("Test skipped since it's invalid for MantaV2");
        }

        mantaClient.putDirectory(dir);

        for (int i = 0; i < 100; i++) {
            final String name = UUID.randomUUID().toString();
            final String path = String.format("%s/%s", dir, name);
            mantaClient.put(path, TEST_DATA);
            String actual = mantaClient.getAsString(path);
            Assert.assertEquals(actual, TEST_DATA);
        }

        mantaClient.deleteRecursive(dir);
        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(dir));
    }

    @Test
    public final void testCRUDWithFileObject() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;

        mantaClient.put(path, TEST_DATA);
        final File file = mantaClient.getToTempFile(path);

        final String data = FileUtils.readFileToString(file, Charset.defaultCharset());
        Assert.assertEquals(data, TEST_DATA);
        mantaClient.delete(path);

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(testPathPrefix + name));
    }

    @Test
    public final void testCRUDWithByteArray() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;

        mantaClient.put(path, TEST_DATA.getBytes(StandardCharsets.UTF_8));
        final String actual = mantaClient.getAsString(path);

        Assert.assertEquals(actual, TEST_DATA);
        mantaClient.delete(path);

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(testPathPrefix + name));
    }

    @Test
    public final void testCRUDObjectWithHeaders() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        final MantaHttpHeaders headers = new MantaHttpHeaders();
        headers.setDurabilityLevel(3);

        mantaClient.put(path, TEST_DATA, headers);
        try (final MantaObjectInputStream gotObject = mantaClient.getAsInputStream(path)) {
            final String data = IOUtils.toString(gotObject, Charset.defaultCharset());
            Assert.assertEquals(data, TEST_DATA);
            Assert.assertEquals("3", gotObject.getHttpHeaders().getFirstHeaderStringValue("durability-level"));
            mantaClient.delete(gotObject.getPath());
        }

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(testPathPrefix + name));
    }

    @Test
    public final void canGetZeroByteFileAsInputStream() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        final byte[] empty = new byte[0];

        mantaClient.put(path, empty);
        try (final MantaObjectInputStream gotObject = mantaClient.getAsInputStream(path)) {
            Assert.assertEquals(gotObject.getContentLength().longValue(), 0);
            byte[] actualBytes = IOUtils.toByteArray(gotObject);
            Assert.assertEquals(actualBytes, empty,
                    "Actual object was not the expected 0 bytes");
            mantaClient.delete(path);
        }

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(testPathPrefix + name));
    }

    @Test
    public final void canGetZeroByteFileAsString() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;
        final byte[] empty = new byte[0];

        mantaClient.put(path, empty);
        final String actual = mantaClient.getAsString(path, StandardCharsets.UTF_8);
        Assert.assertEquals(actual, "",
                "Empty string not returned as expected");
        mantaClient.delete(path);

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(path));
    }

    @Test
    public final void testContentTypeSetByFilename() throws IOException {
        final String name = UUID.randomUUID().toString() + ".html";
        final String path = testPathPrefix + name;

        mantaClient.put(path, TEST_DATA.getBytes(StandardCharsets.UTF_8));
        MantaObject object = mantaClient.head(path);

        Assert.assertEquals(object.getContentType(),
                "text/html", "Content type wasn't auto-assigned");
        mantaClient.delete(path);

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(path));
    }

    @Test
    public final void testRFC3986() throws IOException {
        final String name = "spaces in the name of the file";
        final String path = testPathPrefix + name;

        mantaClient.put(path, TEST_DATA);
        final String actual = mantaClient.getAsString(path);

        Assert.assertEquals(actual, TEST_DATA);
        mantaClient.delete(path);

        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(testPathPrefix + name));
    }

    @Test
    public final void testFileExists() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;

        mantaClient.put(path, TEST_DATA);

        final boolean actual = mantaClient.existsAndIsAccessible(path);
        Assert.assertTrue(actual, "File object should exist");

        mantaClient.delete(path);
        MantaAssert.assertResponseFailureCode(404,
                (MantaFunction<Object>) () -> mantaClient.get(path));
    }

    @Test
    public final void testFileDoesntExist() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;

        final boolean actual = mantaClient.existsAndIsAccessible(path);
        Assert.assertFalse(actual, "File object shouldn't exist");
    }
}
