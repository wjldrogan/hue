/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.thriftfs;

import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.TTransportFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * Rip-off of Thrift 0.2.0's TThreadPoolServer which has a deeper and saner queue.
 *
 * See https://issues.apache.org/jira/browse/THRIFT-692
 */
public class SanerThreadPoolServer extends TServer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SanerThreadPoolServer.class.getName());

  // Executor service for handling client connections
  private ExecutorService executorService_;

  // Flag for stopping the server
  private volatile boolean stopped_;

  // Server options
  private Options options_;

  // Customizable server options
  public static class Options {
    public int minWorkerThreads = 5;
    public int maxWorkerThreads = Integer.MAX_VALUE;
    public int stopTimeoutVal = 60;
    public TimeUnit stopTimeoutUnit = TimeUnit.SECONDS;
    /**
     * Size of queue for the underlying ExecutorService, used
     * if all the worker threads are already busy.
     */
    public int queueSize = 0;
  }

  public SanerThreadPoolServer(TProcessor processor,
                           TServerTransport serverTransport) {
    this(processor, serverTransport,
         new TTransportFactory(), new TTransportFactory(),
         new TBinaryProtocol.Factory(), new TBinaryProtocol.Factory());
  }

  public SanerThreadPoolServer(TProcessorFactory processorFactory,
                           TServerTransport serverTransport) {
    this(processorFactory, serverTransport,
         new TTransportFactory(), new TTransportFactory(),
         new TBinaryProtocol.Factory(), new TBinaryProtocol.Factory());
  }

  public SanerThreadPoolServer(TProcessor processor,
                           TServerTransport serverTransport,
                           TProtocolFactory protocolFactory) {
    this(processor, serverTransport,
         new TTransportFactory(), new TTransportFactory(),
         protocolFactory, protocolFactory);
  }

  public SanerThreadPoolServer(TProcessor processor,
                           TServerTransport serverTransport,
                           TTransportFactory transportFactory,
                           TProtocolFactory protocolFactory) {
    this(processor, serverTransport,
         transportFactory, transportFactory,
         protocolFactory, protocolFactory);
  }

  public SanerThreadPoolServer(TProcessorFactory processorFactory,
                           TServerTransport serverTransport,
                           TTransportFactory transportFactory,
                           TProtocolFactory protocolFactory) {
    this(processorFactory, serverTransport,
         transportFactory, transportFactory,
         protocolFactory, protocolFactory);
  }

  public SanerThreadPoolServer(TProcessor processor,
                           TServerTransport serverTransport,
                           TTransportFactory inputTransportFactory,
                           TTransportFactory outputTransportFactory,
                           TProtocolFactory inputProtocolFactory,
                           TProtocolFactory outputProtocolFactory) {
    this(new TProcessorFactory(processor), serverTransport,
         inputTransportFactory, outputTransportFactory,
         inputProtocolFactory, outputProtocolFactory);
  }

  public SanerThreadPoolServer(TProcessorFactory processorFactory,
                           TServerTransport serverTransport,
                           TTransportFactory inputTransportFactory,
                           TTransportFactory outputTransportFactory,
                           TProtocolFactory inputProtocolFactory,
                           TProtocolFactory outputProtocolFactory) {
    super(processorFactory, serverTransport,
          inputTransportFactory, outputTransportFactory,
          inputProtocolFactory, outputProtocolFactory);
    options_ = new Options();
    executorService_ = Executors.newCachedThreadPool();
  }

  public SanerThreadPoolServer(TProcessor processor,
          TServerTransport serverTransport,
          TTransportFactory inputTransportFactory,
          TTransportFactory outputTransportFactory,
          TProtocolFactory inputProtocolFactory,
          TProtocolFactory outputProtocolFactory,
          Options options) {
    this(new TProcessorFactory(processor), serverTransport,
         inputTransportFactory, outputTransportFactory,
         inputProtocolFactory, outputProtocolFactory,
         options);
  }

  public SanerThreadPoolServer(TProcessorFactory processorFactory,
                           TServerTransport serverTransport,
                           TTransportFactory inputTransportFactory,
                           TTransportFactory outputTransportFactory,
                           TProtocolFactory inputProtocolFactory,
                           TProtocolFactory outputProtocolFactory,
                           Options options) {
    super(processorFactory, serverTransport,
          inputTransportFactory, outputTransportFactory,
          inputProtocolFactory, outputProtocolFactory);

    executorService_ = null;

    BlockingQueue<Runnable> executorQueue;
    if (options.queueSize == 0) {
      executorQueue = new SynchronousQueue<Runnable>();
    } else {
      executorQueue = new LinkedBlockingQueue<Runnable>(options.queueSize);
    }


    executorService_ = new ThreadPoolExecutor(options.minWorkerThreads,
                                              options.maxWorkerThreads,
                                              60,
                                              TimeUnit.SECONDS,
                                              executorQueue);

    options_ = options;
  }


  public void serve() {
    try {
      serverTransport_.listen();
    } catch (TTransportException ttx) {
      LOGGER.error("Error occurred during listening.", ttx);
      return;
    }

    stopped_ = false;
    while (!stopped_) {
      int failureCount = 0;
      try {
        TTransport client = serverTransport_.accept();
        WorkerProcess wp = new WorkerProcess(client);
        try {
          executorService_.execute(wp);
        } catch (RejectedExecutionException ree) {
          ++failureCount;
          LOGGER.warn("Execution rejected.", ree);
          client.close();
        }
      } catch (TTransportException ttx) {
        if (!stopped_) {
          ++failureCount;
          LOGGER.warn("Transport error occurred during acceptance of message.", ttx);
        }
      } catch (Error e) {
        if (!stopped_) {
          ++failureCount;
          LOGGER.warn("Uncaught error.", e);
        }
      }
    }

    executorService_.shutdown();

    // Loop until awaitTermination finally does return without a interrupted
    // exception. If we don't do this, then we'll shut down prematurely. We want
    // to let the executorService clear it's task queue, closing client sockets
    // appropriately.
    long timeoutMS = options_.stopTimeoutUnit.toMillis(options_.stopTimeoutVal);
    long now = System.currentTimeMillis();
    while (timeoutMS >= 0) {
      try {
        executorService_.awaitTermination(timeoutMS, TimeUnit.MILLISECONDS);
        break;
      } catch (InterruptedException ix) {
        long newnow = System.currentTimeMillis();
        timeoutMS -= (newnow - now);
        now = newnow;
      }
    }
  }

  public void stop() {
    stopped_ = true;
    serverTransport_.interrupt();
  }

  private class WorkerProcess implements Runnable {

    /**
     * Client that this services.
     */
    private TTransport client_;

    /**
     * Default constructor.
     *
     * @param client Transport to process
     */
    private WorkerProcess(TTransport client) {
      client_ = client;
    }

    /**
     * Loops on processing a client forever
     */
    public void run() {
      TProcessor processor = null;
      TTransport inputTransport = null;
      TTransport outputTransport = null;
      TProtocol inputProtocol = null;
      TProtocol outputProtocol = null;
      try {
        processor = processorFactory_.getProcessor(client_);
        inputTransport = inputTransportFactory_.getTransport(client_);
        outputTransport = outputTransportFactory_.getTransport(client_);
        inputProtocol = inputProtocolFactory_.getProtocol(inputTransport);
        outputProtocol = outputProtocolFactory_.getProtocol(outputTransport);
        // we check stopped_ first to make sure we're not supposed to be shutting
        // down. this is necessary for graceful shutdown.
        while (!stopped_ && processor.process(inputProtocol, outputProtocol)) {}
      } catch (TTransportException ttx) {
        // Assume the client died and continue silently
      } catch (TException tx) {
        LOGGER.error("Thrift error occurred during processing of message.", tx);
      } catch (Exception x) {
        LOGGER.error("Error occurred during processing of message.", x);
      }

      if (inputTransport != null) {
        inputTransport.close();
      }

      if (outputTransport != null) {
        outputTransport.close();
      }
    }
  }
}