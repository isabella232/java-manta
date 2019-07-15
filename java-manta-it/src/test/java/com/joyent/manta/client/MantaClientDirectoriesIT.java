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
import com.joyent.manta.exception.MantaClientException;
import com.joyent.manta.exception.MantaClientHttpResponseException;
import com.joyent.test.util.MantaAssert;
import com.joyent.test.util.MantaFunction;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.text.RandomStringGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.joyent.manta.client.MantaClient.SEPARATOR;
import static com.joyent.manta.exception.MantaErrorCode.RESOURCE_NOT_FOUND_ERROR;
import static com.joyent.manta.util.MantaUtils.writeablePrefixPaths;

/**
 * Tests for verifying the correct functioning of making remote requests
 * against Manta directories.
 */
@Test(groups = {"directory"})
public class MantaClientDirectoriesIT {

    private static final Logger LOG = LoggerFactory.getLogger(MantaClientDirectoriesIT.class);

    private static final RandomStringGenerator STRING_GENERATOR =
            new RandomStringGenerator.Builder()
                    .withinRange((int) 'a', (int) 'z')
                    .build();

    private static final String TEST_DATA = "EPISODEII_IS_BEST_EPISODE";

    private MantaClient mantaClient;

    private String testPathPrefix;

    private IntegrationTestConfigContext config;

    @BeforeClass
    @Parameters({"usingEncryption", "testType"})
    public void beforeClass(final @Optional Boolean usingEncryption,
                            final @Optional String testType) throws IOException {
        if ("buckets".equals(testType)) {
            throw new SkipException("Directory tests will be skipped in Manta Buckets");
        }

        config = new IntegrationTestConfigContext();
        final String testName = this.getClass().getSimpleName();
        mantaClient = new MantaClient(config);
        testPathPrefix = IntegrationTestConfigContext.generateBasePathWithoutSeparator(config, testName);
    }

    @AfterClass
    public void afterClass() throws IOException {
        IntegrationTestHelper.cleanupTestBucketOrDirectory(mantaClient, testPathPrefix);
    }

    @Test
    public void canCreateDirectory() throws IOException {
        mantaClient.putDirectory(testPathPrefix, true);

        String dir = String.format("%s/%s", testPathPrefix, UUID.randomUUID());
        boolean created = mantaClient.putDirectory(dir);

        Assert.assertTrue(created, "Directory was marked as created");

        MantaObject response = mantaClient.head(dir);
        Assert.assertEquals(dir, response.getPath());
    }

    @Test
    public void willReturnFalseWhenWeOverwriteDirectory() throws IOException {
        mantaClient.putDirectory(testPathPrefix);

        String dir = String.format("%s/%s", testPathPrefix, UUID.randomUUID());
        Assert.assertTrue(mantaClient.putDirectory(dir),
                "We were unable to create the initial directory");

        boolean result = mantaClient.putDirectory(dir);

        Assert.assertFalse(result, "Expected a false value because we "
                + "didn't create a new directory");
    }

    @Test(dependsOnMethods = { "canCreateDirectory" })
    public void canDeleteDirectory() throws IOException {
        String dir = String.format("%s/%s", testPathPrefix, UUID.randomUUID());
        mantaClient.putDirectory(dir);

        MantaObject response = mantaClient.head(dir);
        Assert.assertEquals(dir, response.getPath());

        mantaClient.delete(dir);

        MantaAssert.assertResponseFailureStatusCode(404, RESOURCE_NOT_FOUND_ERROR,
                (MantaFunction<Object>) () -> mantaClient.get(dir));
    }

    @Test(dependsOnMethods = { "canCreateDirectory" })
    public void wontErrorWhenWeCreateOverAnExistingDirectory() throws IOException {
        String dir = String.format("%s/%s", testPathPrefix, UUID.randomUUID());
        mantaClient.putDirectory(dir);
        mantaClient.putDirectory(dir);
        mantaClient.putDirectory(dir);

        MantaObject response = mantaClient.head(dir);
        Assert.assertEquals(dir, response.getPath());
    }

    /**
     * This is somewhat surprising behavior, but this test documents Manta's
     * behavior. As a user of Manta, you will need to check to see if a
     * file exists before attempting to write a directory over the top of it.
     */
    @Test(dependsOnMethods = { "canCreateDirectory" })
    public void noErrorWhenWeOverwriteAnExistingFile() throws IOException {
        String dir = String.format("%s/%s", testPathPrefix, UUID.randomUUID());
        mantaClient.putDirectory(dir);

        String file = String.format("%s/%s", dir, UUID.randomUUID());
        mantaClient.put(file, TEST_DATA);
        mantaClient.putDirectory(file);
    }

    @Test(dependsOnMethods = { "canCreateDirectory" })
    public void directoryIsMarkedAsSuch() throws IOException {
        MantaObject dir = mantaClient.head(testPathPrefix);
        Assert.assertTrue(dir.isDirectory(),
                String.format("Directory should be marked as such [%s]. "
                        + "\nResponse: %s", testPathPrefix, dir));
    }

    @Test(dependsOnMethods = { "wontErrorWhenWeCreateOverAnExistingDirectory" })
    public void canRecursivelyCreateDirectory() throws IOException {
        String dir = String.format("%s/%s/%s/%s/%s/%s", testPathPrefix,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());

        mantaClient.putDirectory(dir, true);

        MantaObject response = mantaClient.head(dir);

        Assert.assertTrue(response.isDirectory(),
                String.format("Directory should be marked as such [%s]", testPathPrefix));
        Assert.assertEquals(dir, response.getPath());
    }

    @Test
    public final void testList() throws IOException {
        final String pathPrefix = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.putDirectory(pathPrefix, null);

        mantaClient.put(String.format("%s/%s", pathPrefix, UUID.randomUUID()), "");
        mantaClient.put(String.format("%s/%s", pathPrefix, UUID.randomUUID()), "");
        final String subDir = pathPrefix + SEPARATOR + UUID.randomUUID().toString();
        mantaClient.putDirectory(subDir, null);
        mantaClient.put(String.format("%s/%s", subDir, UUID.randomUUID()), "");
        final Stream<MantaObject> objs = mantaClient.listObjects(pathPrefix);

        final AtomicInteger count = new AtomicInteger(0);
        objs.forEach(obj -> {
            count.incrementAndGet();
            Assert.assertTrue(obj.getPath().startsWith(testPathPrefix));
        });

        Assert.assertEquals(3, count.get());
    }

    @Test
    public final void testDirectoryExists() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String dir = testPathPrefix + name;

        mantaClient.putDirectory(dir);

        final boolean actual = mantaClient.existsAndIsAccessible(dir);
        Assert.assertTrue(actual, "File object should exist");
    }

    @Test
    public final void testIsDirectoryEmptyWithEmptyDir() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String dir = testPathPrefix + name;
        mantaClient.putDirectory(dir);

        Assert.assertTrue(mantaClient.isDirectoryEmpty(dir),
                "Empty directory is not reported as empty");
    }

    @Test
    public final void testIsDirectoryEmptyWithDirWithFiles() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String dir = testPathPrefix + name;
        mantaClient.putDirectory(dir);

        mantaClient.put(String.format("%s/%s", dir, UUID.randomUUID()), TEST_DATA);

        Assert.assertFalse(mantaClient.isDirectoryEmpty(dir),
                "Empty directory is not reported as empty");
    }

    @Test(expectedExceptions = { MantaClientException.class })
    public final void testIsDirectoryEmptyWithAFileNotDir() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String file = testPathPrefix + name;

        mantaClient.put(file, TEST_DATA);

        mantaClient.isDirectoryEmpty(file);
    }

    @Test
    public final void verifyYouCanJustSpecifyDirNameWhenPuttingFile() throws IOException {
        final String name = UUID.randomUUID().toString();
        final String path = testPathPrefix + name;

        mantaClient.putDirectory(path);

        File temp = File.createTempFile("upload", ".txt");

        boolean thrown = false;

        try {
            Files.write(temp.toPath(), TEST_DATA.getBytes(StandardCharsets.UTF_8));
            mantaClient.put(path, temp);
        } catch (MantaClientHttpResponseException e) {
            thrown = e.getStatusCode() == 400;
        }
        finally {
            Files.delete(temp.toPath());
        }

        Assert.assertTrue(thrown, "Bad request response not received");
    }

    @Test
    public void canCreateDirectoriesNormallyWhenNewDirectoryDepthLessThanSkipDepth() throws IOException {
        if (!mantaClient.existsAndIsAccessible(testPathPrefix)) {
            Assert.fail("Base directory is missing");
        }

        final String childDirectory = testPathPrefix + SEPARATOR + UUID.randomUUID();
        final int childDirectoryDepth = writeablePrefixPaths(childDirectory).length;
        // set the skip depth to be greater than the new directory's depth
        config.setSkipDirectoryDepth(childDirectoryDepth + 1);

        final long operations =
                RecursiveDirectoryCreationStrategy.createWithSkipDepth(mantaClient, childDirectory, null, config.getSkipDirectoryDepth());

        // check that number of operations is exactly the same as the writeable directories
        // (i.e. no extra operations and no fewer operations)
        Assert.assertEquals(operations, childDirectoryDepth);
    }

    @Test
    public final void testRecursiveDeleteObject() throws IOException {
        final String dir1 = String.format("%s1", testPathPrefix);
        mantaClient.putDirectory(testPathPrefix + "1", null);
        mantaClient.putDirectory(dir1, null);
        final String path1 = String.format("%s/%s", dir1, UUID.randomUUID());
        mantaClient.put(path1, TEST_DATA);

        final String dir2 = String.format("%s/2", dir1);
        mantaClient.putDirectory(dir2, null);
        final String path2 = String.format("%s/%s", dir2, UUID.randomUUID());
        mantaClient.put(path2, TEST_DATA);

        final String dir3 = String.format("%s/3", dir2);
        mantaClient.putDirectory(dir3, null);
        final String path3 = String.format("%s/%s", dir3, UUID.randomUUID());
        mantaClient.put(path3, TEST_DATA);

        mantaClient.deleteRecursive(testPathPrefix + "1");

        MantaAssert.assertResponseFailureStatusCode(404, RESOURCE_NOT_FOUND_ERROR,
                (MantaFunction<Object>) () -> mantaClient.get(testPathPrefix + "1"));
    }

    @Test
    public void canAddMetadataToDirectory() throws IOException {
        String dir = String.format("%s%s", testPathPrefix, UUID.randomUUID());
        mantaClient.putDirectory(dir);

        MantaMetadata metadata = new MantaMetadata();
        metadata.put("m-test", "value");

        mantaClient.putMetadata(dir, metadata);

        {
            MantaObject head = mantaClient.head(dir);
            MantaMetadata remoteMetadata = head.getMetadata();

            Assert.assertTrue(remoteMetadata.containsKey("m-test"));
            Assert.assertEquals(metadata.get("m-test"), remoteMetadata.get("m-test"),
                    "Set metadata doesn't equal actual metadata");
        }

        {
            MantaObject get = mantaClient.get(dir);
            MantaMetadata remoteMetadata = get.getMetadata();

            Assert.assertTrue(remoteMetadata.containsKey("m-test"));
            Assert.assertEquals(metadata.get("m-test"), remoteMetadata.get("m-test"),
                    "Set metadata doesn't equal actual metadata");
        }
    }

    @Test
    public void canSkipAlreadyCreatedDirectoriesWhenDepthSetCorrectly() throws IOException {
        directorySkipping(true);
    }

    @Test
    public void canAddAtMostOneFailedOperationWhenDepthSetIncorrectly() throws IOException {
        directorySkipping(false);
    }

    private void directorySkipping(final boolean settingCorrectDepth) throws IOException {
        final int parentAddedDepth = RandomUtils.nextInt(1, 5);
        final int childAddedDepth = RandomUtils.nextInt(2, 5); // child depth of 1 would trigger parts.length <= skipDepth early exit

        final StringBuilder parentDirBuilder = new StringBuilder(testPathPrefix);
        for (int i = 0; i < parentAddedDepth; i++) {
            parentDirBuilder
                    .append(SEPARATOR)
                    .append(STRING_GENERATOR.generate(3));
        }
        final String parentDir = parentDirBuilder.toString();
        final int parentDirDepth = writeablePrefixPaths(parentDir).length;
        config.setSkipDirectoryDepth(parentDirDepth);

        if (!settingCorrectDepth) {
            // assume the directory _directly_ under the parent exists so we cause a failure
            config.setSkipDirectoryDepth(config.getSkipDirectoryDepth() + 1);
        }

        final StringBuilder childDirBuilder = new StringBuilder(parentDir);
        for (int i = 0; i < childAddedDepth; i++) {
            childDirBuilder
                    .append(SEPARATOR)
                    .append(STRING_GENERATOR.generate(3));
        }
        final String childDir = childDirBuilder.toString();

        mantaClient.putDirectory(parentDir, true);
        final MantaObject response = mantaClient.head(parentDir);
        Assert.assertTrue(response.isDirectory(),
                String.format("Directory should be marked as such [%s]", testPathPrefix));
        Assert.assertEquals(parentDir, response.getPath());

        // create the strategy ourselves so we can check the number of operations
        final long operations =
                RecursiveDirectoryCreationStrategy.createWithSkipDepth(mantaClient, childDir, null, config.getSkipDirectoryDepth());

        // ensure that the child was created
        final MantaObject nestedResponse = mantaClient.head(childDir);
        Assert.assertTrue(nestedResponse.isDirectory(),
                String.format("Nested directory should be marked as such [%s]", testPathPrefix));
        Assert.assertEquals(childDir, nestedResponse.getPath());

        // verify that created the nested directory took less calls than its parent
        LOG.info("PARENT " + parentDir);
        LOG.info("CHILD  " + childDir);
        LOG.info("calls (child): " + operations);

        if (settingCorrectDepth) {
            // best case we only create directories belonging to the child
            Assert.assertEquals(operations, childAddedDepth);
        } else {
            // when we fail we'll do a single (unsuccessful) probe directly under the assumed parent depth plus one PUT
            // for each directory the in child path
            Assert.assertEquals(operations, parentDirDepth + childAddedDepth + 1);
        }
    }



    /**
     * This test will create a set of directories, then it will
     * set the pruneEmptyParentDepth = -1, meaning it will
     * delete all empty directories in the hierarchy.
     */
    @Test
    public void pruneParentDirectoriesFull() throws IOException {
        final String parentDir = createRandomDirectory(testPathPrefix, 1);
        // We are going to create a sibling to the parent directory, so
        // the test does not delete the root.
        createRandomDirectory(testPathPrefix, 1);
        final String childDir = createRandomDirectory(parentDir, 5);
        LOG.info("CHILD DIR  : " + childDir);
        LOG.info("Parent DIR : " + parentDir);
        mantaClient.delete(childDir, null, PruneEmptyParentDirectoryStrategy.PRUNE_ALL_PARENTS);
        Assert.assertFalse(mantaClient.existsAndIsAccessible(childDir));
        Assert.assertFalse(mantaClient.existsAndIsAccessible(parentDir));
        // Getting the path of the parent's parent.
        String ancestor = parentDir.substring(0,parentDir.lastIndexOf(SEPARATOR));
        Assert.assertTrue(mantaClient.existsAndIsAccessible(ancestor));
    }

    @Test
    public void pruneParentDirectoryZero() throws IOException {
        final String parentDir = createRandomDirectory(testPathPrefix, 1);
        final String childDir = createRandomDirectory(parentDir, 2);
        LOG.debug("CHILD DIR  : " + childDir);
        LOG.debug("Parent DIR : " + parentDir);
        mantaClient.delete(childDir, null, 0);
        Assert.assertFalse(mantaClient.existsAndIsAccessible(childDir));
        Assert.assertTrue(mantaClient.existsAndIsAccessible(parentDir));
    }

    @Test
    public void pruneParentDirectoryOne() throws IOException {
        // This should stop at 1 parent being deleted
        final String parentDir = createRandomDirectory(testPathPrefix, 1);
        final String childDir = createRandomDirectory(parentDir, 3);
        LOG.info("CHILD DIR  : " + childDir);
        LOG.info("Parent DIR : " + parentDir);
        mantaClient.delete(childDir, null, 1);
        Assert.assertFalse(mantaClient.existsAndIsAccessible(childDir));
        Assert.assertTrue(mantaClient.existsAndIsAccessible(parentDir));
    }

    /**
     * This test will create a set of directories, then it will
     * set the pruneEmptyParentDepth = -3, meaning that the method should throw
     * an exception indicating that the parameter (which is less than -1) is
     * invalid)
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void pruneParentDirectoriesInvalid() throws IOException {
        final String parentDir = createRandomDirectory(testPathPrefix, 1);
        final String childDir = createRandomDirectory(parentDir, 5);
        // This will throw an illegal argument exception.
        mantaClient.delete(childDir, null, -3);
    }

    /**
     * This will use a file and not just directories. Previous tests only used
     * directories, but this one will add a file.
     *
     * @throws IOException - when there is an error that is not accounted for.
     */
    @Test
    public void pruneParentDirectoriesWithFile() throws IOException {
        final String parentDir = createRandomDirectory(testPathPrefix, 1);
        final String childDir = createRandomDirectory(parentDir, 5);
        LOG.debug("CHILD DIR  : " + childDir);
        LOG.debug("Parent DIR : " + parentDir);
        // This should delete the child, but not delete any of the parents.

        String file = String.format("%s/%s", childDir, UUID.randomUUID());
        mantaClient.put(file, TEST_DATA);
        mantaClient.putDirectory(file);
        LOG.debug("CHILD DIR  : " + childDir);

        mantaClient.delete(file, null, 1);
        Assert.assertFalse(mantaClient.existsAndIsAccessible(file));
        Assert.assertFalse(mantaClient.existsAndIsAccessible(childDir));
        Assert.assertTrue(mantaClient.existsAndIsAccessible(parentDir));
    }

    /**
     * This will use a file and not just directories. Previous tests only used
     * directories, but this one will add a file.
     *
     * @throws IOException - when there is an error that is not accounted for.
     */
    @Test
    public void pruneParentDirectoriesFailingWithFile() throws IOException {
        final String parentDir = createRandomDirectory(testPathPrefix, 1);
        final String childDir = createRandomDirectory(parentDir, 5);
        LOG.debug("CHILD DIR  : " + childDir);
        LOG.debug("Parent DIR : " + parentDir);
        // This should delete the child, but not delete any of the parents.

        String file = String.format("%s/%s", childDir, UUID.randomUUID());
        mantaClient.put(file, TEST_DATA);
        mantaClient.putDirectory(file);

        String file2 = String.format("%s/%s", childDir, UUID.randomUUID());
        mantaClient.put(file2, TEST_DATA);
        mantaClient.putDirectory(file2);
        mantaClient.delete(file, null, 2);
        Assert.assertFalse(mantaClient.existsAndIsAccessible(file));
        Assert.assertTrue(mantaClient.existsAndIsAccessible(file2));
        Assert.assertTrue(mantaClient.existsAndIsAccessible(parentDir));
    }

    @Test
    public void pruneParentDirectoriesFailingGreaterThanDirPath() throws IOException {
        final String parentDir = createRandomDirectory(testPathPrefix, 1);
        final String childDir = createRandomDirectory(parentDir, 2);
        // This should delete the child, but not delete any of the parents.
        mantaClient.delete(childDir, null, 10);
        Assert.assertFalse(mantaClient.existsAndIsAccessible(parentDir));
        Assert.assertFalse(mantaClient.existsAndIsAccessible(childDir));
    }

    /**
     * This will create a hierarchy of random directories with the starting point of parent with the given depth.
     *
     * @param parent - the directory to create this from.
     * @param depth - the desired depth from the parent.
     */
    private String createRandomDirectory(final String parent, final int depth) throws IOException {
        final StringBuilder parentDirBuilder = new StringBuilder(parent);
        for (int i = 0; i < depth; i++) {
            parentDirBuilder.append(SEPARATOR).append(STRING_GENERATOR.generate(3));
        }
        final String dirPath = parentDirBuilder.toString();
        mantaClient.putDirectory(dirPath, true);
        return dirPath;
    }
}
