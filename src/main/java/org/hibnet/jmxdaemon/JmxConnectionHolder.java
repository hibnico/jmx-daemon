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

import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
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
import javax.management.remote.JMXServiceURL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JmxConnectionHolder {

    private static final Logger log = LoggerFactory.getLogger(JmxConnectionHolder.class);

    private String url;

    private JMXConnector jmxc;

    private MBeanServerConnection server;

    private ReentrantReadWriteLock connectionStateLock = new ReentrantReadWriteLock();

    public JmxConnectionHolder(String url) throws IOException {
        this.url = url;
        ensureConnected(false);
    }

    private void ensureConnected(boolean forRead) throws IOException {
        if (server == null) {
            if (forRead) {
                connectionStateLock.readLock().unlock();
            }
            connectionStateLock.writeLock().lock();
            try {
                if (server == null) {
                    JMXServiceURL jmxurl = new JMXServiceURL(url);
                    jmxc = JMXConnectorFactory.connect(jmxurl, null);
                    server = jmxc.getMBeanServerConnection();
                }
            } finally {
                if (forRead) {
                    connectionStateLock.readLock().lock();
                }
                connectionStateLock.writeLock().unlock();
            }
        }
    }

    public Object getAttribute(ObjectName mxbeanName, String attribute) throws AttributeNotFoundException,
            InstanceNotFoundException, MBeanException, ReflectionException, IOException {
        connectionStateLock.readLock().lock();
        try {
            ensureConnected(true);
            return server.getAttribute(mxbeanName, attribute);
        } finally {
            connectionStateLock.readLock().unlock();
        }
    }

    public Object getAttributeValue(String beanName, String attribute) throws IOException,
            MalformedObjectNameException, InstanceNotFoundException, IntrospectionException, ReflectionException,
            AttributeNotFoundException, MBeanException {
        Object attValue = getAttribute(new ObjectName(beanName), attribute);
        return attValue;
    }

    public void close() {
        connectionStateLock.writeLock().lock();
        try {
            if (server != null) {
                try {
                    jmxc.close();
                } catch (IOException e) {
                    log.warn("IO error while closong connection to {}", url, e);
                }
                jmxc = null;
                server = null;
            }
        } finally {
            connectionStateLock.writeLock().unlock();
        }
    }

    @Override
    public String toString() {
        return url;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((url == null) ? 0 : url.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        JmxConnectionHolder other = (JmxConnectionHolder) obj;
        if (url == null) {
            if (other.url != null) {
                return false;
            }
        } else if (!url.equals(other.url)) {
            return false;
        }
        return true;
    }

}
