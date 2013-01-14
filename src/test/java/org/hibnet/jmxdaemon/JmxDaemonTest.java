/*
 *  Copyright 2013 JMX Daemon contributors
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.hibnet.jmxdaemon;

import static org.hamcrest.collection.IsArrayContainingInOrder.arrayContaining;
import static org.hamcrest.core.IsAnything.anything;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hibnet.jmxdaemon.JmxRequestHandler.RESP_ERR;
import static org.hibnet.jmxdaemon.JmxRequestHandler.RESP_ERR_ARGS_LEN;
import static org.hibnet.jmxdaemon.JmxRequestHandler.RESP_ERR_UNKNOWN_CMD;
import static org.hibnet.jmxdaemon.JmxRequestHandler.RESP_OK;
import static org.hibnet.jmxdaemon.RegexMatcher.matches;
import static org.junit.Assert.assertThat;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.rmi.registry.LocateRegistry;
import java.util.HashMap;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.naming.Context;

import org.hamcrest.Matcher;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Slf4JLoggerFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

@SuppressWarnings("unchecked")
public class JmxDaemonTest {

    private static int jmxDaemonPort = 2713;

    private static int jmxPort = 2714;

    private static JmxDaemon daemon;

    private static JmxDaemonClient client;

    private static RMIConnectorServer jmxServer;

    private static String jmxurl;

    @BeforeClass
    public static void start() throws Exception {
        try {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
            daemon = new JmxDaemon(new InetSocketAddress(jmxDaemonPort));
            daemon.start();
            client = new JmxDaemonClient(new InetSocketAddress(jmxDaemonPort));
            jmxServer = creatJMXConnectorAndRMIRegistry(jmxPort);
            jmxurl = "service:jmx:rmi:///jndi/rmi://localhost:" + jmxPort + "/jmxrmi";
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private static RMIConnectorServer creatJMXConnectorAndRMIRegistry(int rmiRegistryPort) throws Exception {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        LocateRegistry.createRegistry(rmiRegistryPort);
        Map<String, Object> env = new HashMap<String, Object>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.rmi.registry.RegistryContextFactory");
        env.put(Context.PROVIDER_URL, "rmi://localhost:" + rmiRegistryPort);
        JMXServiceURL serviceURL = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:" + rmiRegistryPort
                + "/jmxrmi");
        RMIConnectorServer rmiServer = new RMIConnectorServer(serviceURL, env, mbeanServer);
        rmiServer.start();
        return rmiServer;
    }

    @AfterClass
    public static void stop() throws Exception {
        client.close();
        daemon.stop();
        jmxServer.stop();
    }

    private void assertReceives(String cmd, Matcher<? super String[]> matcher) {
        assertThat(client.send(cmd).split("\n"), matcher);
    }

    @Test
    public void testErrors() throws Exception {
        assertReceives("UNKNOWN", arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_UNKNOWN_CMD), anything()));
        assertReceives("GET", arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("CLOSE", arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("CLOSE someurl someextraarg",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("GET someurl aformat object",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("GET someurl aformat object att object2",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("GET someurl 'qsdf sqdf' object",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("GET someurl 'qsdgdsfg' object att object2",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("GET someurl 'qsdf \\'sqdf' object",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("GET someurl 'qsdg\\'dsfg' object att object2",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("GET someurl \"qsdf \\\"sqdf\" object",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
        assertReceives("GET someurl \"qsdg\\\"dsfg\" object att object2",
                arrayContaining(equalTo(RESP_ERR), equalTo(RESP_ERR_ARGS_LEN), anything()));
    }

    @Test
    public void testGet() {
        assertReceives("GET " + jmxurl + " '%d' java.lang:type=Memory HeapMemoryUsage.used",
                arrayContaining(equalTo(RESP_OK), matches("[0-9]+")));
        assertReceives("CLOSE " + jmxurl, arrayContaining(equalTo(RESP_OK)));
        assertReceives("GET " + jmxurl + " '%d' java.lang:type=Memory HeapMemoryUsage.used",
                arrayContaining(equalTo(RESP_OK), matches("[0-9]+")));
        assertReceives("CLOSE " + jmxurl, arrayContaining(equalTo(RESP_OK)));
        assertReceives("CLOSE " + jmxurl, arrayContaining(equalTo(RESP_OK)));
        assertReceives("GET " + jmxurl + " '%d' java.lang:type=Memory HeapMemoryUsage.used",
                arrayContaining(equalTo(RESP_OK), matches("[0-9]+")));
        assertReceives("GET " + jmxurl + " 'used:%d' java.lang:type=Memory HeapMemoryUsage.used",
                arrayContaining(equalTo(RESP_OK), matches("used:[0-9]+")));
        assertReceives("GET " + jmxurl + " 'used:%d committed:%d max:%d' java.lang:type=Memory HeapMemoryUsage.used"
                + " java.lang:type=Memory HeapMemoryUsage.committed java.lang:type=Memory HeapMemoryUsage.max",
                arrayContaining(equalTo(RESP_OK), matches("used:[0-9]+ committed:[0-9]+ max:[0-9]+")));
    }
}
