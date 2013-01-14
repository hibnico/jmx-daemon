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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.DelimiterBasedFrameDecoder;
import org.jboss.netty.handler.codec.string.StringDecoder;
import org.jboss.netty.handler.codec.string.StringEncoder;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Slf4JLoggerFactory;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JmxDaemon {

    private static final Logger log = LoggerFactory.getLogger(JmxDaemon.class);

    private SocketAddress listenAddress;

    private ServerBootstrap bootstrap;

    private JmxRequestHandler jmxRequestHandler;

    public JmxDaemon(SocketAddress listenAddress) {
        this.listenAddress = listenAddress;
    }

    public void start() {
        ExecutorService bossPool = Executors.newCachedThreadPool();
        ExecutorService workerPool = Executors.newCachedThreadPool();
        bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(bossPool, workerPool));
        jmxRequestHandler = new JmxRequestHandler();
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(
                        new DelimiterBasedFrameDecoder(1024 * 1024, ChannelBuffers
                                .copiedBuffer("\n", CharsetUtil.UTF_8)), new StringDecoder(), new StringEncoder(),
                        jmxRequestHandler);
            };
        });
        bootstrap.bind(listenAddress);
        log.info("Starting listening to {}", listenAddress);
    }

    public void stop() {
        jmxRequestHandler.closeJmxConnections();
        bootstrap.releaseExternalResources();
    }

    public static void main(String[] args) {
        String listendAddress = "localhost";
        int port = 2713;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-h") || args[i].equals("--help")) {
                if (args.length > i) {
                    String cmd = args[++i].toUpperCase();
                    if (cmd.equals(JmxRequestHandler.REQ_CMD_GET)) {
                        displayHelpGET();
                    } else if (cmd.equals(JmxRequestHandler.REQ_CMD_CLOSE)) {
                        displayHelpCLOSE();
                    } else {
                        System.err.println("Unknwon command " + cmd);
                        System.exit(1);
                    }
                } else {
                    displayHelp();
                }
                System.exit(0);
            }
            if (args[i].equals("-p") || args[i].equals("--port")) {
                if (args.length > i) {
                    try {
                        port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("Expecting a port number after '-p' or '--port' option");
                        System.exit(1);
                    }
                } else {
                    System.err.println("Expecting a port number after '-p' or '--port' option");
                    System.exit(1);
                }
            }
            if (args[i].equals("-l") || args[i].equals("--listen")) {
                if (args.length > i) {
                    listendAddress = args[++i];
                } else {
                    System.err.println("Expecting a listen address after '-l' or '--listen' option");
                    System.exit(1);
                }
            }
        }

        InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        JmxDaemon daemon = new JmxDaemon(new InetSocketAddress(listendAddress, port));
        daemon.start();
    }

    private static void displayHelp() {
        System.out.println("Usage:");
        System.out.println("    -h, --help          : display this help");
        System.out.println("    -h, --help <cmd>    : display the help about a specific command");
        System.out.println("                          supported commands are: GET, CLOSE");
        System.out.println("    -p, --port <port>   : the port number to listen to (defaults to 2713)");
        System.out.println("    -l, --listen <host> : the address the daemon will listen to (defaults to localhost)");
    }

    private static void displayHelpGET() {
        System.out.println("The GET command retrieve values from JMX beans and returns the values formatted according");
        System.out.println("  the to specified format");
        System.out.println("Usage:");
        System.out.println("  GET <jmxurl> <stringformat> [<beanname> <attribute>]+");
        System.out.println("    jmxurl       : the url of the JMX endpoint");
        System.out.println("    stringformat : the string format of how attributes should be outputed back");
        System.out.println("    beanname     : the name of the JMX bean to query");
        System.out.println("    attribute    : the name of the attribute to get the value from");
        System.out.println("Exemple:");
        System.out.println("  GET service:jmx:rmi:///jndi/rmi://myserver.mydomain.com:7199/jmxrmi \\");
        System.out.println("    'used:%d committed:%d max:%d' \\");
        System.out.println("    java.lang:type=Memory HeapMemoryUsage.used \\");
        System.out.println("    java.lang:type=Memory HeapMemoryUsage.committed \\");
        System.out.println("    java.lang:type=Memory HeapMemoryUsage.max");
    }

    private static void displayHelpCLOSE() {
        System.out.println("The CLOSE command close the closing to JMX endpoint which might have been cached");
        System.out.println("Usage:");
        System.out.println("  CLOSE <jmxurl>");
        System.out.println("    jmxurl : the url of the JMX endpoint");
        System.out.println("Exemple:");
        System.out.println("  CLOSE service:jmx:rmi:///jndi/rmi://myserver.mydomain.com:7199/jmxrmi");
    }
}
