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
package org.apache.catalina.realm;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.naming.AuthenticationException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsInstanceOf.instanceOf;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.naming.NameParserImpl;
import org.apache.tomcat.unittest.TesterContext;
import org.apache.tomcat.util.buf.HexUtils;
import org.easymock.EasyMock;

public class TestJNDIRealm {

    private static final String ALGORITHM = "MD5";

    private static final String USER = "test-user";
    private static final String PASSWORD = "test-password";
    private static final String REALM = "test-realm";

    private static final String NONCE = "test-nonce";
    private static final String CNONCE = "cnonce";
    private static final String NC = "00000001";
    private static final String QOP = "auth";
    // Not digested but doesn't matter for the purposes of the test
    private static final String DIGEST_A2 = "method:request-uri";
    public static final String USER_PASSWORD_ATTR = "test-pwd";

    private static final String SIMPLE = "simple";
    private static final String BIND_USER_PATTERN = "cn={0},ou=people,dc=example,dc=com";
    private static final String BIND_USER_DN = "cn=" + USER + ",ou=people,dc=example,dc=com";

    private static MessageDigest md5Helper;

    @BeforeClass
    public static void setupClass() throws Exception {
        md5Helper = MessageDigest.getInstance(ALGORITHM);
    }

    @Test
    public void testAuthenticateWithoutUserPassword() throws Exception {
        // GIVEN
        JNDIRealm realm = buildRealm(PASSWORD);

        // WHEN
        String expectedResponse =
                HexUtils.toHexString(md5Helper.digest((digestA1() + ":" + NONCE + ":" + NC + ":" + CNONCE + ":" + QOP + ":" + DIGEST_A2).getBytes()));
        Principal principal =
                realm.authenticate(USER, expectedResponse, NONCE, NC, CNONCE, QOP, REALM, DIGEST_A2, ALGORITHM);

        // THEN
        Assert.assertNull(principal);
    }

    @Test
    public void testAuthenticateWithUserPassword() throws Exception {
        // GIVEN
        JNDIRealm realm = buildRealm(PASSWORD);
        realm.setUserPassword(USER_PASSWORD_ATTR);

        // WHEN
        String expectedResponse =
                HexUtils.toHexString(md5Helper.digest((digestA1() + ":" + NONCE + ":" + NC + ":" + CNONCE + ":" + QOP + ":" + DIGEST_A2).getBytes()));
        Principal principal =
                realm.authenticate(USER, expectedResponse, NONCE, NC, CNONCE, QOP, REALM, DIGEST_A2, ALGORITHM);

        // THEN
        assertThat(principal, instanceOf(GenericPrincipal.class));
        Assert.assertEquals(PASSWORD, ((GenericPrincipal)principal).getPassword());
    }

    @Test
    public void testAuthenticateWithUserPasswordAndCredentialHandler() throws Exception {
        // GIVEN
        JNDIRealm realm = buildRealm(digestA1());
        realm.setCredentialHandler(buildCredentialHandler());
        realm.setUserPassword(USER_PASSWORD_ATTR);

        // WHEN
        String expectedResponse =
                HexUtils.toHexString(md5Helper.digest((digestA1() + ":" + NONCE + ":" + NC + ":" + CNONCE + ":" + QOP + ":" + DIGEST_A2).getBytes()));
        Principal principal =
                realm.authenticate(USER, expectedResponse, NONCE, NC, CNONCE, QOP, REALM, DIGEST_A2, ALGORITHM);

        // THEN
        assertThat(principal, instanceOf(GenericPrincipal.class));
        Assert.assertEquals(digestA1(), ((GenericPrincipal)principal).getPassword());
    }

    @Test
    public void testErrorRealm() throws Exception {
        Context context = new TesterContext();
        JNDIRealm realm = new JNDIRealm();
        realm.setContainer(context);
        realm.setUserSearch("");
        // Connect to something that will fail
        realm.setConnectionURL("ldap://127.0.0.1:12345");
        realm.start();

        final CountDownLatch latch = new CountDownLatch(3);
        (new Thread(() -> { realm.authenticate("foo", "bar"); latch.countDown(); })).start();
        (new Thread(() -> { realm.authenticate("foo", "bar"); latch.countDown(); })).start();
        (new Thread(() -> { realm.authenticate("foo", "bar"); latch.countDown(); })).start();

        Assert.assertTrue(latch.await(30, TimeUnit.SECONDS));
    }


    @Test
    public void testBindAsUserAuthenticationNone() throws Exception {
        doTestBindAsUser("none");
    }


    @Test
    public void testBindAsUserAuthenticationExternal() throws Exception {
        doTestBindAsUser("EXTERNAL");
    }


    @Test
    public void testBindAsUserAuthenticationGssapi() throws Exception {
        doTestBindAsUser("GSSAPI");
    }


    @Test
    public void testBindAsUserAuthenticationSimple() throws Exception {
        doTestBindAsUser("simple");
    }


    @Test
    public void testBindAsUserAuthenticationDefault() throws Exception {
        doTestBindAsUser(null);
    }


    private void doTestBindAsUser(String authentication) throws Exception {
        Context context = new TesterContext();
        JNDIRealm realm = new JNDIRealm();
        realm.setContainer(context);
        realm.setUserPattern(BIND_USER_PATTERN);
        realm.setAuthentication(authentication);
        // Logger is configured during init()
        realm.init();

        TesterBindDirContext dirContext = new TesterBindDirContext(realm.getDirectoryContextEnvironment());
        // Usually everything is created in create() but that's not the case here
        realm.singleConnection.context = dirContext;

        realm.start();
        try {
            // An invalid password must never authenticate the user, regardless
            // of the configured authentication mechanism
            Assert.assertNull(realm.authenticate(USER, "wrong-" + PASSWORD));
            assertBindEnvironmentRestored(dirContext, authentication);

            // The valid password must still authenticate the user
            Principal principal = realm.authenticate(USER, PASSWORD);
            assertThat(principal, instanceOf(GenericPrincipal.class));
            Assert.assertEquals(USER, principal.getName());
            assertBindEnvironmentRestored(dirContext, authentication);

            // Every bind as the user must have been a simple bind
            Assert.assertEquals(2, dirContext.getBindMechanisms().size());
            for (String mechanism : dirContext.getBindMechanisms()) {
                Assert.assertEquals(SIMPLE, mechanism);
            }
        } finally {
            realm.stop();
        }
    }


    private void assertBindEnvironmentRestored(TesterBindDirContext dirContext, String authentication)
            throws NamingException {
        Hashtable<?,?> env = dirContext.getEnvironment();
        // The configured authentication mechanism must have been restored
        Assert.assertEquals(authentication, env.get(javax.naming.Context.SECURITY_AUTHENTICATION));
        // The user's credentials must have been removed
        Assert.assertFalse(env.containsKey(javax.naming.Context.SECURITY_PRINCIPAL));
        Assert.assertFalse(env.containsKey(javax.naming.Context.SECURITY_CREDENTIALS));
    }


    private JNDIRealm buildRealm(String password) throws NamingException,
            NoSuchFieldException, IllegalAccessException, LifecycleException {
        Context context = new TesterContext();
        JNDIRealm realm = new JNDIRealm();
        realm.setContainer(context);
        realm.setUserSearch("");

        // Usually everything is created in create() but that's not the case here
        Field field = JNDIRealm.class.getDeclaredField("singleConnection");
        field.setAccessible(true);
        Field field2 = JNDIRealm.JNDIConnection.class.getDeclaredField("context");
        field2.setAccessible(true);
        field2.set(field.get(realm), mockDirContext(mockSearchResults(password)));

        realm.start();

        return realm;
    }

    private MessageDigestCredentialHandler buildCredentialHandler()
            throws NoSuchAlgorithmException {
        MessageDigestCredentialHandler credentialHandler = new MessageDigestCredentialHandler();
        credentialHandler.setAlgorithm(ALGORITHM);
        return credentialHandler;
    }

    private NamingEnumeration<SearchResult> mockSearchResults(String password)
            throws NamingException {
        NamingEnumeration<SearchResult> searchResults =
                EasyMock.createNiceMock(NamingEnumeration.class);
        EasyMock.expect(Boolean.valueOf(searchResults.hasMore()))
                .andReturn(Boolean.TRUE)
                .andReturn(Boolean.FALSE)
                .andReturn(Boolean.TRUE)
                .andReturn(Boolean.FALSE);
        EasyMock.expect(searchResults.next())
                .andReturn(new SearchResult("ANY RESULT", "",
                        new BasicAttributes(USER_PASSWORD_ATTR, password)))
                .times(2);
        EasyMock.replay(searchResults);
        return searchResults;
    }

    private DirContext mockDirContext(NamingEnumeration<SearchResult> namingEnumeration)
            throws NamingException {
        DirContext dirContext = EasyMock.createNiceMock(InitialDirContext.class);
        EasyMock.expect(dirContext.search(EasyMock.anyString(), EasyMock.anyString(),
                        EasyMock.anyObject(SearchControls.class)))
                .andReturn(namingEnumeration)
                .times(2);
        EasyMock.expect(dirContext.getNameParser(""))
                .andReturn(new NameParserImpl()).times(2);
        EasyMock.expect(dirContext.getNameInNamespace())
                .andReturn("ANY NAME")
                .times(2);
        EasyMock.replay(dirContext);
        return dirContext;
    }

    private String digestA1() {
        String a1 = USER + ":" + REALM + ":" + PASSWORD;
        return HexUtils.toHexString(md5Helper.digest(a1.getBytes()));
    }


    /*
     * Simulates the bind behaviour of an LDAP server accessed via the JNDI LDAP
     * provider. The provided credentials are only checked for a simple bind.
     * Anonymous binds ("none") and SASL EXTERNAL binds ignore the password.
     */
    private static class TesterBindDirContext extends InitialDirContext {

        private final Hashtable<Object,Object> env = new Hashtable<>();
        private final List<String> bindMechanisms = new ArrayList<>();

        TesterBindDirContext(Hashtable<String,String> env) throws NamingException {
            // Lazy so no connection to a directory server is ever attempted
            super(true);
            this.env.putAll(env);
        }

        List<String> getBindMechanisms() {
            return bindMechanisms;
        }

        @Override
        public Hashtable<?,?> getEnvironment() throws NamingException {
            return new Hashtable<>(env);
        }

        @Override
        public Object addToEnvironment(String propName, Object propVal) throws NamingException {
            return env.put(propName, propVal);
        }

        @Override
        public Object removeFromEnvironment(String propName) throws NamingException {
            return env.remove(propName);
        }

        @Override
        public Attributes getAttributes(String name, String[] attrIds) throws NamingException {
            Object mechanism = env.get(javax.naming.Context.SECURITY_AUTHENTICATION);
            if (mechanism == null) {
                // JNDI LDAP provider default
                mechanism = env.containsKey(javax.naming.Context.SECURITY_CREDENTIALS) ? SIMPLE : "none";
            }
            bindMechanisms.add(mechanism.toString());
            if (SIMPLE.equals(mechanism)) {
                if (!BIND_USER_DN.equals(env.get(javax.naming.Context.SECURITY_PRINCIPAL)) ||
                        !PASSWORD.equals(env.get(javax.naming.Context.SECURITY_CREDENTIALS))) {
                    throw new AuthenticationException("Invalid credentials");
                }
            }
            // "none" (anonymous) and "EXTERNAL" binds succeed without checking
            // the password
            return new BasicAttributes();
        }

        @Override
        public void close() throws NamingException {
            // NO-OP
        }
    }
}
