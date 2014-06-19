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

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
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

    private final int workers;

    public JmxDaemon(SocketAddress listenAddress, int workers) {
        this.listenAddress = listenAddress;
        this.workers = workers;
    }

    public void start() {
        ExecutorService bossPool = Executors.newCachedThreadPool();
        log.info("Creating worker thread pool with " + workers + " threads.");
        ExecutorService workerPool = Executors.newFixedThreadPool(workers);
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
        int workers = 50;

        Options options = new Options();
        options.addOption("p", "port", true, "the port number to listen to (defaults to 2713)");
        options.addOption("l", "listen", true, "the address the daemon will listen to (defaults to localhost)");
        options.addOption("h", "help", false, "displays this message");
        options.addOption("w", "workers", true, "number of worker threads, (default 50)");
        CommandLineParser parser = new BasicParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("p")) {
                try {
                    port = Integer.parseInt(cmd.getOptionValue("p"));
                } catch (NumberFormatException nfe) {
                    System.err.println("Invalid Port number: " + cmd.getOptionValue("p"));
                    System.exit(1);
                }
            }
            if (cmd.hasOption("w")) {
                try {
                    workers = Integer.parseInt(cmd.getOptionValue("w"));
                } catch (NumberFormatException nfe) {
                    System.err.println("Invalid workers number: " + cmd.getOptionValue("w"));
                    System.exit(1);
                }
            }
            if (cmd.hasOption("l")) {
                listendAddress = cmd.getOptionValue("l");
            }
            if (cmd.hasOption("h")) {
                displayHelp(options);
                System.exit(0);
            }
        } catch (org.apache.commons.cli.ParseException pe) {
            System.err.println("Unable to parse options: " + pe.getMessage());
            System.exit(1);
        }

        InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        JmxDaemon daemon = new JmxDaemon(new InetSocketAddress(listendAddress, port), workers);
        daemon.start();
    }

    private static void displayHelp(Options o) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("jmxdaemon", o);
        System.out.println();
        System.out.println("Summary of available commands");
        System.out.println();
        displayHelpGET();
        System.out.println();
        displayHelpCLOSE();
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
