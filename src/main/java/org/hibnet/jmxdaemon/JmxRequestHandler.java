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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ReflectionException;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JmxRequestHandler extends SimpleChannelHandler {

    private static final String REQ_CMD_GET = "GET";

    private static final String REQ_CMD_CLOSE = "CLOSE";

    private static final String REQ_SEP = " ";

    private static final String RESP_OK = "OK";

    private static final String RESP_ERR = "ERR";

    private static final String RESP_SEP = "\n";

    private static final String RESP_ERR_NO_CMD = "NO_CMD";

    private static final String RESP_ERR_UNKNOWN_CMD = "UNKNOWN_CMD";

    private static final String RESP_ERR_ARGS_LEN = "INVALID_ARGUMENT_LENGTH";

    private static final String RESP_ERR_CONN = "CONNECTION_FAILED";

    private static final String RESP_ERR_IO = "IO_ERROR";

    private static final String RESP_NA = "N/A";

    private static final Logger log = LoggerFactory.getLogger(JmxRequestHandler.class);

    private Map<String, JmxConnectionHolder> connectionCache = new ConcurrentHashMap<>();

    private JmxConnectionHolder getConnection(StringBuilder response, String url) {
        try {
            return getConnection(url);
        } catch (IOException e) {
            response.append(RESP_ERR);
            response.append(RESP_SEP);
            response.append(RESP_ERR_CONN);
            response.append(RESP_SEP);
            writeExceptionMessage(response, e);
            return null;
        }
    }

    private JmxConnectionHolder getConnection(String url) throws IOException {
        JmxConnectionHolder connection = connectionCache.get(url);
        if (connection == null) {
            synchronized (connectionCache) {
                connection = connectionCache.get(url);
                if (connection == null) {
                    connection = new JmxConnectionHolder(url);
                    connectionCache.put(url, connection);
                }
            }
        }
        return connection;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        String[] request = ((String) e.getMessage()).trim().split(REQ_SEP);
        StringBuilder response = new StringBuilder();
        if (request.length == 0 || (request.length == 1 && request[0].length() == 0)) {
            response.append(RESP_ERR);
            response.append(RESP_SEP);
            response.append(RESP_ERR_NO_CMD);
        } else if (request[0].equals(REQ_CMD_GET)) {
            if (request.length < 4 || (request.length - 2) % 2 != 0) {
                response.append(RESP_ERR);
                response.append(RESP_SEP);
                response.append(RESP_ERR_ARGS_LEN);
                response.append(RESP_SEP);
                response.append("Expecting an odd number of arguments and at least 3 but there was "
                        + (request.length - 1));
            } else {
                String url = request[1];
                JmxConnectionHolder connection = getConnection(response, url);
                if (connection != null) {
                    response.append(RESP_OK);
                    for (int i = 2; i < request.length; i += 2) {
                        response.append(RESP_SEP);
                        Object value;
                        try {
                            value = connection.getAttributeValue(request[i], request[i + 1]);
                            response.append(value.toString());
                        } catch (MalformedObjectNameException | InstanceNotFoundException | IntrospectionException
                                | AttributeNotFoundException | ReflectionException | MBeanException ex) {
                            log.warn("Error on {} for bean '{}' getting attribute '{}': {} ({})", url, request[i],
                                    request[i + 1], ex.getMessage(), ex.getClass().getSimpleName(), ex);
                            response.append(RESP_NA);
                        } catch (IOException ex) {
                            log.warn("IO error on connection {}", url, ex);
                            connection.close();
                            response.setLength(0);
                            response.append(RESP_ERR);
                            response.append(RESP_SEP);
                            response.append(RESP_ERR_IO);
                            response.append(RESP_SEP);
                            writeExceptionMessage(response, ex);
                            break;
                        }
                    }
                }
            }
        } else if (request[0].equals(REQ_CMD_CLOSE)) {
            if (request.length != 2) {
                response.append(RESP_ERR);
                response.append(RESP_SEP);
                response.append(RESP_ERR_ARGS_LEN);
                response.append(RESP_SEP);
                response.append("Expecting 1 argument but there was " + (request.length - 1));
            } else {
                String url = request[1];
                JmxConnectionHolder connection = getConnection(response, url);
                if (connection != null) {
                    connection.close();
                    response.append(RESP_OK);
                }
            }
        } else {
            response.append(RESP_ERR);
            response.append(RESP_SEP);
            response.append(RESP_ERR_UNKNOWN_CMD);
            response.append(RESP_SEP);
            response.append(request[0]);
        }
        response.append(RESP_SEP);

        // send the response back
        Channel channel = e.getChannel();
        ChannelFuture channelFuture = Channels.future(e.getChannel());
        ChannelEvent responseEvent = new DownstreamMessageEvent(channel, channelFuture, response.toString(),
                channel.getRemoteAddress());
        ctx.sendDownstream(responseEvent);

        super.messageReceived(ctx, e);
    }

    private void writeExceptionMessage(StringBuilder response, IOException ex) {
        response.append(ex.getClass().getSimpleName() + ": "
                + ex.getMessage().replaceAll("\n", " ").replaceAll("\t", " "));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        if (e.getCause() instanceof IOException && e.getCause().getMessage().equals("Connection reset by peer")) {
            // we don't care
            return;
        }
        log.warn("Unexpected Error", e.getCause());
    }

    public void closeJmxConnections() {
        synchronized (connectionCache) {
            for (JmxConnectionHolder connection : connectionCache.values()) {
                try {
                    connection.close();
                } catch (Throwable e) {
                    log.warn("Error while closing connection {}", connection, e);
                }
            }
            connectionCache.clear();
        }
    }
}
