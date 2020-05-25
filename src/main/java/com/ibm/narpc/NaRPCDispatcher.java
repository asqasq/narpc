/*
 * NaRPC: An NIO-based RPC library
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016-2018, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.narpc;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.LinkedList;
import org.slf4j.Logger;

public class NaRPCDispatcher<R extends NaRPCMessage, T extends NaRPCMessage, C extends NaRPCContext> implements Runnable {
    static private Logger LOG = NaRPCUtils.getLogger();
    
    class RetryRequestState {
      int nrRetries;
      R request;
      NaRPCServerChannel channel;
      long ticket;
      C context;

      RetryRequestState(R request, NaRPCServerChannel channel, long ticket, C context) {
        this.nrRetries = 0;
        this.request = request;
        this.channel = channel;
        this.ticket = ticket;
        this.context = context;
      }
      int getAndIncNrRetries() {
        nrRetries++;
        return (nrRetries);
      }
    }

    private LinkedList<RetryRequestState> retries;
    private NaRPCGroup group;
    private LinkedBlockingQueue<NaRPCServerChannel> incomingChannels;
    private NaRPCService<R,T,C> service;
    private Selector selector;
    private R request;
    private int id;
    private boolean isAlive;
    
    public NaRPCDispatcher() {
    	this.isAlive = true;
    	
    }

    public NaRPCDispatcher(NaRPCGroup group, NaRPCService<R,T,C> service, int id) throws IOException {
    	this.group = group;
        this.service = service;
        this.id = id;
        this.selector = Selector.open();
        this.incomingChannels = new LinkedBlockingQueue<NaRPCServerChannel>();
        this.request = service.createRequest();
        this.retries = new LinkedList<RetryRequestState>();
        this.isAlive = true;
    }

    public void addChannel(NaRPCServerChannel endpoint) throws IOException {
		this.service.addEndpoint(endpoint);
		incomingChannels.add(endpoint);
    	selector.wakeup();
	}

	public void close(){
    	this.isAlive = false;
	}

	@Override
	public void run() {
		try {
			while (this.isAlive) {
				int readyChannels = selector.select(1000);
				if (readyChannels > 0){
					Set<SelectionKey> selectedKeys = selector.selectedKeys();
					Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
					while (keyIterator.hasNext()) {
						SelectionKey key = keyIterator.next();
						if (!key.isValid()){
							continue;
						}
						if (key.isReadable()) {
							NaRPCServerChannel channel = (NaRPCServerChannel) key.attachment();
							long ticket = channel.receiveMessage(request);
							if(ticket > 0){
                C context = service.createContext();
								T response = service.processRequest(request, 0, context);
                if (response == null) {
                  retries.add(new RetryRequestState(request, channel, ticket, context));
                  this.request = service.createRequest();
                  System.out.println("Queued request ticket " + ticket + "\n");
                } else {
								  channel.transmitMessage(ticket, response);
                }
							} else if (ticket < 0){
								LOG.info("closing channel " + channel.address());
								this.service.removeEndpoint(channel);
								key.cancel();
								channel.close();
							} else {
								throw new Exception("ticket number invalid");
							}
						} 
						keyIterator.remove();
					}
				}
				processIncomingChannels();
        processRetryList();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		LOG.info("closing the select call");
	}
	
	public void processIncomingChannels() throws IOException{
		NaRPCServerChannel channel = incomingChannels.poll();
		while(channel != null){
			SocketChannel socket = channel.getSocketChannel();
			socket.configureBlocking(false);
			socket.socket().setTcpNoDelay(group.isNodelay());
			socket.socket().setReuseAddress(true);
			socket.register(selector, SelectionKey.OP_READ, channel);
			LOG.info("adding new channel to selector, from " + socket.getRemoteAddress());
			channel = incomingChannels.poll();
		}		
	}


  private void processRetryList() throws IOException {
    LinkedList<RetryRequestState> tmp = new LinkedList<RetryRequestState>();
    for(RetryRequestState rs: this.retries) {
      System.out.println("Handle queued request " + rs.ticket + " for the " + (rs.nrRetries + 1) + " time\n");
      T response = service.processRequest(rs.request, rs.getAndIncNrRetries(), rs.context);
      if (response == null) {
        System.out.println("request " + rs.ticket + " still pending\n");
        tmp.add(rs);
      } else {
        System.out.println("request " + rs.ticket + " finished successfully after " + rs.nrRetries + " times\n");
			  rs.channel.transmitMessage(rs.ticket, response);
      }
    }
    this.retries = tmp;
  }
}
