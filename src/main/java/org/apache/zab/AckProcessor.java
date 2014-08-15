/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
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

package org.apache.zab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.zab.proto.ZabMessage;
import org.apache.zab.proto.ZabMessage.Message;
import org.apache.zab.proto.ZabMessage.Message.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts acknowledgment from peers and broadcasts COMMIT message if there're
 * any committed transactions.
 */
public class AckProcessor implements RequestProcessor,
                                     Callable<Void> {

  private final BlockingQueue<MessageTuple> ackQueue =
      new LinkedBlockingQueue<MessageTuple>();

  /**
   * The quorum set in main thread.
   */
  private final Map<String, PeerHandler> quorumSetOriginal;

  /**
   * The quorum set for AckProcessor.
   */
  private final Map<String, PeerHandler> quorumSet;

  /**
   * Current cluster configuration.
   */
  private ClusterConfiguration clusterConfig;

  /**
   * The pending configuration which has not been committed yet.
   */
  private ClusterConfiguration pendingConfig;

  private static final Logger LOG =
      LoggerFactory.getLogger(AckProcessor.class);

  Future<Void> ft;

  /**
   * Last committed zxid sent by AckProcessor. Used to avoid sending duplicated
   * COMMIT message.
   */
  private Zxid lastCommittedZxid;

  public AckProcessor(Map<String, PeerHandler> quorumSet,
                      ClusterConfiguration cnf,
                      Zxid lastCommittedZxid) {
    this.quorumSetOriginal = quorumSet;
    this.quorumSet = new HashMap<String, PeerHandler>(quorumSet);
    this.clusterConfig = cnf;
    this.lastCommittedZxid = lastCommittedZxid;
    ExecutorService es =
        Executors.newSingleThreadExecutor(DaemonThreadFactory.FACTORY);
    ft = es.submit(this);
    es.shutdown();
  }

  @Override
  public void processRequest(MessageTuple request) {
    this.ackQueue.add(request);
  }

  // Given a cluster configuration and the quorum set, find out the last zxid of
  // the transactions which could be committed for the given cluster
  // configuration.
  private Zxid getCommittedZxid(ClusterConfiguration cnf) {
    ArrayList<Zxid> zxids = new ArrayList<Zxid>();
    for (PeerHandler ph : quorumSet.values()) {
      LOG.debug("Last zxid of {} is {}",
                ph.getServerId(),
                ph.getLastAckedZxid());
      Zxid ackZxid = ph.getLastAckedZxid();
      if (ackZxid != null && cnf.contains(ph.getServerId())) {
        // Add to list only if it's in the given cluster configuration and it
        // has sent at least one ACK to leader.
        zxids.add(ph.getLastAckedZxid());
      }
    }
    int quorumSize = cnf.getQuorumSize();
    if (zxids.size() < quorumSize) {
      // It's impossible to be committed.
      return this.lastCommittedZxid;
    }
    // Sorts the last ACK zxid of each peer to find one transaction which
    // can be committed safely.
    Collections.sort(zxids);
    return zxids.get(zxids.size() - quorumSize);
  }

  @Override
  public Void call() throws Exception {
    LOG.debug("AckProcessor gets started.");
    try {
      while (true) {
        MessageTuple request = ackQueue.take();
        if (request == MessageTuple.REQUEST_OF_DEATH) {
          break;
        }
        Message msg = request.getMessage();
        String source = request.getServerId();
        if (msg.getType() == MessageType.ACK) {
          ZabMessage.Ack ack = request.getMessage().getAck();
          Zxid zxid = MessageBuilder.fromProtoZxid(ack.getZxid());
          LOG.debug("Got ACK {} from {}", zxid, source);
          this.quorumSet.get(source).setLastAckedZxid(zxid);
          // The zxid of last transaction which could be committed.
          Zxid zxidCanCommit = null;
          // Check if there's a pending reconfiguration.
          if (this.pendingConfig != null) {
            // Find out the last transaction which can be committed for pending
            // configuration.
            zxidCanCommit = getCommittedZxid(this.pendingConfig);
            LOG.debug("Pending configuration can COMMIT {}", zxidCanCommit);
            if (zxidCanCommit.compareTo(pendingConfig.getVersion()) >= 0) {
              // The pending configuration is just committed, make it becomes
              // current configuration.
              this.clusterConfig = this.pendingConfig;
              this.pendingConfig = null;
            } else {
              // Still hasn't been committed yet.
              zxidCanCommit = null;
            }
          }
          if (zxidCanCommit == null) {
            // Find out the last transaction which can be committed for current
            // configuration.
            zxidCanCommit = getCommittedZxid(this.clusterConfig);
            if (pendingConfig != null &&
                zxidCanCommit.compareTo(pendingConfig.getVersion()) >= 0) {
              // We still shouldn't commit any transaction after COP if they
              // are just acknowledged by a quorum of old configuration.
              Zxid version = pendingConfig.getVersion();
              // Then commit the transactions up to the one before COP.
              zxidCanCommit =
                new Zxid(version.getEpoch(), version.getXid() - 1);
            }
          }
          LOG.debug("Can COMMIT : {}", zxidCanCommit);
          if (zxidCanCommit.compareTo(this.lastCommittedZxid) > 0) {
            // Avoid sending duplicated COMMIT message.
            LOG.debug("Will send commit {} to quorumSet.", zxidCanCommit);
            Message commit = MessageBuilder.buildCommit(zxidCanCommit);
            for (PeerHandler ph : quorumSet.values()) {
              ph.queueMessage(commit);
            }
            this.lastCommittedZxid = zxidCanCommit;
          }
        } else if (msg.getType() == MessageType.JOIN ||
                   msg.getType() == MessageType.ACK_EPOCH) {
          PeerHandler ph = quorumSetOriginal.get(source);
          if (ph != null) {
            this.quorumSet.put(source, ph);
          }
          if (msg.getType() == MessageType.JOIN) {
            LOG.debug("Got JOIN({}) from {}", request.getZxid(), source);
            if (pendingConfig != null) {
              LOG.error("A pending reconfig is still in progress, a bug?");
              throw new RuntimeException("Still has pending reconfiguration");
            }
            this.pendingConfig = this.clusterConfig.clone();
            this.pendingConfig.addPeer(source);
            // Update zxid for this reconfiguration.
            this.pendingConfig.setVersion(request.getZxid());
          }
        } else if (msg.getType() == MessageType.DISCONNECTED) {
          String peerId = msg.getDisconnected().getServerId();
          LOG.debug("Got DISCONNECTED from {}.", peerId);
          this.quorumSet.remove(peerId);
        } else if (msg.getType() == MessageType.REMOVE) {
          String serverId = msg.getRemove().getServerId();
          LOG.debug("Got REMOVE({})for {}", request.getZxid(), serverId);
          if (pendingConfig != null) {
            LOG.error("A pending reconfig is still in progress, a bug?");
            throw new RuntimeException("Still has pending reconfiguration");
          }
          this.pendingConfig = this.clusterConfig.clone();
          this.pendingConfig.removePeer(serverId);
          // Update zxid for this reconfiguration.
          this.pendingConfig.setVersion(request.getZxid());
        } else {
          LOG.warn("Got unexpected message.");
        }
      }
    } catch (RuntimeException e) {
      LOG.error("Caught exception in AckProcessor!", e);
      throw e;
    }
    LOG.debug("AckProcesser has been shut down.");
    return null;
  }

  @Override
  public void shutdown() throws InterruptedException, ExecutionException {
    this.ackQueue.add(MessageTuple.REQUEST_OF_DEATH);
    this.ft.get();
  }
}
