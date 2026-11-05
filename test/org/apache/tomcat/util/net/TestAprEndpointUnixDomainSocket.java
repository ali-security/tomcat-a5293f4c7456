/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.catalina.core.AprLifecycleListener;
import org.apache.catalina.startup.LoggingBaseTest;
import org.apache.coyote.http11.Http11AprProtocol;
import org.apache.tomcat.jni.Library;

/**
 * Tests the checks the APR/Native connector applies to the directory in which a Unix Domain Socket is created. The
 * socket is created with permissions determined by the process umask and only restricted afterwards. Unless the
 * directory that contains it is restricted to trusted users, other users can access or replace the socket in the
 * meantime.
 *
 * @deprecated The APR/Native connector has been removed in Tomcat 10.1.x onwards
 */
@Deprecated
public class TestAprEndpointUnixDomainSocket extends LoggingBaseTest {

    private static final String SOCKET_NAME = "tomcat.sock";


    @BeforeClass
    public static void checkAprUnixDomainSocketSupport() {
        Assume.assumeTrue("POSIX file permissions are not supported",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        // An instance must have been created before the APR/Native library will be initialised
        new AprLifecycleListener();
        Assume.assumeTrue("APR/Native is not available", AprLifecycleListener.isAprAvailable());
        Assume.assumeTrue("APR/Native does not support Unix Domain Sockets", Library.APR_HAVE_UNIX);
    }


    @Test
    public void testMissingParentCreatedWithConfiguredPermissions() throws Exception {
        Path parent = createTempDir().resolve("a").resolve("b");
        Path socket = parent.resolve(SOCKET_NAME);

        Exception e = bind(socket, null, "rwx------", null, () -> {
            Assert.assertTrue(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS));
            Assert.assertEquals(perms("rwx------"), Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS));
            Assert.assertTrue(Files.exists(socket, LinkOption.NOFOLLOW_LINKS));
        });

        Assert.assertNull(e);
    }


    @Test
    public void testMissingParentCreatedWithDerivedPermissionsOwnerOnly() throws Exception {
        doTestMissingParentCreatedWithDerivedPermissions("rw-------", "rwx------");
    }


    @Test
    public void testMissingParentCreatedWithDerivedPermissionsGroup() throws Exception {
        doTestMissingParentCreatedWithDerivedPermissions("rw-rw----", "rwxr-x---");
    }


    @Test
    public void testMissingParentCreatedWithDerivedPermissionsOthers() throws Exception {
        doTestMissingParentCreatedWithDerivedPermissions("rw-rw-rw-", "rwxr-xr-x");
    }


    private void doTestMissingParentCreatedWithDerivedPermissions(String socketPermissions,
            String expectedParentPermissions) throws Exception {
        Path parent = createTempDir().resolve("parent");
        Path socket = parent.resolve(SOCKET_NAME);

        Exception e = bind(socket, socketPermissions, null, null, () -> {
            Assert.assertTrue(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS));
            Assert.assertEquals(perms(expectedParentPermissions),
                    Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS));
            Assert.assertTrue(Files.exists(socket, LinkOption.NOFOLLOW_LINKS));
            Assert.assertEquals(perms(socketPermissions),
                    Files.getPosixFilePermissions(socket, LinkOption.NOFOLLOW_LINKS));
        });

        Assert.assertNull(e);
    }


    @Test
    public void testExistingParentWithLaxPermissionsRejected() throws Exception {
        // Any user may add, remove or rename entries in this directory
        Path parent = createExistingDir("rwxrwxrwx");
        Path socket = parent.resolve(SOCKET_NAME);

        Exception e = bind(socket, "rw-------", null, null, null);

        Assert.assertTrue(String.valueOf(e), e instanceof IllegalStateException);
        // The existing directory must not have been modified
        Assert.assertEquals(perms("rwxrwxrwx"), Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS));
    }


    @Test
    public void testExistingParentWithLaxPermissionsRejectedExplicit() throws Exception {
        Path parent = createExistingDir("rwxr-xr-x");
        Path socket = parent.resolve(SOCKET_NAME);

        Exception e = bind(socket, "rw-------", "rwx------", null, null);

        Assert.assertTrue(String.valueOf(e), e instanceof IllegalStateException);
    }


    @Test
    public void testExistingParentWithStricterPermissionsAccepted() throws Exception {
        Path parent = createExistingDir("rwx------");
        Path socket = parent.resolve(SOCKET_NAME);

        Exception e = bind(socket, "rw-rw----", "rwxr-x---", null, () -> {
            Assert.assertTrue(Files.exists(socket, LinkOption.NOFOLLOW_LINKS));
            Assert.assertEquals(perms("rw-rw----"), Files.getPosixFilePermissions(socket, LinkOption.NOFOLLOW_LINKS));
            // The existing directory must not have been modified
            Assert.assertEquals(perms("rwx------"), Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS));
        });

        Assert.assertNull(e);
    }


    @Test
    public void testParentIsSymbolicLinkRejected() throws Exception {
        Path base = createTempDir();
        Path target = base.resolve("target");
        Files.createDirectory(target);
        Files.setPosixFilePermissions(target, perms("rwx------"));
        Path parent = base.resolve("link");
        Files.createSymbolicLink(parent, target);
        Path socket = parent.resolve(SOCKET_NAME);

        Exception e = bind(socket, "rw-------", null, null, null);

        Assert.assertNotNull(e);
    }


    @Test
    public void testExistingParentWithUnexpectedOwnerRejected() throws Exception {
        Path parent = getParentOwnedByOtherUser();
        Path socket = parent.resolve(getUniqueSocketName());
        try {
            // Permissions check passes so that only the ownership check fails
            Exception e = bind(socket, null, "rwxrwxrwx", null, null);

            Assert.assertTrue(String.valueOf(e), e instanceof IllegalStateException);
        } finally {
            Files.deleteIfExists(socket);
        }
    }


    @Test
    public void testExistingParentWithExpectedOwnerAccepted() throws Exception {
        Path parent = getParentOwnedByOtherUser();
        Assume.assumeTrue(Files.isWritable(parent));
        String owner = Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS).getName();
        Path socket = parent.resolve(getUniqueSocketName());
        try {
            Exception e = bind(socket, null, "rwxrwxrwx", owner,
                    () -> Assert.assertTrue(Files.exists(socket, LinkOption.NOFOLLOW_LINKS)));

            Assert.assertNull(e);
        } finally {
            Files.deleteIfExists(socket);
        }
    }


    @Test
    public void testParentOwnerCheckSkipped() throws Exception {
        Path parent = getParentOwnedByOtherUser();
        Assume.assumeTrue(Files.isWritable(parent));
        Path socket = parent.resolve(getUniqueSocketName());
        try {
            Exception e = bind(socket, null, "rwxrwxrwx", "",
                    () -> Assert.assertTrue(Files.exists(socket, LinkOption.NOFOLLOW_LINKS)));

            Assert.assertNull(e);
        } finally {
            Files.deleteIfExists(socket);
        }
    }


    @Test
    public void testParentPermissionsCheckSkipped() throws Exception {
        Path parent = createExistingDir("rwxrwxrwx");
        Path socket = parent.resolve(SOCKET_NAME);

        Exception e = bind(socket, "rw-------", "", null, () -> {
            Assert.assertTrue(Files.exists(socket, LinkOption.NOFOLLOW_LINKS));
            Assert.assertEquals(perms("rw-------"), Files.getPosixFilePermissions(socket, LinkOption.NOFOLLOW_LINKS));
        });

        Assert.assertNull(e);
    }


    @Test
    public void testNoPermissionsConfigured() throws Exception {
        Path parent = createExistingDir("rwxrwxrwx");
        Path socket = parent.resolve(SOCKET_NAME);

        Exception e = bind(socket, null, null, null,
                () -> Assert.assertTrue(Files.exists(socket, LinkOption.NOFOLLOW_LINKS)));

        Assert.assertNull(e);
    }


    /*
     * Binds an AprEndpoint to the given Unix Domain Socket path. If the bind succeeds, the provided checks are
     * performed while the endpoint is still bound since APR removes the socket file when the endpoint is unbound.
     *
     * Returns the exception thrown by the bind or null if the bind succeeded.
     */
    private Exception bind(Path socketPath, String socketPermissions, String parentPermissions, String parentOwner,
            BoundCheck boundCheck) throws Exception {
        AprEndpoint endpoint = new AprEndpoint();
        // The protocol provides the handler the endpoint requires
        Http11AprProtocol protocol = new Http11AprProtocol(endpoint);

        // Configure the endpoint the same way Connector attributes are applied
        Assert.assertTrue(protocol.setProperty("unixDomainSocketPath", socketPath.toString()));
        if (socketPermissions != null) {
            Assert.assertTrue(protocol.setProperty("unixDomainSocketPathPermissions", socketPermissions));
        }
        if (parentPermissions != null) {
            Assert.assertTrue(protocol.setProperty("unixDomainSocketParentPermissions", parentPermissions));
        }
        if (parentOwner != null) {
            Assert.assertTrue(protocol.setProperty("unixDomainSocketParentOwner", parentOwner));
        }

        try {
            try {
                endpoint.init();
            } catch (Exception e) {
                log.info("Unable to bind Unix Domain Socket [" + socketPath + "]", e);
                return e;
            }
            if (boundCheck != null) {
                boundCheck.check();
            }
            return null;
        } finally {
            endpoint.destroy();
        }
    }


    private Path createTempDir() throws IOException {
        // Use java.io.tmpdir to keep the socket path within the length limit for Unix Domain Sockets
        Path dir = Files.createTempDirectory("tomcat-uds-");
        addDeleteOnTearDown(dir.toFile());
        return dir;
    }


    private Path createExistingDir(String permissions) throws IOException {
        Path dir = createTempDir().resolve("existing");
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, perms(permissions));
        return dir;
    }


    private static Path getParentOwnedByOtherUser() throws IOException {
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir")).toRealPath();
        String owner = Files.getOwner(tmpDir, LinkOption.NOFOLLOW_LINKS).getName();
        Assume.assumeFalse("[" + tmpDir + "] is owned by the current user",
                owner.equals(System.getProperty("user.name")));
        return tmpDir;
    }


    private static String getUniqueSocketName() throws IOException {
        File tempFile = File.createTempFile("uds-tomcat-test-", ".sock");
        // Need to delete the file to make way for the actual socket
        Assert.assertTrue(tempFile.delete());
        return tempFile.getName();
    }


    private static Set<PosixFilePermission> perms(String permissions) {
        return PosixFilePermissions.fromString(permissions);
    }


    @FunctionalInterface
    private interface BoundCheck {
        void check() throws Exception;
    }
}
