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
package org.apache.catalina.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.net.openssl.OpenSSLStatus;

/**
 * When FIPS mode is requested but cannot be entered (the OpenSSL used for the tests has no FIPS provider), the
 * {@link OpenSSLLifecycleListener} must fail securely - as {@link AprLifecycleListener} does - and prevent the server
 * from initialising rather than continuing without FIPS.
 */
@RunWith(Parameterized.class)
public class TestOpenSSLLifecycleListener {

    @Parameterized.Parameters(name = "{index}: FIPSMode [{0}]")
    public static Collection<Object[]> parameters() {
        List<Object[]> parameterSets = new ArrayList<>();
        parameterSets.add(new Object[] { "on", Boolean.TRUE });
        parameterSets.add(new Object[] { "require", Boolean.TRUE });
        parameterSets.add(new Object[] { "enter", Boolean.TRUE });
        parameterSets.add(new Object[] { "off", Boolean.FALSE });
        return parameterSets;
    }

    @Parameter(0)
    public String fipsMode;

    @Parameter(1)
    public boolean expectFailure;


    @Before
    public void checkFFM() {
        // The listener only uses OpenSSL via FFM
        Assume.assumeTrue(JreCompat.isJre22Available());
    }


    @After
    public void resetFIPSMode() {
        // FIPS mode is static. Restore the default once OpenSSL has been shut down.
        new OpenSSLLifecycleListener().setFIPSMode("off");
    }


    @Test
    public void testBeforeInitEvent() {
        StandardServer server = new StandardServer();
        OpenSSLLifecycleListener listener = new OpenSSLLifecycleListener();
        listener.setFIPSMode(fipsMode);
        Assert.assertEquals("FIPS mode could not be configured", fipsMode, listener.getFIPSMode());

        Error error = null;
        try {
            listener.lifecycleEvent(new LifecycleEvent(server, Lifecycle.BEFORE_INIT_EVENT, null));
        } catch (Error e) {
            error = e;
        }

        if (expectFailure) {
            Assert.assertNotNull("FIPS mode [" + fipsMode + "] was requested and is not available " +
                    "but the listener did not fail", error);
            Assert.assertFalse(listener.isFIPSModeActive());
            // OpenSSL must have been shut down
            Assert.assertFalse(OpenSSLStatus.isAvailable());
            Assert.assertFalse(OpenSSLStatus.isInitialized());
        } else {
            Assert.assertNull(error);
            listener.lifecycleEvent(new LifecycleEvent(server, Lifecycle.AFTER_DESTROY_EVENT, null));
        }
    }


    @Test
    public void testServerInit() throws Exception {
        StandardServer server = new StandardServer();
        OpenSSLLifecycleListener listener = new OpenSSLLifecycleListener();
        listener.setFIPSMode(fipsMode);
        Assert.assertEquals("FIPS mode could not be configured", fipsMode, listener.getFIPSMode());
        server.addLifecycleListener(listener);

        LifecycleException lifecycleException = null;
        try {
            server.init();
        } catch (LifecycleException e) {
            lifecycleException = e;
        }

        if (expectFailure) {
            Assert.assertNotNull("FIPS mode [" + fipsMode + "] was requested and is not available " +
                    "but the server initialised", lifecycleException);
            Assert.assertTrue(lifecycleException.getCause() instanceof Error);
            Assert.assertEquals(LifecycleState.FAILED, server.getState());
            Assert.assertFalse(OpenSSLStatus.isAvailable());
        } else {
            Assert.assertNull(lifecycleException);
            Assert.assertEquals(LifecycleState.INITIALIZED, server.getState());
            server.destroy();
        }
    }
}
